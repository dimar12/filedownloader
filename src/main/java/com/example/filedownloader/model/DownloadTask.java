package com.example.filedownloader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadTask {
    private String id;
    private String url;
    private String filename;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String threadName;
    private Long fileSizeBytes;
    private String errorMessage;
    private Integer retryCount;
    private Double downloadSpeedMbps;

    public static DownloadTask create(String url, String filename) {
        return DownloadTask.builder()
                .id(UUID.randomUUID().toString())
                .url(url)
                .filename(filename)
                .status(TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .build();
    }

    public void markAsStarted(String threadName) {
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
        this.threadName = threadName;
    }

    public void markAsCompleted(long fileSizeBytes, double downloadSpeedMbps) {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.fileSizeBytes = fileSizeBytes;
        this.downloadSpeedMbps = downloadSpeedMbps;
    }

    public void markAsFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
        this.retryCount++;
    }

    public boolean canRetry() {
        return retryCount < 3 && status == TaskStatus.FAILED;
    }
}