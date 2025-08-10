package com.example.filedownloader.service;

import com.example.filedownloader.entity.DownloadTaskEntity;
import com.example.filedownloader.model.DownloadTask;
import com.example.filedownloader.model.TaskStatus;
import com.example.filedownloader.repository.DownloadTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskStatusService {

    private final DownloadTaskRepository taskRepository;

    @PostConstruct
    public void init() {
        log.info("Загрузка задач...");
        List<DownloadTaskEntity> tasks = taskRepository.findAll();
        log.info("Загружено {} задач", tasks.size());
    }

    public void updateTaskStatus(DownloadTask task) {
        DownloadTaskEntity entity = mapToEntity(task);
        taskRepository.save(entity);
        log.debug("Сохранена задача: id={}, status={}", task.getId(), task.getStatus());
    }

    public Optional<DownloadTask> getTask(String taskId) {
        return taskRepository.findById(taskId)
                .map(this::mapToDomain);
    }

    public List<DownloadTask> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(this::mapToDomain)
                .collect(Collectors.toList());
    }

    public List<DownloadTask> getTasksByStatus(TaskStatus status) {
        return taskRepository.findByStatus(status).stream()
                .map(this::mapToDomain)
                .collect(Collectors.toList());
    }

    public long getTaskCount() {
        return taskRepository.count();
    }

    public long getTaskCountByStatus(TaskStatus status) {
        return taskRepository.countByStatus(status);
    }

    public void removeTask(String taskId) {
        taskRepository.deleteById(taskId);
        log.debug("Удалена задача: id={}", taskId);
    }

    public void clearCompletedTasks() {
        List<TaskStatus> completedStatuses = Arrays.asList(TaskStatus.COMPLETED, TaskStatus.FAILED);
        List<DownloadTaskEntity> completedTasks = taskRepository.findAllByStatusIn(completedStatuses);
        int count = completedTasks.size();
        taskRepository.deleteAll(completedTasks);
        log.info("Очищено {} завершенных задач", count);
    }

    public Map<TaskStatus, Long> getTaskStatistics() {
        Map<TaskStatus, Long> statistics = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            statistics.put(status, taskRepository.countByStatus(status));
        }
        return statistics;
    }

    private DownloadTaskEntity mapToEntity(DownloadTask task) {
        return DownloadTaskEntity.builder()
                .id(task.getId())
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
                .build();
    }

    private DownloadTask mapToDomain(DownloadTaskEntity entity) {
        return DownloadTask.builder()
                .id(entity.getId())
                .url(entity.getUrl())
                .filename(entity.getFilename())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .threadName(entity.getThreadName())
                .fileSizeBytes(entity.getFileSizeBytes())
                .errorMessage(entity.getErrorMessage())
                .retryCount(entity.getRetryCount())
                .downloadSpeedMbps(entity.getDownloadSpeedMbps())
                .build();
    }
}