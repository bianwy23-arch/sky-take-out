package com.sky.benchmark;

import com.sky.result.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Map;

@RestController
@RequestMapping("/user/paid-coupon/benchmark")
@ConditionalOnProperty(name="sky.paid-coupon.benchmark-enabled", havingValue="true")
public class BenchmarkMetricsController {
    @GetMapping("/metrics")
    public Result<Map<String,Object>> metrics() { Map<String,Object> result=ConnectionMetrics.snapshot();
        java.util.List<Object> pools=new java.util.ArrayList<>();
        for(com.zaxxer.hikari.HikariDataSource ds:BenchmarkPools.SOURCES) {
            com.zaxxer.hikari.HikariPoolMXBean pool=ds.getHikariPoolMXBean();
            if(pool==null) continue;
            Map<String,Object> item=new java.util.LinkedHashMap<>();
            item.put("name",ds.getPoolName()); item.put("max",ds.getMaximumPoolSize());
            item.put("active",pool.getActiveConnections()); item.put("idle",pool.getIdleConnections());
            item.put("pending",pool.getThreadsAwaitingConnection()); pools.add(item);
        }
        result.put("pools",pools); return Result.success(result); }
}
