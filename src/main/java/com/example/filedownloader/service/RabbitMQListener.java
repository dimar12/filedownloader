package com.example.filedownloader.service;

import com.example.filedownloader.config.RabbitMQConfig;
import com.example.filedownloader.model.DownloadTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RabbitMQListener {

    private final DownloadService downloadService;

    @RabbitListener(queues = RabbitMQConfig.DOWNLOAD_QUEUE)
    public void handleDownloadTask(DownloadTask task) {
        MDC.put("taskId", task.getId());

        try {
            log.info("Получена задача из очереди: id={}, url={}, filename={}", task.getId(), task.getUrl(), task.getFilename());
            downloadService.submitDownloadTask(task);
        } catch (Exception e) {
            log.error("Ошибка при обработке задачи из очереди: id={}, error={}", task.getId(), e.getMessage(), e);
            throw e;
        } finally {
            MDC.remove("taskId");
        }
    }
}