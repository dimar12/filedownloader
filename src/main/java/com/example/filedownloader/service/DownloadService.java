package com.example.filedownloader.service;

import com.example.filedownloader.model.DownloadTask;
import com.example.filedownloader.model.TaskStatus;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

    private final ThreadPoolExecutor downloadExecutor;
    private final ForkJoinPool forkJoinPool;
    private final TaskStatusService taskStatusService;
    private final MetricsService metricsService;

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

    private static final int HTTP_PARTIAL_CONTENT = 206;

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

            task.markAsCompleted(fileSize, downloadSpeedMbps);
            taskStatusService.updateTaskStatus(task);
            metricsService.incrementTasksCompleted();
            metricsService.recordDownloadTime(Duration.between(startTime, endTime));
            metricsService.recordDownloadSpeed(downloadSpeedMbps);

            log.info("Загрузка завершена успешно: taskId={}, thread={}, fileSize={} bytes, speed={} Mbps",
                    task.getId(), threadName, fileSize, String.format("%.2f", downloadSpeedMbps));

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

    private long downloadFileInParallel(DownloadTask task, String urlString, Path filePath, long contentLength) throws Exception {
        int numChunks = (int) Math.ceil((double) contentLength / (parallelChunkSizeMb * 1024 * 1024));
        numChunks = Math.min(numChunks, Runtime.getRuntime().availableProcessors() * 2);

        log.info("Загрузка файла {} частями: размер={} MB, частей={}", task.getFilename(), contentLength / (1024 * 1024), numChunks);

        List<ChunkDownloadTask> chunkTasks = new ArrayList<>();
        long chunkSize = contentLength / numChunks;

        for (int i = 0; i < numChunks; i++) {
            long start = i * chunkSize;
            long end = (i == numChunks - 1) ? contentLength - 1 : start + chunkSize - 1;

            chunkTasks.add(new ChunkDownloadTask(
                    task.getId(), urlString, start, end, i, filePath));
        }

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.setLength(contentLength);
        }

        AtomicLong totalBytesDownloaded = new AtomicLong(0);

        List<CompletableFuture<Long>> futures = chunkTasks.stream()
                .map(chunkTask -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadChunk(chunkTask, totalBytesDownloaded, contentLength, task);
                    } catch (Exception e) {
                        throw new RuntimeException("Ошибка загрузки части " + chunkTask.chunkIndex, e);
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

    private long downloadChunk(ChunkDownloadTask chunkTask, AtomicLong totalProgress,
                               long totalSize, DownloadTask mainTask) throws Exception {

        checkPauseState();

        URL url = new URL(chunkTask.url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("User-Agent", "FileDownloaderBot/1.0");
            connection.setRequestProperty("Range",
                    String.format("bytes=%d-%d", chunkTask.startByte, chunkTask.endByte));

            int responseCode = connection.getResponseCode();
            if (responseCode != HTTP_PARTIAL_CONTENT) {
                throw new IOException("Сервер не поддерживает загрузку по частям. Response code: " + responseCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 RandomAccessFile outputFile = new RandomAccessFile(chunkTask.filePath.toFile(), "rw")) {

                outputFile.seek(chunkTask.startByte);

                byte[] buffer = new byte[chunkSizeKb * 1024];
                int bytesRead;
                long chunkBytesRead = 0;
                long expectedChunkSize = chunkTask.endByte - chunkTask.startByte + 1;

                while ((bytesRead = inputStream.read(buffer)) != -1 &&
                        chunkBytesRead < expectedChunkSize) {

                    checkPauseState();

                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Загрузка части прервана");
                    }

                    int bytesToWrite = (int) Math.min(bytesRead, expectedChunkSize - chunkBytesRead);
                    outputFile.write(buffer, 0, bytesToWrite);
                    chunkBytesRead += bytesToWrite;

                    long currentTotal = totalProgress.addAndGet(bytesToWrite);

                    if (currentTotal % (totalSize / 10) < bytesToWrite) {
                        double progress = (double) currentTotal / totalSize * 100;
                        log.debug("Прогресс загрузки taskId={}: {}%", mainTask.getId(), String.format("%.2f", progress));
                    }
                }

                log.debug("Часть {} загружена: {} bytes", chunkTask.chunkIndex, chunkBytesRead);
                return chunkBytesRead;
            }

        } finally {
            connection.disconnect();
        }
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

    public boolean isDownloadsPaused() {
        return downloadsPaused.get();
    }

    public CompletableFuture<Void> cancelDownload(String taskId) {
        CompletableFuture<Void> downloadFuture = activeDownloads.get(taskId);
        if (downloadFuture != null) {
            downloadFuture.cancel(true);
            activeDownloads.remove(taskId);

            return CompletableFuture.runAsync(() -> {
                taskStatusService.getTask(taskId).ifPresent(task -> {
                    task.markAsFailed("Отменено пользователем");
                    taskStatusService.updateTaskStatus(task);
                });
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    public List<String> getActiveDownloadIds() {
        return new ArrayList<>(activeDownloads.keySet());
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

    private static class ChunkDownloadTask {
        final String taskId;
        final String url;
        final long startByte;
        final long endByte;
        final int chunkIndex;
        final Path filePath;

        ChunkDownloadTask(String taskId, String url, long startByte, long endByte,
                          int chunkIndex, Path filePath) {
            this.taskId = taskId;
            this.url = url;
            this.startByte = startByte;
            this.endByte = endByte;
            this.chunkIndex = chunkIndex;
            this.filePath = filePath;
        }
    }
}