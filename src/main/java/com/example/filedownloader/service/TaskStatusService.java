package com.example.filedownloader.service;

import com.example.filedownloader.model.DownloadTask;
import com.example.filedownloader.model.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TaskStatusService {

    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();

    public void updateTaskStatus(DownloadTask task) {
        tasks.put(task.getId(), task);
        log.debug("Обновлен статус задачи: id={}, status={}", task.getId(), task.getStatus());
    }

    public Optional<DownloadTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public List<DownloadTask> getTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    public long getTaskCount() {
        return tasks.size();
    }

    public long getTaskCountByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .count();
    }

    public void removeTask(String taskId) {
        DownloadTask removed = tasks.remove(taskId);
        if (removed != null) {
            log.debug("Удалена задача: id={}", taskId);
        }
    }

    public void clearCompletedTasks() {
        List<String> completedTaskIds = tasks.values().stream()
                .filter(task -> task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED)
                .map(DownloadTask::getId)
                .toList();

        completedTaskIds.forEach(tasks::remove);
        log.info("Очищено {} завершенных задач", completedTaskIds.size());
    }

    public Map<TaskStatus, Long> getTaskStatistics() {
        Map<TaskStatus, Long> statistics = new EnumMap<>(TaskStatus.class);

        for (TaskStatus status : TaskStatus.values()) {
            statistics.put(status, getTaskCountByStatus(status));
        }

        return statistics;
    }
}