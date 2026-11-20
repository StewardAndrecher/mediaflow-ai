package com.mediaflow.workflow;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WorkflowService.ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(WorkflowService.ApiException error) {
        HttpStatus status =
                error.getMessage() != null && error.getMessage().contains("not found")
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(WorkflowEngine.WorkflowException.class)
    ResponseEntity<Map<String, String>> handleWorkflow(WorkflowEngine.WorkflowException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
}
