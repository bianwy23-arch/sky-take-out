package com.sky.interceptor;

import com.sky.context.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class UVPVInterceptor implements HandlerInterceptor {

    private static final long UV_PV_TTL_SECONDS = 90L * 24 * 60 * 60;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Long userId = BaseContext.getCurrentId();

            // Pipeline 合并为一次网络往返
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                byte[] pvKey = ("pv:" + today).getBytes();
                connection.incr(pvKey);
                connection.expire(pvKey, UV_PV_TTL_SECONDS);

                if (userId != null) {
                    byte[] uvKey = ("uv:" + today).getBytes();
                    connection.pfAdd(uvKey, userId.toString().getBytes());
                    connection.expire(uvKey, UV_PV_TTL_SECONDS);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[UVPVInterceptor] Redis 不可用，UV/PV 统计降级跳过", e);
        }

        return true;
    }
}
