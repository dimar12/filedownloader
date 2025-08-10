package com.example.filedownloader.entity;

import com.example.filedownloader.model.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "download_tasks", indexes = {
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_status_created", columnList = "status, created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTaskEntity {

    @Id
    @Column(name = "task_id", length = 36, nullable = false)
    private String id;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "filename", nullable = false, length = 500)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "thread_name", length = 100)
    private String threadName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "download_speed_mbps")
    private Double downloadSpeedMbps;
}
