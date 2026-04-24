package com.sky.interceptor;

import com.sky.context.BaseContext;
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

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int MAX_REQUESTS = 30;       // 窗口内最大请求数
    private static final long WINDOW_MS = 60 * 1000L;  // 窗口大小：60 秒
    private static final String KEY_PREFIX = "rate:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate_limiter.lua")));
        rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return true;
        }

        String key = KEY_PREFIX + userId;
        long now = System.currentTimeMillis();

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(now - WINDOW_MS),
                    String.valueOf(now),
                    String.valueOf(MAX_REQUESTS),
                    String.valueOf(WINDOW_MS)
            );

            if (result != null && result == 0L) {
                log.warn("[RateLimit] 用户 {} 触发限流", userId);
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":0,\"msg\":\"请求过于频繁，请稍后再试\"}");
                return false;
            }
        } catch (Exception e) {
            log.warn("[RateLimit] Redis 异常，限流降级跳过", e);
        }

        return true;
    }
}
