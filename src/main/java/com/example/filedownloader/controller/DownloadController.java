package com.example.filedownloader.controller;

import com.example.filedownloader.dto.DownloadRequest;
import com.example.filedownloader.dto.DownloadResponse;
import com.example.filedownloader.model.DownloadTask;
import com.example.filedownloader.model.TaskStatus;
import com.example.filedownloader.service.TaskQueueService;
import com.example.filedownloader.service.TaskStatusService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/downloads")
@RequiredArgsConstructor
@Validated
@Slf4j
public class DownloadController {

    private final TaskQueueService taskQueueService;
    private final TaskStatusService taskStatusService;
    private final MeterRegistry meterRegistry;

    @PostMapping("/tasks")
    public ResponseEntity<DownloadResponse> createDownloadTask(@Valid @RequestBody DownloadRequest request) {
        log.info("Получен запрос на загрузку: url={}, filename={}", request.getUrl(), request.getFilename());

        try {
            DownloadTask task = DownloadTask.create(request.getUrl(), request.getFilename());

            taskStatusService.updateTaskStatus(task);

            taskQueueService.enqueueDownloadTask(task);

            DownloadResponse response = mapToResponse(task, "Задача создана и отправлена в очередь");

            log.info("Задача создана успешно: id={}", task.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Ошибка при создании задачи загрузки: url={}, error={}", request.getUrl(), e.getMessage(), e);

            DownloadResponse errorResponse = DownloadResponse.builder()
                    .message("Ошибка при создании задачи: " + e.getMessage())
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<DownloadResponse> getTaskStatus(@PathVariable String taskId) {
        log.debug("Запрос статуса задачи: id={}", taskId);

        Optional<DownloadTask> taskOpt = taskStatusService.getTask(taskId);

        if (taskOpt.isEmpty()) {
            log.warn("Задача не найдена: id={}", taskId);
            return ResponseEntity.notFound().build();
        }

        DownloadTask task = taskOpt.get();
        DownloadResponse response = mapToResponse(task, "Статус задачи получен");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<DownloadResponse>> getAllTasks(@RequestParam(required = false) TaskStatus status) {
        log.debug("Запрос списка задач: status={}", status);

        List<DownloadTask> tasks = status != null
                ? taskStatusService.getTasksByStatus(status)
                : taskStatusService.getAllTasks();

        List<DownloadResponse> responses = tasks.stream()
                .map(task -> mapToResponse(task, null))
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable String taskId) {
        log.info("Запрос на удаление задачи: id={}", taskId);

        Optional<DownloadTask> taskOpt = taskStatusService.getTask(taskId);

        if (taskOpt.isEmpty()) {
            log.warn("Задача для удаления не найдена: id={}", taskId);
            return ResponseEntity.notFound().build();
        }

        DownloadTask task = taskOpt.get();

        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            log.warn("Попытка удаления активной задачи: id={}", taskId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        taskStatusService.removeTask(taskId);
        log.info("Задача удалена: id={}", taskId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/clear-completed")
    public ResponseEntity<Void> clearCompletedTasks() {
        log.info("Запрос на очистку завершенных задач");

        taskStatusService.clearCompletedTasks();

        return ResponseEntity.ok().build();
    }

    private DownloadResponse mapToResponse(DownloadTask task, String message) {
        return DownloadResponse.builder()
                .taskId(task.getId())
                .url(task.getUrl())
                .filename(task.getFilename())
                .status(task.getStatus())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .threadName(task.getThreadName())
                .fileSizeBytes(task.getFileSizeBytes())
                .errorMessage(task.getErrorMessage())
                .retryCount(task.getRetryCount())
                .downloadSpeedMbps(task.getDownloadSpeedMbps())
                .message(message)
                .build();
    }
}