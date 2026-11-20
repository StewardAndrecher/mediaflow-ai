package com.mediaflow.workflow;

import com.mediaflow.workflow.WorkflowModels.CreateRunRequest;
import com.mediaflow.workflow.WorkflowModels.MetricsSummary;
import com.mediaflow.workflow.WorkflowModels.RunListItem;
import com.mediaflow.workflow.WorkflowModels.RunView;
import com.mediaflow.workflow.WorkflowModels.StartRunResponse;
import com.mediaflow.workflow.WorkflowModels.TemplateDefinition;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<TemplateDefinition> templates() {
        return service.templates();
    }

    @GetMapping("/runtime")
    public WorkflowModels.RuntimeInfo runtime() {
        return service.runtime();
    }

    @PostMapping("/runs")
    public ResponseEntity<StartRunResponse> create(
            @RequestBody(required = false) CreateRunRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request == null) throw new WorkflowService.ApiException("request body is required");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.start(request, idempotencyKey));
    }

    @GetMapping("/runs")
    public List<RunListItem> recent(@RequestParam(defaultValue = "30") int limit) {
        return service.recentRuns(limit);
    }

    @GetMapping("/runs/{runId}")
    public RunView run(@PathVariable String runId) {
        return service.view(runId);
    }

    @PostMapping("/runs/{runId}/review")
    public RunView review(
            @PathVariable String runId, @RequestBody(required = false) ReviewRequest request) {
        if (request == null || request.approved() == null) {
            throw new WorkflowService.ApiException("approved is required");
        }
        return service.review(runId, request.approved(), request.comment());
    }

    @GetMapping("/metrics")
    public MetricsSummary metrics() {
        return service.summary();
    }

    public record ReviewRequest(Boolean approved, String comment) {}
}
