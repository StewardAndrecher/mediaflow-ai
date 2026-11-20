package com.mediaflow.workflow;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
            "app.queue.enabled=false",
            "app.cache.enabled=false",
            "spring.flyway.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:mediaflow-retry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
@AutoConfigureMockMvc
@Sql(scripts = "/test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WorkflowRetryTest {
    @Autowired MockMvc mockMvc;

    @MockitoBean AiModelGateway model;

    @Test
    void retriesATransientModelFailure() throws Exception {
        when(model.modeLabel()).thenReturn("mock · retry test");
        when(model.generate(eq("summary"), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("upstream 503"))
                .thenReturn(
                        new AiModelGateway.ModelResult(
                                "{\"summary\":\"重试成功\",\"keyPoints\":[]}", 40, 10));
        when(model.generate(eq("title"), anyString(), anyString(), anyString()))
                .thenReturn(
                        new AiModelGateway.ModelResult(
                                "{\"titles\":[\"标题\"],\"recommended\":\"标题\"}", 30, 8));
        when(model.generate(eq("quality"), anyString(), anyString(), anyString()))
                .thenReturn(
                        new AiModelGateway.ModelResult(
                                "{\"score\":88,\"issues\":[],\"dimensions\":{}}", 30, 8));

        MvcResult created =
                mockMvc.perform(
                                post("/api/runs")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", "retry-run")
                                        .content(
                                                "{\"title\":\"重试测试\",\"content\":\"一段用于验证节点重试策略的媒体素材。\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("WAITING_REVIEW"))
                        .andReturn();

        String runId = JsonTestSupport.field(created.getResponse().getContentAsString(), "runId");
        mockMvc.perform(get("/api/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeRuns[1].nodeKey").value("summary"))
                .andExpect(jsonPath("$.nodeRuns[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeRuns[1].attempts").value(2));
    }
}
