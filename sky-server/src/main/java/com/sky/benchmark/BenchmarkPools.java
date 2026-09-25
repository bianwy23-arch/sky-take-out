package com.sky.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.*;

/** Benchmark-only adapter pinned to the project's ShardingSphere 5.2.1 internals. */
@Component
@ConditionalOnProperty(name="sky.paid-coupon.benchmark-enabled", havingValue="true")
public class BenchmarkPools {
    public static final List<HikariDataSource> SOURCES=new java.util.concurrent.CopyOnWriteArrayList<>();
    @Autowired private DataSource dataSource;
    private Object field(String name) {
        Field f=ReflectionUtils.findField(dataSource.getClass(),name);
        if(f==null) throw new IllegalStateException("Unsupported ShardingSphere version: "+name);
        ReflectionUtils.makeAccessible(f); return ReflectionUtils.getField(f,dataSource);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void bind() {
        ContextManager manager=(ContextManager)field("contextManager");
        manager.getDataSourceMap((String)field("databaseName")).forEach((name,source)-> {
            if(!(source instanceof HikariDataSource)) throw new IllegalStateException("Expected Hikari: "+name);
            HikariDataSource pool=(HikariDataSource)source;
            pool.setMetricsTrackerFactory((ignored,stats)-> new IMetricsTracker() {
                @Override public void recordConnectionAcquiredNanos(long value) { ConnectionMetrics.record(name+".acquire",value); }
                @Override public void recordConnectionUsageMillis(long value) { ConnectionMetrics.record(name+".hold",value*1000000L); }
                @Override public void recordConnectionTimeout() { ConnectionMetrics.record(name+".timeout",0); }
            });
            SOURCES.add(pool);
        });
    }
}
