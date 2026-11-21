package com.mediaflow.workflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Model adapter. Mock is the default; an OpenAI-compatible endpoint can be enabled by env vars. */
@Service
public class AiModelGateway {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AiModelGateway(
            @Value("${app.ai.provider:mock}") String provider,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model}") String model,
            @Value("${app.ai.timeout-seconds:30}") int timeoutSeconds) {
        this.provider = provider == null ? "mock" : provider.trim().toLowerCase();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    public ModelResult generate(
            String operation, String title, String content, String previousSummary) {
        ModelResult result;
        if ("openai".equals(provider)) {
            result = callOpenAiCompatible(operation, title, content, previousSummary);
        } else {
            result = mock(operation, title, content, previousSummary);
        }
        validateOutput(operation, result.content());
        return result;
    }

    public String modeLabel() {
        return "mock".equals(provider) ? "mock · local demo" : "openai-compatible · " + model;
    }

    private ModelResult mock(
            String operation, String title, String content, String previousSummary) {
        String prompt = operation + "\n" + title + "\n" + content + "\n" + previousSummary;
        int promptTokens = Math.max(8, prompt.length() / 4);
        return switch (operation) {
            case "summary" -> {
                List<String> sentences = sentences(content);
                String summary = sentences.stream().limit(2).collect(Collectors.joining("。"));
                if (!summary.isEmpty() && !summary.endsWith("。")) summary += "。";
                List<String> keyPoints = sentences.stream().limit(3).toList();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("summary", summary);
                payload.put("keyPoints", keyPoints);
                payload.put("sentences", sentences.size());
                yield result(payload, promptTokens);
            }
            case "title" -> {
                String seed = title == null || title.isBlank() ? "未命名素材" : title.trim();
                List<String> titles =
                        new ArrayList<>(List.of(seed + "｜核心要点", seed + "｜深度观察", "一文读懂：" + seed));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("titles", titles);
                payload.put("recommended", titles.get(0));
                yield result(payload, promptTokens);
            }
            case "quality" -> {
                int score = 76;
                List<String> issues = new ArrayList<>();
                if (content.length() < 120) {
                    score -= 12;
                    issues.add("内容偏短，信息密度不足");
                }
                if (content.length() > 300) score += 4;
                if (content.contains("。") || content.contains("！") || content.contains("？"))
                    score += 3;
                if (content.matches("(?s).*\\d+.*")) score += 3;
                if (content.lines().count() > 3) score += 2;
                score = Math.min(96, score);
                Map<String, Object> dimensions = new LinkedHashMap<>();
                dimensions.put("信息完整度", Math.min(95, score + 2));
                dimensions.put("结构清晰度", content.lines().count() > 2 ? 88 : 72);
                dimensions.put("可读性", 84);
                dimensions.put("事实密度", content.matches("(?s).*\\d+.*") ? 86 : 68);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("score", score);
                payload.put("issues", issues);
                payload.put("dimensions", dimensions);
                payload.put("verdict", score >= 85 ? "建议进入复核" : "需要编辑补充");
                yield result(payload, promptTokens);
            }
            default ->
                    throw new IllegalArgumentException("unsupported mock operation: " + operation);
        };
    }

    private ModelResult callOpenAiCompatible(
            String operation, String title, String content, String previousSummary) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM_API_KEY is required when AI_PROVIDER=openai");
        }
        try {
            String system =
                    """
          你是媒体内容平台的 AI 助手。只输出合法 JSON，不要输出 Markdown 或额外说明。
          摘要 JSON: {"summary":"...", "keyPoints":["..."]}
          标题 JSON: {"titles":["...", "...", "..."], "recommended":"..."}
          质量评分 JSON: {"score":0-100, "issues":["..."], "dimensions":{"信息完整度":0-100}, "verdict":"..."}
          """;
            String user =
                    "任务: "
                            + operation
                            + "\n标题: "
                            + title
                            + "\n已有摘要: "
                            + previousSummary
                            + "\n正文:\n"
                            + content;
            Map<String, Object> messageSystem = Map.of("role", "system", "content", system);
            Map<String, Object> messageUser = Map.of("role", "user", "content", user);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(messageSystem, messageUser));
            payload.put("temperature", 0.2);

            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "model endpoint returned HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new IllegalStateException("model response has no choices");
            }
            String text = choices.get(0).path("message").path("content").asString();
            text = stripFences(text);
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(estimate(content));
            int completionTokens =
                    root.path("usage")
                            .path("completion_tokens")
                            .asInt(Math.max(1, text.length() / 4));
            return new ModelResult(text, promptTokens, completionTokens);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model call interrupted", error);
        } catch (Exception error) {
            throw new IllegalStateException("model call failed: " + error.getMessage(), error);
        }
    }

    private ModelResult result(Map<String, Object> payload, int promptTokens) {
        String text = JSON.writeValueAsString(payload);
        return new ModelResult(text, promptTokens, Math.max(1, text.length() / 4));
    }

    void validateOutput(String operation, String text) {
        try {
            JsonNode root = JSON.readTree(text);
            require(root != null && root.isObject(), "JSON object is required");
            switch (operation) {
                case "summary" -> {
                    requireText(root, "summary");
                    JsonNode keyPoints = root.path("keyPoints");
                    require(
                            keyPoints.isArray() && keyPoints.size() > 0,
                            "summary.keyPoints must be a non-empty array");
                }
                case "title" -> {
                    JsonNode titles = root.path("titles");
                    require(
                            titles.isArray() && titles.size() >= 3,
                            "title.titles must contain at least three entries");
                    requireText(root, "recommended");
                }
                case "quality" -> {
                    JsonNode score = root.path("score");
                    require(
                            score.isInt() && score.asInt() >= 0 && score.asInt() <= 100,
                            "quality.score must be an integer between 0 and 100");
                    require(root.path("issues").isArray(), "quality.issues must be an array");
                    require(
                            root.path("dimensions").isObject(),
                            "quality.dimensions must be an object");
                    requireText(root, "verdict");
                }
                default -> {
                    // No additional schema for non-model operations.
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "invalid " + operation + " output: " + error.getMessage(), error);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "invalid " + operation + " output: " + error.getMessage(), error);
        }
    }

    private void requireText(JsonNode root, String field) {
        require(!root.path(field).asString().isBlank(), field + " must be non-empty text");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private List<String> sentences(String content) {
        List<String> result = new ArrayList<>();
        for (String part : content.split("(?<=[。！？.!?])")) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        if (result.isEmpty() && !content.isBlank()) result.add(content.trim());
        return result;
    }

    private int estimate(String content) {
        return Math.max(8, (content == null ? 0 : content.length()) / 4);
    }

    private String stripFences(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    public record ModelResult(String content, int promptTokens, int completionTokens) {}
}
