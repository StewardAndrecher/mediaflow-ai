package com.mediaflow.workflow;

import java.util.List;

/** Domain records shared by the API, repository and workflow engine. */
public final class WorkflowModels {
    private WorkflowModels() {}

    public enum RunStatus {
        QUEUED,
        RUNNING,
        WAITING_REVIEW,
        SUCCEEDED,
        REJECTED,
        FAILED
    }

    public enum NodeStatus {
        PENDING,
        RUNNING,
        WAITING_REVIEW,
        SUCCEEDED,
        FAILED
    }

    public record NodeDefinition(
            long id,
            String nodeKey,
            String nodeType,
            String displayName,
            int order,
            int maxAttempts,
            int timeoutSeconds) {}

    public record TemplateDefinition(
            String id, String name, String description, int version, List<NodeDefinition> nodes) {}

    public record RunRecord(
            String id,
            String templateId,
            RunStatus status,
            String idempotencyKey,
            String title,
            String source,
            String content,
            String outputJson,
            String currentNode,
            String errorMessage,
            long createdAtMs,
            Long startedAtMs,
            Long finishedAtMs,
            long updatedAtMs) {}

    public record NodeRunRecord(
            String id,
            String runId,
            String nodeKey,
            String nodeType,
            String displayName,
            int order,
            NodeStatus status,
            int attempts,
            String outputJson,
            String errorMessage,
            int promptTokens,
            int completionTokens,
            Long startedAtMs,
            Long finishedAtMs,
            Long durationMs) {}

    public record RunView(
            RunRecord run,
            TemplateDefinition template,
            List<NodeRunRecord> nodeRuns,
            String modelMode) {}

    public record RunListItem(
            String id,
            String title,
            RunStatus status,
            String source,
            String currentNode,
            long createdAtMs,
            Long finishedAtMs) {}

    public record CreateRunRequest(
            String templateId, String title, String content, String source) {}

    public record ReviewRequest(boolean approved, String comment) {}

    public record MetricsSummary(
            long totalRuns,
            long succeededRuns,
            long failedRuns,
            long waitingRuns,
            long totalTokens,
            double averageDurationMs) {}

    public record StartRunResponse(String runId, RunStatus status, boolean replayed) {}

    public record RuntimeInfo(String modelMode, boolean queueEnabled, String platform) {}
}
