package com.example.filedownloader.repository;

import com.example.filedownloader.entity.DownloadTaskEntity;
import com.example.filedownloader.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DownloadTaskRepository extends JpaRepository<DownloadTaskEntity, String> {
    List<DownloadTaskEntity> findByStatus(TaskStatus status);
    List<DownloadTaskEntity> findAllByStatusIn(List<TaskStatus> statuses);
    Long countByStatus(TaskStatus status);
}
