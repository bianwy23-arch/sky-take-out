package com.sky.task;

import com.sky.config.AsyncTaskExecutorConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池运行状态巡检日志。
 */
@Component
@Slf4j
public class AsyncExecutorMonitorTask {

    @Autowired
    private ThreadPoolTaskExecutor asyncTaskExecutor;

    @Scheduled(fixedDelay = 60000)
    public void logStats() {
        ThreadPoolExecutor executor = asyncTaskExecutor.getThreadPoolExecutor();
        if (executor == null) {
            return;
        }
        int active = executor.getActiveCount();
        int queueSize = executor.getQueue().size();
        boolean shouldWarn = active >= asyncTaskExecutor.getMaxPoolSize() * 0.8 || queueSize >= 100;
        if (shouldWarn) {
            log.warn("async executor busy, name={}, active={}, poolSize={}, core={}, max={}, queueSize={}, completed={}, taskCount={}",
                    AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR,
                    active,
                    executor.getPoolSize(),
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    queueSize,
                    executor.getCompletedTaskCount(),
                    executor.getTaskCount());
            return;
        }
        log.info("async executor stats, name={}, active={}, poolSize={}, core={}, max={}, queueSize={}, completed={}, taskCount={}",
                AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR,
                active,
                executor.getPoolSize(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                queueSize,
                executor.getCompletedTaskCount(),
                executor.getTaskCount());
    }
}
