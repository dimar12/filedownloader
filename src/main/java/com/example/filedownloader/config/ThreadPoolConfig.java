package com.example.filedownloader.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class ThreadPoolConfig {

    @Value("${file-downloader.thread-pool.core-size:5}")
    private int corePoolSize;

    @Value("${file-downloader.thread-pool.max-size:20}")
    private int maxPoolSize;

    @Value("${file-downloader.thread-pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${file-downloader.thread-pool.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Bean(name = "downloadExecutor")
    public ThreadPoolExecutor downloadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadFactory() {
                    private int counter = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "download-thread-" + (++counter));
                        thread.setDaemon(false);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        executor.allowCoreThreadTimeOut(true);

        log.info("Создан ThreadPoolExecutor: core={}, max={}, queue={}, keepAlive={}s",
                corePoolSize, maxPoolSize, queueCapacity, keepAliveSeconds);

        return executor;
    }

    @Bean(name = "forkJoinPool")
    public ForkJoinPool forkJoinPool() {
        int parallelism = Runtime.getRuntime().availableProcessors();
        ForkJoinPool pool = new ForkJoinPool(parallelism);

        log.info("Создан ForkJoinPool с parallelism={}", parallelism);

        return pool;
    }
}