package com.example.filedownloader.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String DOWNLOAD_QUEUE = "download.queue";
    public static final String DOWNLOAD_EXCHANGE = "download.exchange";
    public static final String DOWNLOAD_ROUTING_KEY = "download.task";

    @Bean
    public Queue downloadQueue() {
        return QueueBuilder.durable(DOWNLOAD_QUEUE)
                .withArgument("x-message-ttl", 3600000) // 1 час
                .build();
    }

    @Bean
    public DirectExchange downloadExchange() {
        return new DirectExchange(DOWNLOAD_EXCHANGE);
    }

    @Bean
    public Binding downloadBinding() {
        return BindingBuilder
                .bind(downloadQueue())
                .to(downloadExchange())
                .with(DOWNLOAD_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(1);
        return factory;
    }
}