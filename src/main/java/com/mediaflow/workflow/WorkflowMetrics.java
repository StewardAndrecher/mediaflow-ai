package com.mediaflow.workflow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class WorkflowMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("ai_workflow_active_runs", activeRuns);
    }

    public void runQueued(String templateId) {
        counter("ai_workflow_runs_total", "template", templateId, "status", "queued").increment();
    }

    public void runStarted(String templateId) {
        activeRuns.incrementAndGet();
        counter("ai_workflow_runs_total", "template", templateId, "status", "running").increment();
    }

    public void runFinished(String templateId, WorkflowModels.RunStatus status, long durationMs) {
        activeRuns.updateAndGet(value -> Math.max(0, value - 1));
        counter(
                        "ai_workflow_runs_total",
                        "template",
                        templateId,
                        "status",
                        status.name().toLowerCase())
                .increment();
        Timer.builder("ai_workflow_run_duration_seconds")
                .description("End-to-end workflow run duration")
                .tag("template", templateId)
                .tag("status", status.name().toLowerCase())
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void nodeFinished(
            String templateId,
            String nodeKey,
            WorkflowModels.NodeStatus status,
            long durationMs,
            int promptTokens,
            int completionTokens) {
        counter(
                        "ai_workflow_nodes_total",
                        "template",
                        templateId,
                        "node",
                        nodeKey,
                        "status",
                        status.name().toLowerCase())
                .increment();
        Timer.builder("ai_workflow_node_duration_seconds")
                .description("Per-node execution duration")
                .tag("template", templateId)
                .tag("node", nodeKey)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
        if (promptTokens > 0) {
            counter("ai_workflow_tokens_total", "type", "prompt", "node", nodeKey)
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            counter("ai_workflow_tokens_total", "type", "completion", "node", nodeKey)
                    .increment(completionTokens);
        }
    }

    public void nodeFailed(String templateId, String nodeKey, String reason) {
        counter(
                        "ai_workflow_node_failures_total",
                        "template",
                        templateId,
                        "node",
                        nodeKey,
                        "reason",
                        reason == null ? "unknown" : reason)
                .increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
