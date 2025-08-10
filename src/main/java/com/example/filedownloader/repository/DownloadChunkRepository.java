package com.example.filedownloader.repository;

import com.example.filedownloader.entity.DownloadChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DownloadChunkRepository extends JpaRepository<DownloadChunkEntity, Long> {
    List<DownloadChunkEntity> findByTaskIdOrderByChunkIndex(String taskId);
    void deleteByTaskId(String taskId);
}
