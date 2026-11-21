package com.mediaflow.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AiModelGatewayValidationTest {
    private final AiModelGateway gateway = new AiModelGateway("mock", "", "", "mock", 30);

    @Test
    void acceptsWellFormedQualityOutput() {
        assertDoesNotThrow(
                () ->
                        gateway.validateOutput(
                                "quality",
                                """
                                {
                                  "score": 86,
                                  "issues": [],
                                  "dimensions": {"信息完整度": 88},
                                  "verdict": "建议进入复核"
                                }
                                """));
    }

    @Test
    void rejectsTitleOutputWithoutThreeEntries() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        gateway.validateOutput(
                                "title",
                                """
                                {"titles": ["只有一条"], "recommended": "只有一条"}
                                """));
    }
}
