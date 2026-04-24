package com.sky.task;

import com.sky.entity.OutboxMessage;
import com.sky.mapper.OutboxMessageMapper;
import com.sky.mq.OrderEventMqPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OutboxDispatchTask {

    private static final int MAX_RETRY = 5;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Autowired
    private OrderEventMqPublisher orderEventMqPublisher;

    @Scheduled(fixedDelay = 5000)
    public void dispatch() {
        List<OutboxMessage> list = outboxMessageMapper.listReady(100);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (OutboxMessage message : list) {
            try {
                orderEventMqPublisher.publish(message);
                outboxMessageMapper.markSent(message.getId(), message.getUserId(), LocalDateTime.now());
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
}
