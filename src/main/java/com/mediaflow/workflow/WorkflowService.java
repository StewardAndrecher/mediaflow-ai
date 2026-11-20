package com.mediaflow.workflow;

import com.mediaflow.workflow.WorkflowModels.CreateRunRequest;
import com.mediaflow.workflow.WorkflowModels.MetricsSummary;
import com.mediaflow.workflow.WorkflowModels.NodeRunRecord;
import com.mediaflow.workflow.WorkflowModels.NodeStatus;
import com.mediaflow.workflow.WorkflowModels.RunListItem;
import com.mediaflow.workflow.WorkflowModels.RunRecord;
import com.mediaflow.workflow.WorkflowModels.RunStatus;
import com.mediaflow.workflow.WorkflowModels.RunView;
import com.mediaflow.workflow.WorkflowModels.StartRunResponse;
import com.mediaflow.workflow.WorkflowModels.TemplateDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {
    private final WorkflowRepository repository;
    private final WorkflowEngine engine;
    private final WorkflowRunCache cache;
    private final WorkflowMetrics metrics;
    private final RunDispatcher dispatcher;
    private final AiModelGateway model;
    private final int maxContentLength;
    private final boolean queueEnabled;

    public WorkflowService(
            WorkflowRepository repository,
            WorkflowEngine engine,
            WorkflowRunCache cache,
            WorkflowMetrics metrics,
            RunDispatcher dispatcher,
            AiModelGateway model,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.workflow.max-content-length:12000}")
                    int maxContentLength,
            @org.springframework.beans.factory.annotation.Value("${app.queue.enabled:false}")
                    boolean queueEnabled) {
        this.repository = repository;
        this.engine = engine;
        this.cache = cache;
        this.metrics = metrics;
        this.dispatcher = dispatcher;
        this.model = model;
        this.maxContentLength = maxContentLength;
        this.queueEnabled = queueEnabled;
    }

    public WorkflowModels.RuntimeInfo runtime() {
        return new WorkflowModels.RuntimeInfo(
                model.modeLabel(), queueEnabled, "MediaFlow AI / Spring Boot 4");
    }

    public List<TemplateDefinition> templates() {
        return repository.findAllTemplates();
    }

    public StartRunResponse start(CreateRunRequest request, String idempotencyHeader) {
        String templateId =
                request.templateId() == null || request.templateId().isBlank()
                        ? "tpl_media_review_v1"
                        : request.templateId().trim();
        TemplateDefinition template =
                repository
                        .findTemplate(templateId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "workflow template not found: " + templateId));
        String title = request.title() == null ? "" : request.title().trim();
        String content = request.content() == null ? "" : request.content().trim();
        String source =
                request.source() == null || request.source().isBlank()
                        ? "manual"
                        : request.source().trim();
        if (title.isBlank()) throw new ApiException("title is required");
        if (title.length() > 200) throw new ApiException("title is too long");
        if (content.isBlank()) throw new ApiException("content is required");
        if (content.length() > maxContentLength) {
            throw new ApiException("content exceeds " + maxContentLength + " characters");
        }

        String idempotencyKey =
                idempotencyHeader == null || idempotencyHeader.isBlank()
                        ? UUID.randomUUID().toString()
                        : idempotencyHeader.trim();
        if (idempotencyKey.length() > 128) throw new ApiException("idempotency key is too long");
        var existing = repository.findRunByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new StartRunResponse(existing.get().id(), existing.get().status(), true);
        }

        long now = Instant.now().toEpochMilli();
        String runId = UUID.randomUUID().toString();
        RunRecord run =
                new RunRecord(
                        runId,
                        templateId,
                        RunStatus.QUEUED,
                        idempotencyKey,
                        title,
                        source,
                        content,
                        null,
                        null,
                        null,
                        now,
                        null,
                        null,
                        now);
        try {
            repository.insertRun(run);
        } catch (DuplicateKeyException race) {
            RunRecord replay =
                    repository
                            .findRunByIdempotencyKey(idempotencyKey)
                            .orElseThrow(() -> new ApiException("idempotency conflict"));
            return new StartRunResponse(replay.id(), replay.status(), true);
        }

        for (WorkflowModels.NodeDefinition node : template.nodes()) {
            repository.insertNodeRun(
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
        }
        metrics.runCreated(templateId);
        metrics.runQueued(templateId);
        dispatcher.dispatch(runId);
        RunStatus current =
                repository.findRun(runId).map(RunRecord::status).orElse(RunStatus.QUEUED);
        return new StartRunResponse(runId, current, false);
    }

    public RunView view(String runId) {
        return cache.get(runId).orElseGet(() -> loadView(runId));
    }

    public RunView review(String runId, boolean approved, String comment) {
        engine.review(runId, approved, comment);
        return loadView(runId);
    }

    public List<RunListItem> recentRuns(int limit) {
        return repository.findRecentRuns(Math.max(1, Math.min(100, limit))).stream()
                .map(
                        run ->
                                new RunListItem(
                                        run.id(),
                                        run.title(),
                                        run.status(),
                                        run.source(),
                                        run.currentNode(),
                                        run.createdAtMs(),
                                        run.finishedAtMs()))
                .toList();
    }

    public MetricsSummary summary() {
        long total = repository.countRuns();
        long succeeded = repository.countByStatus(RunStatus.SUCCEEDED);
        long failed = repository.countByStatus(RunStatus.FAILED);
        long waiting = repository.countByStatus(RunStatus.WAITING_REVIEW);
        long tokens = repository.sumTokens();
        double average = repository.averageRunDurationMs();
        return new MetricsSummary(total, succeeded, failed, waiting, tokens, average);
    }

    private RunView loadView(String runId) {
        RunRecord run =
                repository
                        .findRun(runId)
                        .orElseThrow(() -> new ApiException("workflow run not found: " + runId));
        TemplateDefinition template =
                repository.findAllTemplates().stream()
                        .filter(item -> item.id().equals(run.templateId()))
                        .findFirst()
                        .orElseThrow(() -> new ApiException("workflow template not found"));
        RunView view =
                new RunView(run, template, repository.findNodeRuns(run.id()), model.modeLabel());
        cache.put(runId, view);
        return view;
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }
    }
}
