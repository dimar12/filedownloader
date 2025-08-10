package com.example.filedownloader.entity;

import com.example.filedownloader.model.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "download_chunks", indexes = {
        @Index(name = "idx_chunk_task", columnList = "task_id"),
        @Index(name = "idx_chunk_task_index", columnList = "task_id, chunk_index")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "start_byte", nullable = false)
    private Long startByte;

    @Column(name = "end_byte", nullable = false)
    private Long endByte;

    @Column(name = "bytes_downloaded", nullable = false)
    private Long bytesDownloaded;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

