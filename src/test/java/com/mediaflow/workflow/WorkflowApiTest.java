package com.mediaflow.workflow;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
            "app.queue.enabled=false",
            "app.cache.enabled=false",
            "spring.flyway.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:mediaflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
@AutoConfigureMockMvc
@Sql(scripts = "/test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WorkflowApiTest {
    private static final String BODY =
            """
      {
        "templateId": "tpl_media_review_v1",
        "title": "AI 平台观察",
        "content": "媒体团队正在把大模型接入内容生产。真正困难的不是调用一次模型，而是让摘要、标题、质量评分和人工复核组成可观测、可重试的工作流。本文记录一次平台化尝试，并给出运行指标。"
      }
      """;

    @Autowired MockMvc mockMvc;

    @Test
    void runsWorkflowAndWaitsForHumanReview() throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/runs")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", "test-run-001")
                                        .content(BODY))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.runId").isNotEmpty())
                        .andExpect(jsonPath("$.status").value("WAITING_REVIEW"))
                        .andReturn();

        String runId = JsonTestSupport.field(created.getResponse().getContentAsString(), "runId");

        mockMvc.perform(get("/api/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("WAITING_REVIEW"))
                .andExpect(jsonPath("$.template.nodes.length()").value(5))
                .andExpect(jsonPath("$.nodeRuns.length()").value(5))
                .andExpect(jsonPath("$.nodeRuns[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeRuns[4].status").value("WAITING_REVIEW"))
                .andExpect(jsonPath("$.modelMode").value("mock · local demo"));

        mockMvc.perform(
                        post("/api/runs/{runId}/review", runId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approved\":true,\"comment\":\"可以发布\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.run.outputJson").value(containsString("\"approved\":true")));

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(1))
                .andExpect(jsonPath("$.succeededRuns").value(1))
                .andExpect(jsonPath("$.totalTokens").isNumber());
    }

    @Test
    void rejectsRunTwiceWhenIdempotencyKeyRepeats() throws Exception {
        MvcResult first =
                mockMvc.perform(
                                post("/api/runs")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", "repeat-key")
                                        .content(BODY))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.replayed").value(false))
                        .andReturn();
        String firstId = JsonTestSupport.field(first.getResponse().getContentAsString(), "runId");

        MvcResult second =
                mockMvc.perform(
                                post("/api/runs")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", "repeat-key")
                                        .content(BODY))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.replayed").value(true))
                        .andReturn();
        String secondId = JsonTestSupport.field(second.getResponse().getContentAsString(), "runId");

        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
    }

    @Test
    void rejectsEmptyContent() throws Exception {
        mockMvc.perform(
                        post("/api/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"只有标题\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("content is required"));
    }

    @Test
    void returnsNotFoundForUnknownRun() throws Exception {
        mockMvc.perform(get("/api/runs/not-a-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("not found")));
    }

    @Test
    void exposesTemplateDefinition() throws Exception {
        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tpl_media_review_v1"))
                .andExpect(jsonPath("$[0].nodes[0].nodeType").value("VALIDATE"));
    }
}
