package com.sky.mq;

import com.sky.entity.OutboxMessage;
import com.sky.mapper.OutboxMessageMapper;
import lombok.Getter;
import lombok.Setter;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublishTracker {

    private final ConcurrentHashMap<String, PublishState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    public PublishState register(String correlationId, Long outboxId, Long userId) {
        PublishState state = new PublishState(outboxId, userId);
        states.put(correlationId, state);
        return state;
    }

    public void onConfirm(String correlationId, boolean ack, String cause) {
        PublishState state = states.get(correlationId);
        if (state == null) {
            return;
        }
        state.setAck(ack);
        state.setConfirmCause(cause);
        if (ack) {
            markSent(state);
            states.remove(correlationId);
        } else {
            fail(state, "confirm nack" + suffix(cause));
            states.remove(correlationId);
        }
    }

    public void onReturn(String correlationId, ReturnedMessage returned) {
        PublishState state = states.get(correlationId);
        if (state == null) {
            return;
        }
        state.setReturned(true);
        state.setReplyCode(returned.getReplyCode());
        state.setReplyText(returned.getReplyText());
        state.setExchange(returned.getExchange());
        state.setRoutingKey(returned.getRoutingKey());
        fail(state, "returned replyCode=" + state.getReplyCode()
                + ", replyText=" + state.getReplyText()
                + ", exchange=" + state.getExchange()
                + ", routingKey=" + state.getRoutingKey());
        states.remove(correlationId);
    }

    public void markTimeoutIfNecessary(String correlationId) {
        PublishState state = states.remove(correlationId);
        if (state == null) {
            return;
        }
        fail(state, "confirm timeout");
    }

    public void scheduleTimeoutCheck(String correlationId) {
        timeoutExecutor.schedule(() -> markTimeoutIfNecessary(correlationId), 3200, TimeUnit.MILLISECONDS);
    }

    private void markSent(PublishState state) {
        outboxMessageMapper.markSent(state.getOutboxId(), state.getUserId(), LocalDateTime.now());
    }

    private void fail(PublishState state, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        int retryCount = state.getRetryCount() == null ? 0 : state.getRetryCount();
        int nextRetryCount = retryCount + 1;
        int status = nextRetryCount >= 5 ? OutboxMessage.FAILED : OutboxMessage.NEW;
        LocalDateTime nextRetryTime = now.plusSeconds((long) Math.min(60, 5L * nextRetryCount));
        outboxMessageMapper.markRetry(
                state.getOutboxId(),
                state.getUserId(),
                status,
                nextRetryCount,
                nextRetryTime,
                errorMessage,
                now
        );
    }

    private String suffix(String cause) {
        return cause == null || cause.isEmpty() ? "" : ", cause=" + cause;
    }

    @Getter
    @Setter
    public static class PublishState {
        private final Long outboxId;
        private final Long userId;
        private Integer retryCount;
        private boolean ack;
        private boolean returned;
        private String confirmCause;
        private int replyCode;
        private String replyText;
        private String exchange;
        private String routingKey;

        public PublishState(Long outboxId, Long userId) {
            this.outboxId = outboxId;
            this.userId = userId;
        }
    }
}
