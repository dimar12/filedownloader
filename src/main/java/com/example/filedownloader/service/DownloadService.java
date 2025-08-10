package com.example.filedownloader.service;

import com.example.filedownloader.entity.DownloadChunkEntity;
import com.example.filedownloader.model.DownloadTask;
import com.example.filedownloader.model.TaskStatus;
import com.example.filedownloader.repository.DownloadChunkRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

    private final ThreadPoolExecutor downloadExecutor;
    private final ForkJoinPool forkJoinPool;
    private final TaskStatusService taskStatusService;
    private final MetricsService metricsService;
    private final DownloadChunkRepository chunkRepository;

    private final AtomicBoolean downloadsPaused = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeDownloads = new ConcurrentHashMap<>();

    @Value("${file-downloader.download-directory:./downloads}")
    private String downloadDirectory;

    @Value("${file-downloader.download.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${file-downloader.download.max-file-size-mb:500}")
    private int maxFileSizeMb;

    @Value("${file-downloader.download.chunk-size-kb:8192}")
    private int chunkSizeKb;

    @Value("${file-downloader.download.parallel-chunk-size-mb:10}")
    private int parallelChunkSizeMb;

    @Value("${file-downloader.download.parallel-threshold-mb:50}")
    private int parallelThresholdMb;

    private static final long PROGRESS_PERSIST_THRESHOLD_BYTES = 64 * 1024;

    private static final int HTTP_PARTIAL_CONTENT = 206;

    @PostConstruct
    public void initResume() {
        log.info("Инициализация DownloadService: проверка незавершенных задач для возобновления.");
        List<DownloadTask> inProgress = taskStatusService.getTasksByStatus(TaskStatus.IN_PROGRESS);

        inProgress.forEach(task -> {
            log.info("Возобновляем IN_PROGRESS задачу: id={}", task.getId());
            submitDownloadTask(task);
        });
    }

    public CompletableFuture<DownloadTask> submitDownloadTask(DownloadTask task) {
        log.info("Отправка задачи на загрузку: id={}, url={}, filename={}", task.getId(), task.getUrl(), task.getFilename());

        metricsService.incrementTasksSubmitted();

        CompletableFuture<Void> downloadFuture = CompletableFuture
                .runAsync(() -> processDownload(task), downloadExecutor)
                .exceptionally(throwable -> {
                    log.error("Необработанная ошибка в задаче загрузки: taskId={}", task.getId(), throwable);
                    task.markAsFailed(throwable.getMessage());
                    taskStatusService.updateTaskStatus(task);
                    return null;
                });

        activeDownloads.put(task.getId(), downloadFuture);

        return downloadFuture.thenApply(v -> {
            activeDownloads.remove(task.getId());
            return task;
        });
    }

    private void processDownload(DownloadTask task) {
        String threadName = Thread.currentThread().getName();
        log.info("Начало загрузки файла: taskId={}, thread={}, url={}", task.getId(), threadName, task.getUrl());

        task.markAsStarted(threadName);
        taskStatusService.updateTaskStatus(task);
        metricsService.incrementTasksStarted();

        try {
            ensureDownloadDirectoryExists();

            LocalDateTime startTime = LocalDateTime.now();
            long fileSize = downloadFile(task);
            LocalDateTime endTime = LocalDateTime.now();

            double downloadSpeedMbps = calculateDownloadSpeed(fileSize, startTime, endTime);

            chunkRepository.findByTaskIdOrderByChunkIndex(task.getId()).forEach(chunk -> {
                chunk.setStatus(TaskStatus.COMPLETED);
                chunk.setBytesDownloaded(chunk.getEndByte() - chunk.getStartByte() + 1);
                chunk.setUpdatedAt(LocalDateTime.now());
                chunkRepository.save(chunk);
            });

            task.markAsCompleted(fileSize, downloadSpeedMbps);
            taskStatusService.updateTaskStatus(task);
            metricsService.incrementTasksCompleted();
            metricsService.recordDownloadTime(Duration.between(startTime, endTime));
            metricsService.recordDownloadSpeed(downloadSpeedMbps);

            log.info("Загрузка завершена успешно: taskId={}, thread={}, fileSize={} bytes, speed={} Mbps",
                    task.getId(), threadName, fileSize, String.format("%.2f", downloadSpeedMbps));

        } catch (InterruptedException e) {
            log.info("Задача taskId={} прервана из-за shutdown", task.getId());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Ошибка при загрузке файла: taskId={}, thread={}, error={}", task.getId(), threadName, e.getMessage(), e);

            task.markAsFailed(e.getMessage());
            taskStatusService.updateTaskStatus(task);
            metricsService.incrementTasksFailed();

            if (task.canRetry()) {
                log.info("Повторная попытка загрузки: taskId={}, retryCount={}", task.getId(), task.getRetryCount());

                task.setStatus(TaskStatus.PENDING);
                taskStatusService.updateTaskStatus(task);

                try {
                    Thread.sleep(5000 * task.getRetryCount());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

                processDownload(task);
            }
        }
    }

    private long downloadFile(DownloadTask task) throws Exception {
        String urlString = task.getUrl();
        String filename = task.getFilename();

        long contentLength = getContentLength(urlString);

        if (contentLength > maxFileSizeMb * 1024L * 1024L) {
            throw new IOException("Файл слишком большой: " + contentLength + " bytes (максимум " + maxFileSizeMb + " MB)");
        }

        Path filePath = Paths.get(downloadDirectory, filename);

        boolean useParallelDownload = contentLength > parallelThresholdMb * 1024L * 1024L && supportsRangeRequests(urlString);

        if (useParallelDownload) {
            log.info("Использование параллельной загрузки для файла размером {} MB", contentLength / (1024 * 1024));
            return downloadFileInParallel(task, urlString, filePath, contentLength);
        } else {
            return downloadFileSequentially(task, urlString, filePath);
        }
    }

    private long downloadFileInParallel(DownloadTask task, String urlString, Path filePath, long contentLength) throws Exception {
        int numChunks = (int) Math.ceil((double) contentLength / (parallelChunkSizeMb * 1024 * 1024));
        numChunks = Math.min(numChunks, Runtime.getRuntime().availableProcessors() * 2);

        log.info("Загрузка файла {} частями: размер={} MB, частей={}", task.getFilename(), contentLength / (1024 * 1024), numChunks);

        long chunkSize = contentLength / numChunks;

        List<DownloadChunkEntity> chunkEntities = ensureChunkEntitiesExist(task.getId(), numChunks, chunkSize, contentLength);

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.setLength(contentLength);
        }

        AtomicLong totalBytesDownloaded = new AtomicLong(
                chunkEntities.stream().mapToLong(DownloadChunkEntity::getBytesDownloaded).sum()
        );

        List<CompletableFuture<Long>> futures = chunkEntities.stream()
                .map(chunkEntity -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadChunkWithResume(chunkEntity, totalBytesDownloaded, contentLength, task, filePath);
                    } catch (Exception e) {
                        throw new RuntimeException("Ошибка загрузки части " + chunkEntity.getChunkIndex() + " task=" + task.getId(), e);
                    }
                }, forkJoinPool))
                .toList();

        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allOf.get();

        long totalBytes = futures.stream()
                .mapToLong(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .sum();

        log.info("Параллельная загрузка завершена: taskId={}, totalBytes={}", task.getId(), totalBytes);

        return totalBytes;
    }

    private List<DownloadChunkEntity> ensureChunkEntitiesExist(String taskId, int numChunks, long chunkSize, long contentLength) {
        List<DownloadChunkEntity> existing = chunkRepository.findByTaskIdOrderByChunkIndex(taskId);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }

        List<DownloadChunkEntity> toSave = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            long start = i * chunkSize;
            long end = (i == numChunks - 1) ? contentLength - 1 : start + chunkSize - 1;

            DownloadChunkEntity entity = DownloadChunkEntity.builder()
                    .taskId(taskId)
                    .chunkIndex(i)
                    .startByte(start)
                    .endByte(end)
                    .bytesDownloaded(0L)
                    .status(TaskStatus.PENDING)
                    .updatedAt(LocalDateTime.now())
                    .build();
            toSave.add(entity);
        }
        List<DownloadChunkEntity> saved = chunkRepository.saveAll(toSave);
        return saved.stream()
                .sorted(Comparator.comparingInt(DownloadChunkEntity::getChunkIndex))
                .collect(Collectors.toList());
    }

    private long downloadChunkWithResume(DownloadChunkEntity chunkEntity,
                                         AtomicLong totalProgress,
                                         long totalSize,
                                         DownloadTask mainTask,
                                         Path filePath) throws Exception {

        checkPauseState();

        long resumeStart = chunkEntity.getStartByte() + chunkEntity.getBytesDownloaded();
        if (resumeStart > chunkEntity.getEndByte()) {
            chunkEntity.setStatus(TaskStatus.COMPLETED);
            chunkEntity.setUpdatedAt(LocalDateTime.now());
            chunkRepository.save(chunkEntity);
            return chunkEntity.getBytesDownloaded();
        }

        URL url = new URL(mainTask.getUrl());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("User-Agent", "FileDownloaderBot/1.0");
            connection.setRequestProperty("Range", String.format("bytes=%d-%d", resumeStart, chunkEntity.getEndByte()));

            int responseCode = connection.getResponseCode();
            if (responseCode != HTTP_PARTIAL_CONTENT && responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Сервер не поддерживает загрузку по частям. Response code: " + responseCode);
            }

            chunkEntity.setStatus(TaskStatus.IN_PROGRESS);
            chunkEntity.setUpdatedAt(LocalDateTime.now());
            chunkRepository.save(chunkEntity);

            try (InputStream inputStream = connection.getInputStream();
                 RandomAccessFile outputFile = new RandomAccessFile(filePath.toFile(), "rw")) {

                outputFile.seek(resumeStart);

                byte[] buffer = new byte[chunkSizeKb * 1024];
                int bytesRead;
                long chunkBytesRead = chunkEntity.getBytesDownloaded();
                long expectedChunkSize = chunkEntity.getEndByte() - chunkEntity.getStartByte() + 1;

                long bytesSinceLastPersist = 0;
                long lastPersistTime = System.currentTimeMillis();

                while ((bytesRead = inputStream.read(buffer)) != -1 && chunkBytesRead < expectedChunkSize) {

                    checkPauseState();

                    if (Thread.currentThread().isInterrupted()) {
                        persistChunkProgress(chunkEntity, chunkBytesRead);
                        throw new InterruptedException("Загрузка части прервана");
                    }

                    int bytesToWrite = (int) Math.min(bytesRead, expectedChunkSize - chunkBytesRead);
                    outputFile.write(buffer, 0, bytesToWrite);
                    chunkBytesRead += bytesToWrite;
                    bytesSinceLastPersist += bytesToWrite;

                    long currentTotal = totalProgress.addAndGet(bytesToWrite);

                    if (currentTotal % Math.max(1, (totalSize / 10)) < bytesToWrite) {
                        double progress = (double) currentTotal / totalSize * 100;
                        log.debug("Прогресс загрузки taskId={}: {}%", mainTask.getId(), String.format("%.2f", progress));
                    }

                    long now = System.currentTimeMillis();
                    if (bytesSinceLastPersist >= PROGRESS_PERSIST_THRESHOLD_BYTES || now - lastPersistTime >= 1000) {
                        chunkEntity.setBytesDownloaded(chunkBytesRead);
                        chunkEntity.setUpdatedAt(LocalDateTime.now());
                        chunkRepository.save(chunkEntity);
                        bytesSinceLastPersist = 0;
                        lastPersistTime = now;
                    }
                }

                chunkEntity.setBytesDownloaded(chunkBytesRead);
                chunkEntity.setStatus(TaskStatus.COMPLETED);
                chunkEntity.setUpdatedAt(LocalDateTime.now());
                chunkRepository.save(chunkEntity);

                log.debug("Часть {} загружена: {} bytes (task={})", chunkEntity.getChunkIndex(), chunkBytesRead, mainTask.getId());
                return chunkBytesRead;
            }

        } finally {
            connection.disconnect();
        }
    }

    private void persistChunkProgress(DownloadChunkEntity chunkEntity, long bytesDownloaded) {
        chunkEntity.setBytesDownloaded(bytesDownloaded);
        chunkEntity.setUpdatedAt(LocalDateTime.now());
        chunkRepository.save(chunkEntity);
    }

    private long downloadFileSequentially(DownloadTask task, String urlString, Path filePath) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("User-Agent", "FileDownloaderBot/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(filePath.toFile());
                 BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream)) {

                byte[] buffer = new byte[chunkSizeKb * 1024];
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    checkPauseState();

                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Загрузка прервана");
                    }

                    bufferedOutputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }

                bufferedOutputStream.flush();
                return totalBytesRead;
            }

        } finally {
            connection.disconnect();
        }
    }

    private void checkPauseState() throws InterruptedException {
        while (downloadsPaused.get()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Поток прерван во время паузы");
            }
            Thread.sleep(1000);
        }
    }

    private void ensureDownloadDirectoryExists() throws IOException {
        Path dir = Paths.get(downloadDirectory);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Создана директория для загрузок: {}", downloadDirectory);
        }
    }

    private double calculateDownloadSpeed(long bytes, LocalDateTime start, LocalDateTime end) {
        long durationMillis = Duration.between(start, end).toMillis();
        if (durationMillis == 0) return 0.0;

        double bytesPerSecond = (double) bytes / (durationMillis / 1000.0);
        return (bytesPerSecond * 8) / (1024 * 1024);
    }

    public void pauseDownloads() {
        log.info("Приостановка загрузок");
        downloadsPaused.set(true);
    }

    public void resumeDownloads() {
        log.info("Возобновление загрузок");
        downloadsPaused.set(false);
    }

    public void shutdown() {
        log.info("Начало graceful shutdown download service");

        downloadsPaused.set(true);

        activeDownloads.values().forEach(future -> future.cancel(true));
        activeDownloads.clear();

        downloadExecutor.shutdown();
        forkJoinPool.shutdown();

        try {
            if (!downloadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Принудительное завершение потоков загрузки");
                downloadExecutor.shutdownNow();
            }

            if (!forkJoinPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Принудительное завершение ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения потоков", e);
            downloadExecutor.shutdownNow();
            forkJoinPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Download service остановлен");
    }

    private long getContentLength(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("User-Agent", "FileDownloaderBot/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HTTP_PARTIAL_CONTENT) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            long contentLength = connection.getContentLengthLong();
            return contentLength == -1 ? 0 : contentLength;
        } finally {
            connection.disconnect();
        }
    }

    private boolean supportsRangeRequests(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            try {
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setRequestProperty("User-Agent", "FileDownloaderBot/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String acceptRanges = connection.getHeaderField("Accept-Ranges");
                    return "bytes".equalsIgnoreCase(acceptRanges);
                }
                return false;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            log.warn("Не удалось проверить поддержку Range requests: {}", e.getMessage());
            return false;
        }
    }
}