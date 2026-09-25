package com.sky.task;

import com.sky.entity.OutboxMessage;
import com.sky.mapper.OutboxMessageMapper;
import com.sky.mq.OrderEventMqPublisher;
import com.sky.mq.OutboxPublishTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sky.config.AsyncTaskExecutorConfiguration;
import org.springframework.beans.factory.annotation.Value;
@Component
@Slf4j
public class OutboxDispatchTask {

    private static final int MAX_RETRY = 5;
    private static final String TASK_LABEL = "task.outbox-dispatch";
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Autowired
    private OrderEventMqPublisher orderEventMqPublisher;

    @Autowired
    private OutboxPublishTracker outboxPublishTracker;

    @Autowired
    private Executor asyncTaskExecutor;

    @Value("${sky.mq.outbox-dispatch.batch-size}")
    private Integer batchSize;

    @Value("${sky.mq.outbox-dispatch.worker-count}")
    private Integer workerCount;

    @Scheduled(fixedDelayString = "${sky.mq.outbox-dispatch.fixed-delay-millis}")
    @Async(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    public void dispatch() {
        long startNanos = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            log.info("async task skipped, label={}, reason=previous-round-running, batchSize={}", TASK_LABEL, batchSize);
            return;
        }
        try {
            log.info("async task begin, label={}, batchSize={}", TASK_LABEL, batchSize);
            List<OutboxMessage> list = outboxMessageMapper.listReady(batchSize);
            log.info("async task fetched, label={}, readyCount={}", TASK_LABEL, list == null ? 0 : list.size());
            if (list == null || list.isEmpty()) {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("async task done, label={}, count=0, costMs={}", TASK_LABEL, costMs);
                return;
            }
            int effectiveWorkerCount = Math.max(1, Math.min(workerCount, list.size()));
            List<CompletableFuture<Void>> futures = new ArrayList<>(effectiveWorkerCount);
            for (int workerIndex = 0; workerIndex < effectiveWorkerCount; workerIndex++) {
                final int currentWorker = workerIndex;
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int index = currentWorker; index < list.size(); index += effectiveWorkerCount) {
                        dispatchOne(list.get(index));
                    }
                }, asyncTaskExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("async task done, label={}, count={}, workers={}, costMs={}", TASK_LABEL, list.size(), effectiveWorkerCount, costMs);
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("async task failed, label={}, costMs={}", TASK_LABEL, costMs, ex);
            throw ex;
        } finally {
            running.set(false);
        }
    }

    private void dispatchOne(OutboxMessage message) {
        try {
            int updated = outboxMessageMapper.markSending(message.getId(), message.getUserId(), LocalDateTime.now());
            if (updated != 1) {
                return;
            }
            String correlationId = orderEventMqPublisher.publish(message);
            outboxPublishTracker.scheduleTimeoutCheck(correlationId);
        } catch (Exception ex) {
            int retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
            int nextRetryCount = retryCount + 1;
            int status = nextRetryCount >= MAX_RETRY ? OutboxMessage.FAILED : OutboxMessage.NEW;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds((long) Math.min(60, 5L * nextRetryCount));
            outboxMessageMapper.markRetry(
                    message.getId(),
                    message.getUserId(),
                    status,
                    nextRetryCount,
                    nextRetryTime,
                    ex.getMessage(),
                    LocalDateTime.now()
            );
            log.error("outbox dispatch failed, id={}, retryCount={}", message.getId(), nextRetryCount, ex);
        }
    }
}
