package com.mediaflow.workflow;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkflowRunCache {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRunCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    public WorkflowRunCache(
            StringRedisTemplate redis,
            @Value("${app.cache.enabled:true}") boolean enabled,
            @Value("${app.cache.run-ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    public Optional<WorkflowModels.RunView> get(String runId) {
        if (!enabled) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key(runId));
            return json == null
                    ? Optional.empty()
                    : Optional.of(JSON.readValue(json, WorkflowModels.RunView.class));
        } catch (RuntimeException error) {
            log.debug("Redis read unavailable, falling back to MySQL: {}", error.getMessage());
            return Optional.empty();
        }
    }

    public void put(String runId, WorkflowModels.RunView view) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(runId), JSON.writeValueAsString(view), ttl);
        } catch (RuntimeException error) {
            log.debug("Redis write unavailable: {}", error.getMessage());
        }
    }

    public void evict(String runId) {
        if (!enabled) return;
        try {
            redis.delete(key(runId));
        } catch (RuntimeException error) {
            log.debug("Redis eviction unavailable: {}", error.getMessage());
        }
    }

    private String key(String runId) {
        return "mediaflow:run:" + runId;
    }
}
