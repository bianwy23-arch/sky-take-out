package com.sky.service.impl;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.entity.PaidCouponInventory;
import com.sky.entity.PaidCouponReservationBatch;
import com.sky.entity.PaidCouponUnit;
import com.sky.exception.BaseException;
import com.sky.mapper.PaidCouponInventoryMapper;
import com.sky.mapper.PaidCouponReservationMapper;
import com.sky.mapper.PaidCouponUnitMapper;
import com.sky.service.PaidCouponReservationTxService;
import com.sky.service.paidcoupon.PaidCouponItems;
import com.sky.service.paidcoupon.ReserveIncompleteException;
import com.sky.vo.PaidCouponReservationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaidCouponReservationTxServiceImpl implements PaidCouponReservationTxService {

    private static final int WRITE_BATCH = 400;

    private static final String RESERVED = "RESERVED";
    private static final String CLAIMED = "CLAIMED";
    private static final String RELEASED = "RELEASED";

    @Autowired
    private PaidCouponReservationMapper reservationMapper;

    @Autowired
    private PaidCouponInventoryMapper inventoryMapper;

    @Autowired
    private PaidCouponUnitMapper unitMapper;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public PaidCouponReservationVO tryReserve(Long userId, String requestId, String canonicalItems,
                                              List<PaidCouponItemDTO> items, int ttlSeconds) {
        PaidCouponReservationBatch existing = reservationMapper.lockByRequestId(requestId);
        if (existing != null) {
            assertSameRequest(existing, userId, canonicalItems);
            return toView(existing);
        }

        // Acquire the unique request key before touching inventory. A concurrent
        // replay waits here, then retries to read the committed batch. If stock
        // is insufficient, this row rolls back with the reservation transaction.
        java.time.LocalDateTime expiresAt = reservationMapper.databaseNow().plusSeconds(ttlSeconds);
        reservationMapper.insertReserved(requestId, userId, canonicalItems, expiresAt);

        List<PaidCouponUnit> locked = unitMapper.lockAvailableUnits(items);
        if (!covers(items, locked)) {
            throw new ReserveIncompleteException();
        }

        deleteAvailable(locked);
        insertReserved(requestId, locked);
        PaidCouponReservationBatch created = reservationMapper.lockByRequestId(requestId);
        return toView(created);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public int replenish(Long couponId, int poolCapacity) {
        List<PaidCouponInventory> locked = inventoryMapper.lockByCouponIds(Collections.singletonList(couponId));
        if (locked.size() != 1) {
            throw new BaseException("券不存在");
        }
        PaidCouponInventory inventory = locked.get(0);
        com.sky.dto.PaidCouponPoolSnapshot snapshot = inventoryMapper.snapshot(couponId);
        if (snapshot == null) {
            throw new BaseException("券不存在");
        }
        int available = snapshot.getAvailableCount();
        int reserved = snapshot.getReservedCount();
        int remaining = inventory.getRemaining();
        long unmaterialized = (long) remaining - available - reserved;
        if (unmaterialized < 0 || available < 0 || reserved < 0 || remaining < 0) {
            throw new BaseException("库存账本不一致");
        }
        int room = Math.max(0, poolCapacity - available);
        int count = (int) Math.min(room, unmaterialized);
        if (count == 0) {
            return 0;
        }
        long nextUnitId = inventory.getNextUnitId();
        List<PaidCouponUnit> units = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            units.add(new PaidCouponUnit(couponId, nextUnitId + i));
        }
        insertAvailable(units);
        inventoryMapper.advanceNextUnitId(couponId, nextUnitId + count);
        return count;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public PaidCouponReservationVO claim(Long userId, String requestId) {
        PaidCouponReservationBatch batch = lockOwned(userId, requestId);
        if (CLAIMED.equals(batch.getState())) {
            return toView(batch);
        }
        if (RELEASED.equals(batch.getState())) {
            throw new BaseException("预留已释放，不能确认");
        }
        if (batch.getDue() != null && batch.getDue() == 1) {
            throw new BaseException("预留已到期，不能确认");
        }
        List<PaidCouponItemDTO> items = PaidCouponItems.parse(batch.getItems());
        lockInventories(items);
        assertReservedMatches(requestId, items);
        unitMapper.deleteReservedByRequest(requestId);
        for (PaidCouponItemDTO item : items) {
            int updated = inventoryMapper.decrementRemaining(item.getCouponId(), item.getQuantity());
            if (updated != 1) {
                throw new BaseException("库存账本不一致");
            }
        }
        if (reservationMapper.markClaimed(requestId) != 1) {
            throw new BaseException("库存账本不一致");
        }
        batch.setState(CLAIMED);
        return toView(batch);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public PaidCouponReservationVO release(Long userId, String requestId, boolean requireExpired) {
        PaidCouponReservationBatch batch = lockOwned(userId, requestId);
        if (RELEASED.equals(batch.getState())) {
            return toView(batch);
        }
        if (CLAIMED.equals(batch.getState())) {
            throw new BaseException("预留已确认，不能释放");
        }
        if (requireExpired && (batch.getDue() == null || batch.getDue() == 0)) {
            return toView(batch);
        }
        List<PaidCouponItemDTO> items = PaidCouponItems.parse(batch.getItems());
        lockInventories(items);
        unitMapper.deleteReservedByRequest(requestId);
        if (reservationMapper.markReleased(requestId) != 1) {
            throw new BaseException("库存账本不一致");
        }
        batch.setState(RELEASED);
        return toView(batch);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public String currentIsolation() {
        return reservationMapper.transactionIsolation();
    }

    private PaidCouponReservationBatch lockOwned(Long userId, String requestId) {
        PaidCouponReservationBatch batch = reservationMapper.lockByRequestId(requestId);
        if (batch == null || !userId.equals(batch.getUserId())) {
            throw new BaseException("预留不存在");
        }
        return batch;
    }

    private void lockInventories(List<PaidCouponItemDTO> items) {
        List<Long> couponIds = new ArrayList<>(items.size());
        for (PaidCouponItemDTO item : items) {
            couponIds.add(item.getCouponId());
        }
        List<PaidCouponInventory> inventories = inventoryMapper.lockByCouponIds(couponIds);
        if (inventories.size() != couponIds.size()) {
            throw new BaseException("券不存在");
        }
    }

    private void assertReservedMatches(String requestId, List<PaidCouponItemDTO> items) {
        List<PaidCouponItemDTO> actual = unitMapper.countReservedByRequest(requestId);
        if (actual.size() != items.size()) {
            throw new BaseException("库存账本不一致");
        }
        for (int i = 0; i < items.size(); i++) {
            PaidCouponItemDTO expected = items.get(i);
            PaidCouponItemDTO found = actual.get(i);
            if (!expected.getCouponId().equals(found.getCouponId())
                    || !expected.getQuantity().equals(found.getQuantity())) {
                throw new BaseException("库存账本不一致");
            }
        }
    }

    private void assertSameRequest(PaidCouponReservationBatch batch, Long userId, String canonicalItems) {
        if (!userId.equals(batch.getUserId()) || !canonicalItems.equals(batch.getItems())) {
            throw new BaseException("请求冲突");
        }
    }

    private boolean covers(List<PaidCouponItemDTO> items, List<PaidCouponUnit> locked) {
        Map<Long, Integer> counts = new HashMap<>();
        for (PaidCouponUnit unit : locked) {
            counts.merge(unit.getCouponId(), 1, Integer::sum);
        }
        for (PaidCouponItemDTO item : items) {
            if (counts.getOrDefault(item.getCouponId(), 0).intValue() != item.getQuantity().intValue()) {
                return false;
            }
        }
        return counts.size() == items.size();
    }

    private void deleteAvailable(List<PaidCouponUnit> units) {
        for (int from = 0; from < units.size(); from += WRITE_BATCH) {
            int updated = unitMapper.deleteAvailableUnits(units.subList(from, Math.min(from + WRITE_BATCH, units.size())));
            if (updated != Math.min(WRITE_BATCH, units.size() - from)) {
                throw new BaseException("库存账本不一致");
            }
        }
    }

    private void insertReserved(String requestId, List<PaidCouponUnit> units) {
        for (int from = 0; from < units.size(); from += WRITE_BATCH) {
            unitMapper.insertReservedUnits(requestId, units.subList(from, Math.min(from + WRITE_BATCH, units.size())));
        }
    }

    private void insertAvailable(List<PaidCouponUnit> units) {
        for (int from = 0; from < units.size(); from += WRITE_BATCH) {
            unitMapper.insertAvailableUnits(units.subList(from, Math.min(from + WRITE_BATCH, units.size())));
        }
    }

    private PaidCouponReservationVO toView(PaidCouponReservationBatch batch) {
        return PaidCouponReservationVO.builder()
                .requestId(batch.getRequestId())
                .state(batch.getState())
                .expiresAt(batch.getExpiresAt())
                .items(PaidCouponItems.parse(batch.getItems()))
                .build();
    }
}
