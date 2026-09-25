package com.sky.ratelimit;

import com.sky.properties.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class RateLimitDegradeState {

    private final RateLimitProperties properties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveProbeSuccesses = new AtomicInteger(0);
    private final AtomicLong degradedUntilMillis = new AtomicLong(0);
    private StringRedisTemplate redisTemplate;

    public RateLimitDegradeState(RateLimitProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    public boolean isDegraded() {
        return System.currentTimeMillis() < degradedUntilMillis.get();
    }

    public void onRedisSuccess() {
        consecutiveFailures.set(0);
    }

    public void onRedisFailure(Exception exception) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getDegrade().getFailureThreshold()) {
            enterDegraded("redis-rate-limit-failure", exception);
        }
    }

    private void enterDegraded(String reason, Exception exception) {
        long until = System.currentTimeMillis() + properties.getDegrade().getDurationMillis();
        degradedUntilMillis.set(until);
        consecutiveFailures.set(0);
        consecutiveProbeSuccesses.set(0);
        log.error("[RateLimit] enter degraded mode, reason={}, until={}", reason, until, exception);
    }

    @Scheduled(fixedDelayString = "${sky.rate-limit.degrade.probe-fixed-delay-millis:5000}")
    public void probeRedis() {
        if (!isDegraded()) {
            return;
        }
        RedisConnection connection = null;
        try {
            connection = redisTemplate.getConnectionFactory().getConnection();
            String pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                consecutiveProbeSuccesses.set(0);
                return;
            }
            int successes = consecutiveProbeSuccesses.incrementAndGet();
            if (successes >= properties.getDegrade().getRecoverySuccessThreshold()) {
                degradedUntilMillis.set(0);
                consecutiveFailures.set(0);
                consecutiveProbeSuccesses.set(0);
                log.warn("[RateLimit] redis recovered, exit degraded mode");
            }
        } catch (Exception e) {
            consecutiveProbeSuccesses.set(0);
            long extendedUntil = System.currentTimeMillis() + properties.getDegrade().getDurationMillis();
            degradedUntilMillis.set(extendedUntil);
            log.warn("[RateLimit] redis probe failed, keep degraded until={}", extendedUntil, e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
