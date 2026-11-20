package com.mediaflow.workflow;

import com.mediaflow.workflow.WorkflowModels.NodeDefinition;
import com.mediaflow.workflow.WorkflowModels.NodeRunRecord;
import com.mediaflow.workflow.WorkflowModels.NodeStatus;
import com.mediaflow.workflow.WorkflowModels.RunRecord;
import com.mediaflow.workflow.WorkflowModels.RunStatus;
import com.mediaflow.workflow.WorkflowModels.TemplateDefinition;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Executes a persisted, definition-driven workflow. */
@Service
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowRepository repository;
    private final AiModelGateway model;
    private final WorkflowMetrics metrics;
    private final WorkflowRunCache cache;
    private final int maxContentLength;
    private final ExecutorService nodeExecutor = Executors.newFixedThreadPool(4);

    public WorkflowEngine(
            WorkflowRepository repository,
            AiModelGateway model,
            WorkflowMetrics metrics,
            WorkflowRunCache cache,
            @Value("${app.workflow.max-content-length:12000}") int maxContentLength) {
        this.repository = repository;
        this.model = model;
        this.metrics = metrics;
        this.cache = cache;
        this.maxContentLength = maxContentLength;
    }

    public void execute(String runId) {
        Optional<RunRecord> existing = repository.findRun(runId);
        if (existing.isEmpty()) {
            log.warn("workflow run not found: {}", runId);
            return;
        }
        RunRecord run = existing.get();
        if (run.status() == RunStatus.WAITING_REVIEW
                || run.status() == RunStatus.SUCCEEDED
                || run.status() == RunStatus.REJECTED
                || run.status() == RunStatus.FAILED) {
            return;
        }
        Optional<TemplateDefinition> template = repository.findTemplate(run.templateId());
        if (template.isEmpty()) {
            fail(run, null, "workflow template not found: " + run.templateId());
            return;
        }
        TemplateDefinition definition = template.get();
        long startedAt = run.startedAtMs() == null ? now() : run.startedAtMs();
        if (run.status() == RunStatus.QUEUED) {
            metrics.runStarted(definition.id());
        }
        run =
                new RunRecord(
                        run.id(),
                        run.templateId(),
                        RunStatus.RUNNING,
                        run.idempotencyKey(),
                        run.title(),
                        run.source(),
                        run.content(),
                        run.outputJson(),
                        run.currentNode(),
                        null,
                        run.createdAtMs(),
                        startedAt,
                        null,
                        now());
        repository.saveRun(run);
        cache.evict(run.id());

        Map<String, String> nodeOutputs = new LinkedHashMap<>();
        for (NodeRunRecord completed : repository.findNodeRuns(run.id())) {
            if (completed.status() == NodeStatus.SUCCEEDED && completed.outputJson() != null) {
                nodeOutputs.put(completed.nodeKey(), completed.outputJson());
            }
        }

        for (NodeDefinition node : definition.nodes()) {
            run = repository.findRun(run.id()).orElseThrow();
            Optional<NodeRunRecord> already = repository.findNodeRun(run.id(), node.nodeKey());
            if (already.isPresent()) {
                NodeRunRecord record = already.get();
                if (record.status() == NodeStatus.SUCCEEDED) continue;
                if (record.status() == NodeStatus.WAITING_REVIEW) return;
            }

            NodeRunRecord nodeRun =
                    already.orElseGet(
                            () ->
                                    new NodeRunRecord(
                                            UUID.randomUUID().toString(),
                                            runId,
                                            node.nodeKey(),
                                            node.nodeType(),
                                            node.displayName(),
                                            node.order(),
                                            NodeStatus.PENDING,
                                            0,
                                            null,
                                            null,
                                            0,
                                            0,
                                            null,
                                            null,
                                            null));
            if (already.isEmpty()) repository.insertNodeRun(nodeRun);

            long nodeStart = now();
            int attempts = nodeRun.attempts();
            NodeResult result = null;
            RuntimeException lastError = null;
            for (int attempt = 0; attempt < Math.max(1, node.maxAttempts()); attempt++) {
                attempts++;
                nodeRun = transitionNode(nodeRun, NodeStatus.RUNNING, attempts, nodeStart, null);
                repository.saveNodeRun(nodeRun);
                try {
                    result = runWithTimeout(node, run, nodeOutputs);
                    break;
                } catch (RuntimeException error) {
                    lastError = error;
                    if (error instanceof NonRetryableNodeException
                            || attempt + 1 >= node.maxAttempts()) {
                        break;
                    }
                    sleep(250L * (attempt + 1));
                }
            }

            long duration = now() - nodeStart;
            if (result == null) {
                String reason =
                        lastError == null ? "node execution failed" : lastError.getMessage();
                nodeRun =
                        new NodeRunRecord(
                                nodeRun.id(),
                                nodeRun.runId(),
                                nodeRun.nodeKey(),
                                nodeRun.nodeType(),
                                nodeRun.displayName(),
                                nodeRun.order(),
                                NodeStatus.FAILED,
                                attempts,
                                null,
                                reason,
                                0,
                                0,
                                nodeStart,
                                now(),
                                duration);
                repository.saveNodeRun(nodeRun);
                metrics.nodeFinished(
                        definition.id(), node.nodeKey(), NodeStatus.FAILED, duration, 0, 0);
                metrics.nodeFailed(definition.id(), node.nodeKey(), "execution_error");
                fail(run, node.nodeKey(), reason);
                return;
            }

            if (result.requiresReview()) {
                nodeRun =
                        new NodeRunRecord(
                                nodeRun.id(),
                                nodeRun.runId(),
                                nodeRun.nodeKey(),
                                nodeRun.nodeType(),
                                nodeRun.displayName(),
                                nodeRun.order(),
                                NodeStatus.WAITING_REVIEW,
                                attempts,
                                result.outputJson(),
                                null,
                                0,
                                0,
                                nodeStart,
                                null,
                                duration);
                repository.saveNodeRun(nodeRun);
                metrics.nodeFinished(
                        definition.id(), node.nodeKey(), NodeStatus.WAITING_REVIEW, duration, 0, 0);
                run =
                        new RunRecord(
                                run.id(),
                                run.templateId(),
                                RunStatus.WAITING_REVIEW,
                                run.idempotencyKey(),
                                run.title(),
                                run.source(),
                                run.content(),
                                run.outputJson(),
                                node.nodeKey(),
                                null,
                                run.createdAtMs(),
                                startedAt,
                                null,
                                now());
                repository.saveRun(run);
                cache.evict(run.id());
                return;
            }

            nodeOutputs.put(node.nodeKey(), result.outputJson());
            nodeRun =
                    new NodeRunRecord(
                            nodeRun.id(),
                            nodeRun.runId(),
                            nodeRun.nodeKey(),
                            nodeRun.nodeType(),
                            nodeRun.displayName(),
                            nodeRun.order(),
                            NodeStatus.SUCCEEDED,
                            attempts,
                            result.outputJson(),
                            null,
                            result.promptTokens(),
                            result.completionTokens(),
                            nodeStart,
                            now(),
                            duration);
            repository.saveNodeRun(nodeRun);
            metrics.nodeFinished(
                    definition.id(),
                    node.nodeKey(),
                    NodeStatus.SUCCEEDED,
                    duration,
                    result.promptTokens(),
                    result.completionTokens());
        }

        complete(run, definition, nodeOutputs, RunStatus.SUCCEEDED, null);
    }

    public void review(String runId, boolean approved, String comment) {
        RunRecord run =
                repository
                        .findRun(runId)
                        .orElseThrow(() -> new WorkflowException("run not found: " + runId));
        if (run.status() != RunStatus.WAITING_REVIEW) {
            throw new WorkflowException("run is not waiting for review");
        }
        TemplateDefinition definition =
                repository
                        .findTemplate(run.templateId())
                        .orElseThrow(() -> new WorkflowException("workflow template not found"));
        NodeRunRecord reviewNode =
                repository
                        .findNodeRun(runId, "review")
                        .orElseThrow(() -> new WorkflowException("review node not found"));
        String output =
                JSON.writeValueAsString(
                        Map.of(
                                "approved",
                                approved,
                                "comment",
                                comment == null ? "" : comment,
                                "reviewedAtMs",
                                now()));
        NodeStatus nodeStatus = approved ? NodeStatus.SUCCEEDED : NodeStatus.FAILED;
        reviewNode =
                new NodeRunRecord(
                        reviewNode.id(),
                        reviewNode.runId(),
                        reviewNode.nodeKey(),
                        reviewNode.nodeType(),
                        reviewNode.displayName(),
                        reviewNode.order(),
                        nodeStatus,
                        reviewNode.attempts(),
                        output,
                        approved ? null : "rejected by reviewer",
                        0,
                        0,
                        reviewNode.startedAtMs(),
                        now(),
                        reviewNode.durationMs());
        repository.saveNodeRun(reviewNode);
        Map<String, String> nodeOutputs = new LinkedHashMap<>();
        for (NodeRunRecord node : repository.findNodeRuns(runId)) {
            if (node.outputJson() != null) nodeOutputs.put(node.nodeKey(), node.outputJson());
        }
        nodeOutputs.put("review", output);
        if (approved) {
            complete(run, definition, nodeOutputs, RunStatus.SUCCEEDED, null);
        } else {
            complete(run, definition, nodeOutputs, RunStatus.REJECTED, "rejected by reviewer");
        }
    }

    private NodeResult runWithTimeout(
            NodeDefinition node, RunRecord run, Map<String, String> nodeOutputs) {
        Future<NodeResult> future = nodeExecutor.submit(() -> executeNode(node, run, nodeOutputs));
        try {
            return future.get(Math.max(1, node.timeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new RetryableNodeException("node timed out after " + node.timeoutSeconds() + "s");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RetryableNodeException("node interrupted");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RetryableNodeException(cause == null ? "node failed" : cause.getMessage());
        }
    }

    private NodeResult executeNode(
            NodeDefinition node, RunRecord run, Map<String, String> nodeOutputs) {
        return switch (node.nodeType()) {
            case "VALIDATE" -> validate(run);
            case "SUMMARIZE" -> summarize(run);
            case "TITLE" -> titles(run, nodeOutputs);
            case "QUALITY" -> quality(run, nodeOutputs);
            case "HUMAN_REVIEW" ->
                    new NodeResult(
                            JSON.writeValueAsString(
                                    Map.of(
                                            "message",
                                            "AI 结果已生成，等待编辑确认",
                                            "checklist",
                                            List.of("摘要是否准确", "标题是否符合品牌语气", "评分是否合理"))),
                            0,
                            0,
                            true);
            default ->
                    throw new NonRetryableNodeException(
                            "unsupported node type: " + node.nodeType());
        };
    }

    private NodeResult validate(RunRecord run) {
        if (run.content() == null || run.content().isBlank()) {
            throw new NonRetryableNodeException("content is required");
        }
        if (run.content().length() > maxContentLength) {
            throw new NonRetryableNodeException(
                    "content exceeds " + maxContentLength + " characters");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", run.title());
        output.put("characters", run.content().length());
        output.put("paragraphs", run.content().lines().filter(line -> !line.isBlank()).count());
        output.put("status", "valid");
        return new NodeResult(JSON.writeValueAsString(output), 0, 0, false);
    }

    private NodeResult summarize(RunRecord run) {
        AiModelGateway.ModelResult result =
                model.generate("summary", run.title(), run.content(), "");
        return new NodeResult(
                result.content(), result.promptTokens(), result.completionTokens(), false);
    }

    private NodeResult titles(RunRecord run, Map<String, String> outputs) {
        AiModelGateway.ModelResult result =
                model.generate("title", run.title(), run.content(), extractSummary(outputs));
        return new NodeResult(
                result.content(), result.promptTokens(), result.completionTokens(), false);
    }

    private NodeResult quality(RunRecord run, Map<String, String> outputs) {
        AiModelGateway.ModelResult result =
                model.generate("quality", run.title(), run.content(), extractSummary(outputs));
        return new NodeResult(
                result.content(), result.promptTokens(), result.completionTokens(), false);
    }

    private String extractSummary(Map<String, String> outputs) {
        String raw = outputs.get("summary");
        if (raw == null) return "";
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.path("summary").isString()) return node.path("summary").asString();
        } catch (RuntimeException ignored) {
            // The model may return a raw string; pass it through.
        }
        return raw;
    }

    private NodeRunRecord transitionNode(
            NodeRunRecord node, NodeStatus status, int attempts, long startedAt, String error) {
        return new NodeRunRecord(
                node.id(),
                node.runId(),
                node.nodeKey(),
                node.nodeType(),
                node.displayName(),
                node.order(),
                status,
                attempts,
                node.outputJson(),
                error,
                node.promptTokens(),
                node.completionTokens(),
                startedAt,
                node.finishedAtMs(),
                node.durationMs());
    }

    private void fail(RunRecord run, String currentNode, String reason) {
        long finished = now();
        RunRecord failed =
                new RunRecord(
                        run.id(),
                        run.templateId(),
                        RunStatus.FAILED,
                        run.idempotencyKey(),
                        run.title(),
                        run.source(),
                        run.content(),
                        run.outputJson(),
                        currentNode,
                        reason,
                        run.createdAtMs(),
                        run.startedAtMs(),
                        finished,
                        finished);
        repository.saveRun(failed);
        cache.evict(run.id());
        metrics.runFinished(
                run.templateId(),
                RunStatus.FAILED,
                run.startedAtMs() == null ? 0 : finished - run.startedAtMs());
        log.warn("workflow run {} failed at {}: {}", run.id(), currentNode, reason);
    }

    private void complete(
            RunRecord run,
            TemplateDefinition definition,
            Map<String, String> nodeOutputs,
            RunStatus status,
            String error) {
        Map<String, JsonNode> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : nodeOutputs.entrySet()) {
            try {
                parsed.put(entry.getKey(), JSON.readTree(entry.getValue()));
            } catch (RuntimeException ignored) {
                parsed.put(entry.getKey(), JSON.getNodeFactory().textNode(entry.getValue()));
            }
        }
        String output = JSON.writeValueAsString(Map.of("nodes", parsed));
        long finished = now();
        RunRecord finishedRun =
                new RunRecord(
                        run.id(),
                        run.templateId(),
                        status,
                        run.idempotencyKey(),
                        run.title(),
                        run.source(),
                        run.content(),
                        output,
                        "review",
                        error,
                        run.createdAtMs(),
                        run.startedAtMs(),
                        finished,
                        finished);
        repository.saveRun(finishedRun);
        cache.evict(run.id());
        metrics.runFinished(
                definition.id(),
                status,
                run.startedAtMs() == null ? 0 : finished - run.startedAtMs());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private long now() {
        return Instant.now().toEpochMilli();
    }

    @PreDestroy
    void shutdown() {
        nodeExecutor.shutdownNow();
    }

    private record NodeResult(
            String outputJson, int promptTokens, int completionTokens, boolean requiresReview) {}

    private static class RetryableNodeException extends RuntimeException {
        RetryableNodeException(String message) {
            super(message);
        }
    }

    private static class NonRetryableNodeException extends RuntimeException {
        NonRetryableNodeException(String message) {
            super(message);
        }
    }

    public static class WorkflowException extends RuntimeException {
        public WorkflowException(String message) {
            super(message);
        }
    }
}
