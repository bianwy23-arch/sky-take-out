package com.sky.utils;

import com.sky.entity.IdSegment;
import com.sky.mapper.IdSegmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class OrderIdGenerator {

    private static final String ORDER_BIZ_TYPE = "orders";
    private static final long EPOCH_MILLIS = 1704067200000L;
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long MAX_WAIT_BACKWARD_MILLIS = 5L;
    private static final long RECOVERY_STABLE_MILLIS = 30_000L;

    @Value("${sky.id.worker-id:1}")
    private long workerId;

    @Autowired
    private IdSegmentMapper idSegmentMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long lastTimestamp = -1L;
    private long sequence = 0L;
    private boolean segmentFallback;
    private long segmentCurrent;
    private long segmentMax;

    public synchronized long nextId() {
        validateWorkerId();
        long currentTimestamp = currentTimeMillis();
        if (segmentFallback) {
            if (currentTimestamp >= lastTimestamp + RECOVERY_STABLE_MILLIS) {
                segmentFallback = false;
                sequence = 0L;
                log.warn("order id generator recovered to snowflake, currentTimestamp={}, lastTimestamp={}",
                        currentTimestamp, lastTimestamp);
            } else {
                return nextSegmentId();
            }
        }

        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            if (offset <= MAX_WAIT_BACKWARD_MILLIS) {
                currentTimestamp = waitUntil(lastTimestamp);
            } else {
                segmentFallback = true;
                log.error("order id generator clock moved backwards, offsetMs={}, switchTo=segment", offset);
                return nextSegmentId();
            }
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                currentTimestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;
        return ((currentTimestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long nextSegmentId() {
        if (segmentCurrent >= segmentMax) {
            allocateSegment();
        }
        segmentCurrent++;
        return segmentCurrent;
    }

    private void allocateSegment() {
        IdSegment segment = transactionTemplate.execute(status -> {
            int rows = idSegmentMapper.allocate(ORDER_BIZ_TYPE);
            if (rows != 1) {
                throw new IllegalStateException("id segment allocate failed, bizType=" + ORDER_BIZ_TYPE);
            }
            return idSegmentMapper.getByBizType(ORDER_BIZ_TYPE);
        });
        if (segment == null || segment.getMaxId() == null || segment.getStep() == null || segment.getStep() <= 0) {
            throw new IllegalStateException("id segment invalid, bizType=" + ORDER_BIZ_TYPE);
        }
        segmentMax = segment.getMaxId();
        segmentCurrent = segmentMax - segment.getStep();
        log.warn("order id segment allocated, range=({}, {}]", segmentCurrent, segmentMax);
    }

    private long waitUntil(long targetTimestamp) {
        long currentTimestamp = currentTimeMillis();
        while (currentTimestamp < targetTimestamp) {
            try {
                Thread.sleep(Math.min(1L, targetTimestamp - currentTimestamp));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("order id generator interrupted while waiting clock recovery", e);
            }
            currentTimestamp = currentTimeMillis();
        }
        return currentTimestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private void validateWorkerId() {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException("workerId out of range: " + workerId);
        }
    }
}
