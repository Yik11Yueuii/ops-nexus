package com.opsnexus.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component("redisFallback")
public class RedisFallbackHealthIndicator implements HealthIndicator {
    private final StringRedisTemplate redis;
    public RedisFallbackHealthIndicator(StringRedisTemplate redis) { this.redis = redis; }
    @Override public Health health() {
        try { redis.getConnectionFactory().getConnection().ping(); return Health.up().withDetail("mode", "REDIS").build(); }
        catch (Exception ignored) { return Health.status("DEGRADED").withDetail("mode", "DATABASE_FALLBACK").build(); }
    }
}
