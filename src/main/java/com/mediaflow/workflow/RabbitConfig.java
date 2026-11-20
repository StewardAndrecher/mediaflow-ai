package com.mediaflow.workflow;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    Queue workflowRunQueue(@Value("${app.queue.name}") String name) {
        return QueueBuilder.durable(name).build();
    }
}
