package com.example.filedownloader.service;

import com.example.filedownloader.config.RabbitMQConfig;
import com.example.filedownloader.model.DownloadTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskQueueService {

    private final RabbitTemplate rabbitTemplate;

    public void enqueueDownloadTask(DownloadTask task) {
        try {
            log.info("Отправка задачи в очередь: id={}, url={}, filename={}", task.getId(), task.getUrl(), task.getFilename());

            rabbitTemplate.convertAndSend(RabbitMQConfig.DOWNLOAD_EXCHANGE, RabbitMQConfig.DOWNLOAD_ROUTING_KEY, task);

            log.debug("Задача успешно отправлена в очередь: id={}", task.getId());
        } catch (Exception e) {
            log.error("Ошибка при отправке задачи в очередь: id={}, error={}", task.getId(), e.getMessage(), e);
            throw new RuntimeException("Не удалось отправить задачу в очередь", e);
        }
    }
}