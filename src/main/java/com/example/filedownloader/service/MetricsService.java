package com.example.filedownloader.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final ThreadPoolExecutor downloadExecutor;

    private final Counter tasksSubmittedCounter;
    private final Counter tasksStartedCounter;
    private final Counter tasksCompletedCounter;
    private final Counter tasksFailedCounter;
    private final Timer downloadTimer;
    private final AtomicLong averageDownloadSpeed = new AtomicLong(0);

    public MetricsService(MeterRegistry meterRegistry, ThreadPoolExecutor downloadExecutor) {
        this.meterRegistry = meterRegistry;
        this.downloadExecutor = downloadExecutor;

        this.tasksSubmittedCounter = Counter.builder("download.tasks.submitted")
                .description("Количество отправленных задач на загрузку")
                .register(meterRegistry);

        this.tasksStartedCounter = Counter.builder("download.tasks.started")
                .description("Количество начатых загрузок")
                .register(meterRegistry);

        this.tasksCompletedCounter = Counter.builder("download.tasks.completed")
                .description("Количество успешно завершенных загрузок")
                .register(meterRegistry);

        this.tasksFailedCounter = Counter.builder("download.tasks.failed")
                .description("Количество неудачных загрузок")
                .register(meterRegistry);

        this.downloadTimer = Timer.builder("download.duration")
                .description("Время загрузки файлов")
                .register(meterRegistry);

        meterRegistry.gauge("download.thread.pool.active",
                downloadExecutor,
                ThreadPoolExecutor::getActiveCount);

        meterRegistry.gauge("download.thread.pool.size",
                downloadExecutor,
                ThreadPoolExecutor::getPoolSize);

        meterRegistry.gauge("download.thread.pool.queue.size",
                downloadExecutor,
                executor -> executor.getQueue().size());

        meterRegistry.gauge("download.thread.pool.completed.tasks",
                downloadExecutor,
                ThreadPoolExecutor::getCompletedTaskCount);

        meterRegistry.gauge("download.speed.average.mbps",
                averageDownloadSpeed,
                AtomicLong::get);

        log.info("Метрики инициализированы");
    }

    public void incrementTasksSubmitted() {
        tasksSubmittedCounter.increment();
        log.debug("Инкремент счетчика отправленных задач");
    }

    public void incrementTasksStarted() {
        tasksStartedCounter.increment();
        log.debug("Инкремент счетчика начатых задач");
    }

    public void incrementTasksCompleted() {
        tasksCompletedCounter.increment();
        log.debug("Инкремент счетчика завершенных задач");
    }

    public void incrementTasksFailed() {
        tasksFailedCounter.increment();
        log.debug("Инкремент счетчика неудачных задач");
    }

    public void recordDownloadTime(Duration duration) {
        downloadTimer.record(duration);
        log.debug("Записано время загрузки: {} мс", duration.toMillis());
    }

    public void recordDownloadSpeed(double speedMbps) {
        long currentSpeed = averageDownloadSpeed.get();
        long newSpeed = Math.round((currentSpeed + speedMbps) / 2.0);
        averageDownloadSpeed.set(newSpeed);

        log.debug("Записана скорость загрузки: {} Mbps", String.format("%.2f", speedMbps));
    }

    public double getTasksSubmittedCount() {
        return tasksSubmittedCounter.count();
    }

    public double getTasksStartedCount() {
        return tasksStartedCounter.count();
    }

    public double getTasksCompletedCount() {
        return tasksCompletedCounter.count();
    }

    public double getTasksFailedCount() {
        return tasksFailedCounter.count();
    }

    public long getActiveThreadCount() {
        return downloadExecutor.getActiveCount();
    }

    public int getPoolSize() {
        return downloadExecutor.getPoolSize();
    }

    public int getQueueSize() {
        return downloadExecutor.getQueue().size();
    }

    public long getCompletedTaskCount() {
        return downloadExecutor.getCompletedTaskCount();
    }

    public double getAverageDownloadSpeed() {
        return averageDownloadSpeed.get();
    }
}