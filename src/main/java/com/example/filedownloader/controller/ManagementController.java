package com.example.filedownloader.controller;

import com.example.filedownloader.service.DownloadService;
import com.example.filedownloader.service.MetricsService;
import com.example.filedownloader.service.TaskStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/management")
@RequiredArgsConstructor
@Slf4j
public class ManagementController {

    private final DownloadService downloadService;
    private final TaskStatusService taskStatusService;
    private final MetricsService metricsService;

    @PostMapping("/pause")
    public ResponseEntity<Map<String, String>> pauseDownloads() {
        log.info("Запрос на приостановку загрузок");

        downloadService.pauseDownloads();

        Map<String, String> response = Map.of(
                "status", "success",
                "message", "Загрузки приостановлены"
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, String>> resumeDownloads() {
        log.info("Запрос на возобновление загрузок");

        downloadService.resumeDownloads();

        Map<String, String> response = Map.of(
                "status", "success",
                "message", "Загрузки возобновлены"
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, String>> shutdown() {
        log.info("Запрос на graceful shutdown");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
                downloadService.shutdown();
                log.info("Graceful shutdown завершен");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Shutdown был прерван", e);
            } catch (Exception e) {
                log.error("Ошибка при выполнении shutdown", e);
            }
        });

        Map<String, String> response = Map.of(
                "status", "success",
                "message", "Процесс остановки запущен"
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        log.debug("Запрос метрик системы");

        Map<String, Object> metrics = new HashMap<>();

        metrics.put("tasks", Map.of(
                "submitted", metricsService.getTasksSubmittedCount(),
                "started", metricsService.getTasksStartedCount(),
                "completed", metricsService.getTasksCompletedCount(),
                "failed", metricsService.getTasksFailedCount()
        ));

        metrics.put("threadPool", Map.of(
                "activeThreads", metricsService.getActiveThreadCount(),
                "poolSize", metricsService.getPoolSize(),
                "queueSize", metricsService.getQueueSize(),
                "completedTasks", metricsService.getCompletedTaskCount()
        ));

        metrics.put("taskStatistics", taskStatusService.getTaskStatistics());

        metrics.put("performance", Map.of(
                "averageDownloadSpeedMbps", metricsService.getAverageDownloadSpeed()
        ));

        return ResponseEntity.ok(metrics);
    }
}