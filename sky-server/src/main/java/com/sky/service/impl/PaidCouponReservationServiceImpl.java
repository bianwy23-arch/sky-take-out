package com.sky.service.impl;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.dto.PaidCouponPoolSnapshot;
import com.sky.dto.PaidCouponReserveDTO;
import com.sky.entity.PaidCouponInventory;
import com.sky.entity.PaidCouponReservationBatch;
import com.sky.exception.BaseException;
import com.sky.mapper.PaidCouponInventoryMapper;
import com.sky.mapper.PaidCouponReservationMapper;
import com.sky.properties.PaidCouponProperties;
import com.sky.service.PaidCouponReservationService;
import com.sky.service.PaidCouponReservationTxService;
import com.sky.config.AsyncTaskExecutorConfiguration;
import com.sky.service.paidcoupon.PaidCouponBatchers;
import com.sky.service.paidcoupon.PaidCouponItems;
import com.sky.service.paidcoupon.PaidCouponLockLab;
import com.sky.service.paidcoupon.PaidCouponPoolHints;
import com.sky.service.paidcoupon.PaidCouponReserveKey;
import com.sky.service.paidcoupon.ReserveIncompleteException;
import com.sky.vo.PaidCouponReservationVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;

@Service
@Slf4j
public class PaidCouponReservationServiceImpl implements PaidCouponReservationService {

    private static final String SOLD_OUT = "已售罄";
    private static final String UNAVAILABLE = "暂不可用";
    private static final long LOCK_RETRY_PAUSE_NANOS = 5_000_000L;

    @Autowired
    private PaidCouponReservationTxService txService;

    @Autowired
    private PaidCouponReservationMapper reservationMapper;

    @Autowired
    private PaidCouponInventoryMapper inventoryMapper;

    @Autowired
    private PaidCouponProperties properties;

    @Autowired
    private PaidCouponBatchers batchers;

    @Autowired
    private PaidCouponLockLab lockLab;

    @Autowired
    private PaidCouponPoolHints poolHints;

    @Autowired
    @Qualifier(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    private Executor asyncTaskExecutor;

    @Override
    public PaidCouponReservationVO reserve(Long userId, PaidCouponReserveDTO dto) {
        if (userId == null || dto == null || blank(dto.getRequestId())) {
            throw new BaseException("请求参数不完整");
        }
        if (dto.getRequestId().length() > 64) {
            throw new BaseException("请求参数不完整");
        }
        List<PaidCouponItemDTO> items = PaidCouponItems.normalize(dto.getItems());
        String canonical = PaidCouponItems.canonical(items);
        long deadline = System.nanoTime() + properties.getReserveTimeoutMs() * 1_000_000L;
        if (!lockLab.matches(items)) {
            Optional<PaidCouponReservationVO> batched = batchers.reserve(
                    new PaidCouponReserveKey(userId, dto.getRequestId(), canonical, items),
                    properties.getReserveTimeoutMs());
            if (batched.isPresent()) {
                refillAhead(items);
                return batched.get();
            }
        }
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new BaseException("系统繁忙，请稍后再试");
            }
            try {
                PaidCouponReservationVO reserved = txService.tryReserve(userId, dto.getRequestId(), canonical, items,
                        properties.getReservationTtlSeconds());
                refillAhead(items);
                return reserved;
            } catch (ReserveIncompleteException ex) {
                String terminal = recover(items);
                if (terminal != null) {
                    throw new BaseException(terminal);
                }
            } catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
                log.info("paid coupon reserve retry, requestId={}, reason={}", dto.getRequestId(), ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public PaidCouponReservationVO claim(Long userId, String requestId) {
        requireIdentity(userId, requestId);
        return batchers.claim(userId, requestId).orElseGet(() -> txService.claim(userId, requestId));
    }

    @Override
    public PaidCouponReservationVO release(Long userId, String requestId) {
        requireIdentity(userId, requestId);
        return txService.release(userId, requestId, false);
    }

    @Override
    public PaidCouponReservationVO query(Long userId, String requestId) {
        requireIdentity(userId, requestId);
        PaidCouponReservationBatch batch = reservationMapper.findByRequestId(requestId);
        if (batch == null || !userId.equals(batch.getUserId())) {
            throw new BaseException("预留不存在");
        }
        return PaidCouponReservationVO.builder()
                .requestId(batch.getRequestId())
                .state(batch.getState())
                .expiresAt(batch.getExpiresAt())
                .items(PaidCouponItems.parse(batch.getItems()))
                .build();
    }

    @Override
    public void initCoupon(Long couponId, Integer total) {
        if (couponId == null || total == null || total <= 0) {
            throw new BaseException("库存数量必须为正整数");
        }
        try {
            inventoryMapper.insert(PaidCouponInventory.builder()
                    .couponId(couponId)
                    .total(total)
                    .remaining(total)
                    .nextUnitId(1L)
                    .build());
        } catch (DuplicateKeyException ex) {
            throw new BaseException("券已存在");
        }
        txService.replenish(couponId, properties.getPoolCapacity());
    }

    @Override
    public int releaseExpired() {
        int released = 0;
        java.time.LocalDateTime cursorExpiresAt = null;
        String cursorRequestId = null;
        int batchSize = Math.max(1, properties.getExpireScanBatchSize());
        while (true) {
            List<PaidCouponReservationBatch> page = reservationMapper.selectExpired(cursorExpiresAt, cursorRequestId, batchSize);
            if (page.isEmpty()) {
                return released;
            }
            for (PaidCouponReservationBatch batch : page) {
                try {
                    PaidCouponReservationVO result = txService.release(batch.getUserId(), batch.getRequestId(), true);
                    if ("RELEASED".equals(result.getState())) {
                        released++;
                    }
                } catch (BaseException ex) {
                    log.info("paid coupon expire skip, requestId={}, reason={}", batch.getRequestId(), ex.getMessage());
                }
            }
            PaidCouponReservationBatch last = page.get(page.size() - 1);
            cursorExpiresAt = last.getExpiresAt();
            cursorRequestId = last.getRequestId();
            if (page.size() < batchSize) {
                return released;
            }
        }
    }

    /**
     * An empty pool makes every concurrent reserve fail, snapshot and retry at once, so
     * top the pool up in the background once it drops below the watermark. replenish()
     * still decides the exact count under the inventory lock.
     */
    private void refillAhead(List<PaidCouponItemDTO> items) {
        long watermark = (long) properties.getPoolCapacity() * properties.getRefillAheadPercent() / 100;
        for (PaidCouponItemDTO item : items) {
            Long couponId = item.getCouponId();
            long available = poolHints.estimatedAvailable(couponId);
            if (available < 0 || available >= watermark || !poolHints.tryStartRefill(couponId)) {
                continue;
            }
            asyncTaskExecutor.execute(() -> {
                try {
                    txService.replenish(couponId, properties.getPoolCapacity());
                } catch (RuntimeException ex) {
                    log.info("paid coupon refill ahead skipped, couponId={}, reason={}", couponId, ex.getMessage());
                } finally {
                    poolHints.refillDone(couponId);
                }
            });
        }
    }

    /**
     * @return a terminal client message, or null when the caller should retry the whole reserve
     */
    private String recover(List<PaidCouponItemDTO> items) {
        String terminal = null;
        boolean replenished = false;
        for (PaidCouponItemDTO item : items) {
            PaidCouponPoolSnapshot snapshot = inventoryMapper.snapshot(item.getCouponId());
            if (snapshot == null || snapshot.getRemaining() == null) {
                return "券不存在";
            }
            int remaining = snapshot.getRemaining();
            int available = snapshot.getAvailableCount();
            int reserved = snapshot.getReservedCount();
            long sellable = (long) remaining - reserved;
            if (sellable < item.getQuantity()) {
                if (remaining == 0) {
                    terminal = SOLD_OUT;
                } else if (terminal == null) {
                    terminal = UNAVAILABLE;
                }
                continue;
            }
            long unmaterialized = (long) remaining - available - reserved;
            if (available < item.getQuantity() && unmaterialized > 0) {
                int inserted = txService.replenish(item.getCouponId(), properties.getPoolCapacity());
                if (inserted > 0) {
                    replenished = true;
                }
            }
        }
        if (terminal != null) {
            return terminal;
        }
        if (!replenished) {
            LockSupport.parkNanos(LOCK_RETRY_PAUSE_NANOS);
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void requireIdentity(Long userId, String requestId) {
        if (userId == null || blank(requestId) || requestId.length() > 64) {
            throw new BaseException("请求参数不完整");
        }
    }
}
