package com.sky.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sky.properties.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class LocalFallbackRateLimiter {

    private final RateLimitProperties properties;
    private final Cache<String, TokenBucket> buckets;

    public LocalFallbackRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.getLocal().getCacheMaximumSize())
                .expireAfterAccess(properties.getLocal().getExpireAfterAccessMinutes(), TimeUnit.MINUTES)
                .build();
    }

    public boolean allow(RateLimitRiskLevel riskLevel, String key) {
        RateLimitProperties.TokenRule rule = toRule(riskLevel);
        TokenBucket bucket = buckets.get(riskLevel.name() + ":" + key,
                ignored -> new TokenBucket(rule.getCapacity(), rule.getRefillTokens(), rule.getRefillMillis()));
        return bucket.tryConsume();
    }

    private RateLimitProperties.TokenRule toRule(RateLimitRiskLevel riskLevel) {
        if (riskLevel == RateLimitRiskLevel.HIGH) {
            return properties.getLocal().getHigh();
        }
        return properties.getLocal().getLow();
    }

    private static class TokenBucket {
        private final long capacity;
        private final long refillTokens;
        private final long refillMillis;
        private long tokens;
        private long lastRefillMillis;

        private TokenBucket(long capacity, long refillTokens, long refillMillis) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillMillis = refillMillis;
            this.tokens = capacity;
            this.lastRefillMillis = System.currentTimeMillis();
        }

        private synchronized boolean tryConsume() {
            refill();
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillMillis;
            if (elapsed < refillMillis) {
                return;
            }
            long refillRounds = elapsed / refillMillis;
            tokens = Math.min(capacity, tokens + refillRounds * refillTokens);
            lastRefillMillis += refillRounds * refillMillis;
        }
    }
}
