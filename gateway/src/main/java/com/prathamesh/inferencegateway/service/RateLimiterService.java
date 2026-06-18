package com.prathamesh.inferencegateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collections;

@Service
public class RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterService.class);
    private final StringRedisTemplate redisTemplate;
    private DefaultRedisScript<Long> redisScript;

    // Rate Limiting Configuration (Could be externalized to properties)
    private static final int MAX_TOKENS = 100;
    private static final int REFILL_RATE_PER_SECOND = 10;

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        // Simple Token Bucket Lua script
        String lua = "local key = KEYS[1] " +
                     "local max_tokens = tonumber(ARGV[1]) " +
                     "local refill_rate = tonumber(ARGV[2]) " +
                     "local now = tonumber(ARGV[3]) " +
                     "local tokens_key = key .. ':tokens' " +
                     "local timestamp_key = key .. ':ts' " +
                     "local current_tokens = tonumber(redis.call('get', tokens_key) or max_tokens) " +
                     "local last_refreshed = tonumber(redis.call('get', timestamp_key) or now) " +
                     "local time_passed = math.max(0, now - last_refreshed) " +
                     "local new_tokens = math.min(max_tokens, current_tokens + (time_passed * refill_rate)) " +
                     "if new_tokens >= 1 then " +
                     "  redis.call('set', tokens_key, new_tokens - 1) " +
                     "  redis.call('set', timestamp_key, now) " +
                     "  redis.call('expire', tokens_key, 60) " +
                     "  redis.call('expire', timestamp_key, 60) " +
                     "  return 1 " +
                     "else " +
                     "  redis.call('set', tokens_key, current_tokens) " + // Update tokens without deduction
                     "  redis.call('set', timestamp_key, now) " +
                     "  return 0 " +
                     "end";

        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(lua);
        redisScript.setResultType(Long.class);
    }

    public void checkRateLimit(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "anonymous"; // Fallback
        }

        String key = "ratelimit:" + clientId;
        long nowSeconds = Instant.now().getEpochSecond();

        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(MAX_TOKENS),
                String.valueOf(REFILL_RATE_PER_SECOND),
                String.valueOf(nowSeconds)
        );

        if (result != null && result == 0L) {
            logger.warn("Rate limit exceeded for client: {}", clientId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Please try again later.");
        }
    }
}
