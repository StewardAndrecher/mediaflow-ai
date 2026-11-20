package com.mediaflow.workflow;

import com.mediaflow.workflow.WorkflowModels.NodeDefinition;
import com.mediaflow.workflow.WorkflowModels.NodeRunRecord;
import com.mediaflow.workflow.WorkflowModels.NodeStatus;
import com.mediaflow.workflow.WorkflowModels.RunRecord;
import com.mediaflow.workflow.WorkflowModels.RunStatus;
import com.mediaflow.workflow.WorkflowModels.TemplateDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowRepository {
    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TemplateDefinition> findAllTemplates() {
        return jdbc.query(
                "SELECT id, name, description, version FROM workflow_templates ORDER BY id",
                (rs, rowNum) ->
                        new TemplateDefinition(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("description"),
                                rs.getInt("version"),
                                findNodes(rs.getString("id"))));
    }

    public Optional<TemplateDefinition> findTemplate(String id) {
        List<TemplateDefinition> rows =
                jdbc.query(
                        "SELECT id, name, description, version FROM workflow_templates WHERE id = ?",
                        (rs, rowNum) ->
                                new TemplateDefinition(
                                        rs.getString("id"),
                                        rs.getString("name"),
                                        rs.getString("description"),
                                        rs.getInt("version"),
                                        findNodes(rs.getString("id"))),
                        id);
        return rows.stream().findFirst();
    }

    public List<NodeDefinition> findNodes(String templateId) {
        return jdbc.query(
                """
        SELECT id, node_key, node_type, display_name, node_order, max_attempts, timeout_seconds
        FROM workflow_template_nodes
        WHERE template_id = ?
        ORDER BY node_order
        """,
                nodeMapper(),
                templateId);
    }

    public void insertRun(RunRecord run) {
        jdbc.update(
                """
        INSERT INTO workflow_runs
          (id, template_id, status, idempotency_key, title, source, content, output_json,
           current_node, error_message, created_at_ms, started_at_ms, finished_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                run.id(),
                run.templateId(),
                run.status().name(),
                run.idempotencyKey(),
                run.title(),
                run.source(),
                run.content(),
                run.outputJson(),
                run.currentNode(),
                run.errorMessage(),
                run.createdAtMs(),
                run.startedAtMs(),
                run.finishedAtMs(),
                run.updatedAtMs());
    }

    public void saveRun(RunRecord run) {
        jdbc.update(
                """
        UPDATE workflow_runs
        SET status = ?, output_json = ?, current_node = ?, error_message = ?,
            started_at_ms = ?, finished_at_ms = ?, updated_at_ms = ?
        WHERE id = ?
        """,
                run.status().name(),
                run.outputJson(),
                run.currentNode(),
                run.errorMessage(),
                run.startedAtMs(),
                run.finishedAtMs(),
                run.updatedAtMs(),
                run.id());
    }

    public Optional<RunRecord> findRun(String id) {
        List<RunRecord> rows =
                jdbc.query("SELECT * FROM workflow_runs WHERE id = ?", runMapper(), id);
        return rows.stream().findFirst();
    }

    public Optional<RunRecord> findRunByIdempotencyKey(String idempotencyKey) {
        List<RunRecord> rows =
                jdbc.query(
                        "SELECT * FROM workflow_runs WHERE idempotency_key = ?",
                        runMapper(),
                        idempotencyKey);
        return rows.stream().findFirst();
    }

    public List<RunRecord> findRecentRuns(int limit) {
        return jdbc.query(
                "SELECT * FROM workflow_runs ORDER BY created_at_ms DESC LIMIT ?",
                runMapper(),
                limit);
    }

    public Optional<NodeRunRecord> findNodeRun(String runId, String nodeKey) {
        List<NodeRunRecord> rows =
                jdbc.query(
                        "SELECT * FROM workflow_node_runs WHERE run_id = ? AND node_key = ?",
                        nodeRunMapper(),
                        runId,
                        nodeKey);
        return rows.stream().findFirst();
    }

    public List<NodeRunRecord> findNodeRuns(String runId) {
        return jdbc.query(
                "SELECT * FROM workflow_node_runs WHERE run_id = ? ORDER BY node_order",
                nodeRunMapper(),
                runId);
    }

    public void insertNodeRun(NodeRunRecord node) {
        jdbc.update(
                """
        INSERT INTO workflow_node_runs
          (id, run_id, node_key, node_type, display_name, node_order, status, attempts,
           output_json, error_message, prompt_tokens, completion_tokens,
           started_at_ms, finished_at_ms, duration_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                node.id(),
                node.runId(),
                node.nodeKey(),
                node.nodeType(),
                node.displayName(),
                node.order(),
                node.status().name(),
                node.attempts(),
                node.outputJson(),
                node.errorMessage(),
                node.promptTokens(),
                node.completionTokens(),
                node.startedAtMs(),
                node.finishedAtMs(),
                node.durationMs());
    }

    public void saveNodeRun(NodeRunRecord node) {
        jdbc.update(
                """
        UPDATE workflow_node_runs
        SET status = ?, attempts = ?, output_json = ?, error_message = ?,
            prompt_tokens = ?, completion_tokens = ?, started_at_ms = ?, finished_at_ms = ?,
            duration_ms = ?
        WHERE id = ?
        """,
                node.status().name(),
                node.attempts(),
                node.outputJson(),
                node.errorMessage(),
                node.promptTokens(),
                node.completionTokens(),
                node.startedAtMs(),
                node.finishedAtMs(),
                node.durationMs(),
                node.id());
    }

    public long countRuns() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Long.class);
        return count == null ? 0 : count;
    }

    public long countByStatus(RunStatus status) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM workflow_runs WHERE status = ?",
                        Long.class,
                        status.name());
        return count == null ? 0 : count;
    }

    public long sumTokens() {
        Long total =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM workflow_node_runs",
                        Long.class);
        return total == null ? 0 : total;
    }

    public double averageRunDurationMs() {
        Double average =
                jdbc.queryForObject(
                        """
            SELECT COALESCE(AVG(finished_at_ms - started_at_ms), 0)
            FROM workflow_runs
            WHERE status IN ('SUCCEEDED', 'REJECTED') AND started_at_ms IS NOT NULL
              AND finished_at_ms IS NOT NULL
            """,
                        Double.class);
        return average == null ? 0 : average;
    }

    private RowMapper<RunRecord> runMapper() {
        return (rs, rowNum) ->
                new RunRecord(
                        rs.getString("id"),
                        rs.getString("template_id"),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getString("idempotency_key"),
                        rs.getString("title"),
                        rs.getString("source"),
                        rs.getString("content"),
                        rs.getString("output_json"),
                        rs.getString("current_node"),
                        rs.getString("error_message"),
                        rs.getLong("created_at_ms"),
                        nullableLong(rs, "started_at_ms"),
                        nullableLong(rs, "finished_at_ms"),
                        rs.getLong("updated_at_ms"));
    }

    private RowMapper<NodeDefinition> nodeMapper() {
        return (rs, rowNum) ->
                new NodeDefinition(
                        rs.getLong("id"),
                        rs.getString("node_key"),
                        rs.getString("node_type"),
                        rs.getString("display_name"),
                        rs.getInt("node_order"),
                        rs.getInt("max_attempts"),
                        rs.getInt("timeout_seconds"));
    }

    private RowMapper<NodeRunRecord> nodeRunMapper() {
        return (rs, rowNum) ->
                new NodeRunRecord(
                        rs.getString("id"),
                        rs.getString("run_id"),
                        rs.getString("node_key"),
                        rs.getString("node_type"),
                        rs.getString("display_name"),
                        rs.getInt("node_order"),
                        NodeStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempts"),
                        rs.getString("output_json"),
                        rs.getString("error_message"),
                        rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"),
                        nullableLong(rs, "started_at_ms"),
                        nullableLong(rs, "finished_at_ms"),
                        nullableLong(rs, "duration_ms"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
