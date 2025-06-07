package com.example.filedownloader.dto;

import com.example.filedownloader.model.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadResponse {
    private String taskId;
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
    private String message;
}