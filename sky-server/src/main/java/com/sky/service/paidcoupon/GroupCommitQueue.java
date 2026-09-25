package com.sky.service.paidcoupon;

import com.sky.exception.BaseException;
import com.sky.properties.PaidCouponProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Opportunistic group commit: a worker takes whatever requests queued up while the
 * previous transaction was committing, so a lone request still runs immediately.
 * Workers only run the batch transaction; anything the batch cannot decide is handed
 * back to the caller thread as {@link Optional#empty()} to run the single-request path.
 */
@Slf4j
public final class GroupCommitQueue<K, V> {

    private final String name;
    private final PaidCouponProperties.Batch config;
    private final Function<Collection<K>, Map<K, BatchOutcome<V>>> batch;
    private final BlockingQueue<Pending<K, V>> queue;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public GroupCommitQueue(String name, PaidCouponProperties.Batch config,
                            Function<Collection<K>, Map<K, BatchOutcome<V>>> batch) {
        this.name = name;
        this.config = config;
        this.batch = batch;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity()));
    }

    public static String threadPrefix(String name) {
        return "paid-" + name + "-batch-";
    }

    public void start() {
        if (!config.isEnabled()) {
            return;
        }
        running = true;
        for (int i = 0; i < config.getWorkers(); i++) {
            Thread worker = new Thread(this::drain, threadPrefix(name) + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        Pending<K, V> pending;
        while ((pending = queue.poll()) != null) {
            pending.result.complete(BatchOutcome.fallback());
        }
    }

    public boolean enabled() {
        return running;
    }

    /** @return the batch result, or empty when the caller must run the single-request path */
    public Optional<V> submit(K key) {
        return submit(key, config.getWaitTimeoutMs());
    }

    public Optional<V> submit(K key, long timeoutMs) {
        Pending<K, V> pending = new Pending<>(key);
        if (!running || !queue.offer(pending)) {
            return Optional.empty();
        }
        BatchOutcome<V> outcome;
        try {
            outcome = pending.result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // Outcome unknown to the caller; a replay with the same requestId is idempotent.
            throw new BaseException("系统繁忙，请稍后再试");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BaseException("系统繁忙，请稍后再试");
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
        return outcome.isFallback() ? Optional.empty() : Optional.ofNullable(outcome.valueOrThrow());
    }

    private void drain() {
        int maxSize = Math.max(1, config.getMaxSize());
        List<Pending<K, V>> taken = new ArrayList<>(maxSize);
        while (running) {
            try {
                Pending<K, V> first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                taken.add(first);
                queue.drainTo(taken, maxSize - 1);
                process(taken);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ex) {
                log.error("paid coupon {} batch worker failure, size={}", name, taken.size(), ex);
                taken.forEach(p -> p.result.complete(BatchOutcome.fallback()));
            } finally {
                taken.clear();
            }
        }
    }

    private void process(List<Pending<K, V>> taken) {
        Map<K, List<Pending<K, V>>> byKey = new LinkedHashMap<>();
        for (Pending<K, V> pending : taken) {
            byKey.computeIfAbsent(pending.key, k -> new ArrayList<>()).add(pending);
        }
        Map<K, BatchOutcome<V>> outcomes;
        try {
            outcomes = applyWithOneRetry(byKey.keySet());
        } catch (RuntimeException ex) {
            // The whole batch rolled back; every request retries on its own.
            log.info("paid coupon {} batch fallback, size={}, reason={}", name, byKey.size(), ex.toString());
            taken.forEach(p -> p.result.complete(BatchOutcome.fallback()));
            return;
        }
        byKey.forEach((key, waiters) -> {
            BatchOutcome<V> outcome = outcomes.getOrDefault(key, BatchOutcome.fallback());
            waiters.forEach(w -> w.result.complete(outcome));
        });
    }

    private Map<K, BatchOutcome<V>> applyWithOneRetry(Collection<K> keys) {
        try {
            return batch.apply(keys);
        } catch (PessimisticLockingFailureException ex) {
            // Deadlock victim or lock timeout: the transaction rolled back, so one retry is safe.
            return batch.apply(keys);
        }
    }

    private static final class Pending<K, V> {
        final K key;
        final CompletableFuture<BatchOutcome<V>> result = new CompletableFuture<>();

        Pending(K key) {
            this.key = key;
        }
    }
}
