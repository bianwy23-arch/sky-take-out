package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.rate-limit")
@Data
public class RateLimitProperties {

    private Redis redis = new Redis();
    private Local local = new Local();
    private Degrade degrade = new Degrade();

    @Data
    public static class Redis {
        private int maxRequests = 30;
        private long windowMillis = 60_000L;
    }

    @Data
    public static class Local {
        private long cacheMaximumSize = 100_000L;
        private long expireAfterAccessMinutes = 10L;
        private TokenRule high = new TokenRule(5, 5, 60_000L);
        private TokenRule low = new TokenRule(60, 60, 60_000L);
    }

    @Data
    public static class TokenRule {
        private long capacity;
        private long refillTokens;
        private long refillMillis;

        public TokenRule() {
        }

        public TokenRule(long capacity, long refillTokens, long refillMillis) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillMillis = refillMillis;
        }
    }

    @Data
    public static class Degrade {
        private int failureThreshold = 5;
        private int recoverySuccessThreshold = 3;
        private long durationMillis = 30_000L;
        private long probeFixedDelayMillis = 5_000L;
    }
}
