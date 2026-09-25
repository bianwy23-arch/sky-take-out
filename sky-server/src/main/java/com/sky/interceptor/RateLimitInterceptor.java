package com.sky.interceptor;

import com.sky.context.BaseContext;
import com.sky.properties.RateLimitProperties;
import com.sky.ratelimit.LocalFallbackRateLimiter;
import com.sky.ratelimit.RateLimitDegradeState;
import com.sky.ratelimit.RateLimitRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.UUID;

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_PREFIX = "rate:";
    private static final String LOGIN_URI = "/user/user/login";
    private static final String COUPON_GRAB_URI_PREFIX = "/user/coupon/grab/";
    private static final String ORDER_SUBMIT_URI = "/user/order/submit";
    private static final String ORDER_PAYMENT_URI = "/user/order/payment";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LocalFallbackRateLimiter localFallbackRateLimiter;

    @Autowired
    private RateLimitDegradeState degradeState;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate_limiter.lua")));
        rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        RateLimitRiskLevel riskLevel = riskLevel(request);
        String localKey = localKey(request, riskLevel);

        if (degradeState.isDegraded()) {
            return handleFallback(request, response, riskLevel, localKey);
        }

        Long userId = BaseContext.getCurrentId();
        String key = KEY_PREFIX + redisIdentity(request, userId) + ":" + request.getRequestURI();
        long now = System.currentTimeMillis();
        long windowMillis = rateLimitProperties.getRedis().getWindowMillis();

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(now - windowMillis),
                    String.valueOf(now),
                    uniqueMember(now),
                    String.valueOf(rateLimitProperties.getRedis().getMaxRequests()),
                    String.valueOf(windowMillis)
            );

            degradeState.onRedisSuccess();
            if (result != null && result == 0L) {
                log.warn("[RateLimit] redis limited, key={}", key);
                return reject(response, "请求过于频繁，请稍后再试");
            }
        } catch (Exception e) {
            log.warn("[RateLimit] redis failed, fallback local limiter, uri={}", request.getRequestURI(), e);
            degradeState.onRedisFailure(e);
            return handleFallback(request, response, riskLevel, localKey);
        }

        return true;
    }

    private boolean handleFallback(HttpServletRequest request,
                                   HttpServletResponse response,
                                   RateLimitRiskLevel riskLevel,
                                   String localKey) throws Exception {
        if (riskLevel == RateLimitRiskLevel.CRITICAL) {
            log.warn("[RateLimit] critical api rejected in fallback, uri={}", request.getRequestURI());
            return reject(response, "活动繁忙，请稍后再试");
        }
        if (!localFallbackRateLimiter.allow(riskLevel, localKey)) {
            log.warn("[RateLimit] local fallback limited, riskLevel={}, key={}", riskLevel, localKey);
            return reject(response, "请求过于频繁，请稍后再试");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":0,\"msg\":\"" + message + "\"}");
        return false;
    }

    private RateLimitRiskLevel riskLevel(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(COUPON_GRAB_URI_PREFIX)) {
            return RateLimitRiskLevel.CRITICAL;
        }
        if (LOGIN_URI.equals(uri) || ORDER_SUBMIT_URI.equals(uri) || ORDER_PAYMENT_URI.equals(uri)) {
            return RateLimitRiskLevel.HIGH;
        }
        return RateLimitRiskLevel.LOW;
    }

    private String redisIdentity(HttpServletRequest request, Long userId) {
        if (userId != null) {
            return "user:" + userId;
        }
        String username = request.getParameter("username");
        if (username != null && !username.isEmpty()) {
            return "username:" + username + ":ip:" + clientIp(request);
        }
        return "ip:" + clientIp(request);
    }

    private String localKey(HttpServletRequest request, RateLimitRiskLevel riskLevel) {
        Long userId = BaseContext.getCurrentId();
        String uri = request.getRequestURI();
        if (riskLevel == RateLimitRiskLevel.HIGH && LOGIN_URI.equals(uri)) {
            String username = request.getParameter("username");
            return "login:" + (username == null ? "unknown" : username) + ":ip:" + clientIp(request);
        }
        if (userId != null) {
            return "user:" + userId + ":" + uri;
        }
        return "ip:" + clientIp(request) + ":" + uri;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex >= 0 ? forwardedFor.substring(0, commaIndex).trim() : forwardedFor.trim();
        }
        return request.getRemoteAddr();
    }

    private String uniqueMember(long now) {
        return now + ":" + Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }
}
