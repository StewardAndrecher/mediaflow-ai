package com.mediaflow.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.queue.enabled", havingValue = "true")
public class WorkflowRunConsumer {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRunConsumer.class);

    private final WorkflowEngine engine;

    public WorkflowRunConsumer(WorkflowEngine engine) {
        this.engine = engine;
    }

    @RabbitListener(queues = "${app.queue.name}")
    public void consume(String runId) {
        try {
            engine.execute(runId);
        } catch (RuntimeException error) {
            log.error("workflow message failed for run {}", runId, error);
        }
    }
}
