package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一异步任务线程池：
 * - MQ 消费后的附属耗时逻辑
 * - 对账/补偿/修复类后台任务
 * - 非实时通知任务
 */
@Configuration
@Slf4j
public class AsyncTaskExecutorConfiguration implements AsyncConfigurer {

    public static final String ASYNC_TASK_EXECUTOR = "asyncTaskExecutor";
    private static final long SLOW_TASK_THRESHOLD_MS = 1000L;

    @Bean(name = ASYNC_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sky-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((runnable, threadPool) -> {
            log.warn("async task rejected, active={}, poolSize={}, core={}, max={}, queueSize={}, completed={}",
                    threadPool.getActiveCount(),
                    threadPool.getPoolSize(),
                    threadPool.getCorePoolSize(),
                    threadPool.getMaximumPoolSize(),
                    threadPool.getQueue().size(),
                    threadPool.getCompletedTaskCount());
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, threadPool);
        });
        AtomicLong sequence = new AtomicLong(0);
        executor.setTaskDecorator(runnable -> () -> {
            long taskId = sequence.incrementAndGet();
            long startNanos = System.nanoTime();
            try {
                runnable.run();
            } catch (Exception ex) {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.error("async task execution failed, taskId={}, costMs={}", taskId, costMs, ex);
                throw ex;
            } finally {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (costMs >= SLOW_TASK_THRESHOLD_MS) {
                    log.warn("async task slow, taskId={}, costMs={}, thread={}", taskId, costMs, Thread.currentThread().getName());
                } else if (log.isDebugEnabled()) {
                    log.debug("async task done, taskId={}, costMs={}, thread={}", taskId, costMs, Thread.currentThread().getName());
                }
            }
        });
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                log.error("async uncaught exception, method={}, paramsCount={}",
                        method == null ? "unknown" : method.getName(),
                        params == null ? 0 : params.length,
                        ex);
            }
        };
    }
}
