package com.example.filedownloader.config;

import com.example.filedownloader.service.DownloadService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class GracefulShutdownConfig {

    private final DownloadService downloadService;

    @PreDestroy
    public void onShutdown() {
        log.info("Запуск graceful shutdown приложения");

        try {
            downloadService.shutdown();
            log.info("Graceful shutdown завершен успешно");
        } catch (Exception e) {
            log.error("Ошибка при graceful shutdown", e);
        }
    }
}