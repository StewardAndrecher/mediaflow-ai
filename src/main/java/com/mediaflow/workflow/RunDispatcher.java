package com.mediaflow.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final WorkflowEngine engine;
    private final RabbitTemplate rabbitTemplate;
    private final boolean queueEnabled;
    private final String queueName;

    public RunDispatcher(
            WorkflowEngine engine,
            RabbitTemplate rabbitTemplate,
            @Value("${app.queue.enabled:false}") boolean queueEnabled,
            @Value("${app.queue.name}") String queueName) {
        this.engine = engine;
        this.rabbitTemplate = rabbitTemplate;
        this.queueEnabled = queueEnabled;
        this.queueName = queueName;
    }

    public void dispatch(String runId) {
        if (!queueEnabled) {
            engine.execute(runId);
            return;
        }
        try {
            rabbitTemplate.convertAndSend(queueName, runId);
        } catch (RuntimeException brokerError) {
            log.warn(
                    "RabbitMQ unavailable, executing run synchronously: {}",
                    brokerError.getMessage());
            engine.execute(runId);
        }
    }
}
