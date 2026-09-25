package com.sky.service.impl;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.dto.PaidCouponReservedCountDTO;
import com.sky.dto.PaidCouponReservedUnitDTO;
import com.sky.dto.PaidCouponUnitScanDTO;
import com.sky.entity.PaidCouponInventory;
import com.sky.entity.PaidCouponReservationBatch;
import com.sky.entity.PaidCouponUnit;
import com.sky.exception.BaseException;
import com.sky.mapper.PaidCouponInventoryMapper;
import com.sky.mapper.PaidCouponReservationMapper;
import com.sky.mapper.PaidCouponUnitMapper;
import com.sky.properties.PaidCouponProperties;
import com.sky.service.PaidCouponReservationTxService;
import com.sky.service.paidcoupon.BatchOutcome;
import com.sky.service.paidcoupon.PaidCouponClaimKey;
import com.sky.service.paidcoupon.PaidCouponReserveKey;
import com.sky.service.paidcoupon.PaidCouponItems;
import com.sky.service.paidcoupon.PaidCouponPoolHints;
import com.sky.service.paidcoupon.ReserveIncompleteException;
import com.sky.vo.PaidCouponReservationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.sky.service.paidcoupon.PaidCouponLockLab;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PaidCouponLockLab lockLab;

    @Autowired
    private PaidCouponProperties properties;

    @Autowired
    private PaidCouponPoolHints poolHints;

    @Override
    public PaidCouponReservationVO tryReserve(Long userId, String requestId, String canonicalItems,
                                              List<PaidCouponItemDTO> items, int ttlSeconds) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(lockLab.isolationFor(items));
        return transaction.execute(status -> reserveInTransaction(userId, requestId, canonicalItems, items, ttlSeconds));
    }

    private PaidCouponReservationVO reserveInTransaction(Long userId, String requestId, String canonicalItems,
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

        List<PaidCouponUnit> locked = lockLab.matches(items) || !properties.isScanHintEnabled()
                ? unitMapper.lockAvailableUnits(items)
                : lockFromScanHints(items);
        if (!covers(items, locked)) {
            if (locked.isEmpty() && lockLab.matches(items)) {
                lockLab.pauseAfterEmptyRead(items, requestId, reservationMapper.transactionIsolation());
            }
            throw new ReserveIncompleteException();
        }

        // SELECT FOR UPDATE above is intentionally preserved in both variants.
        if (lockLab.reverseWritesFor(items)) {
            insertReserved(requestId, locked);
            lockLab.pauseAfterFirstWrite(items, requestId, reservationMapper.transactionIsolation());
            deleteAvailable(locked);
        } else {
            deleteAvailable(locked);
            if (lockLab.matches(items)) {
                lockLab.pauseAfterFirstWrite(items, requestId, reservationMapper.transactionIsolation());
            }
            insertReserved(requestId, locked);
        }
        // These values were written in this transaction; avoid a second locking read.
        PaidCouponReservationBatch created = new PaidCouponReservationBatch();
        created.setRequestId(requestId);
        created.setUserId(userId);
        created.setItems(canonicalItems);
        created.setState(RESERVED);
        created.setExpiresAt(expiresAt);
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
        long nextUnitId = inventory.getNextUnitId();
        poolHints.replenished(couponId, (long) available + count);
        if (count == 0) {
            return 0;
        }
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
        // Available units were removed at reserve time. Claim deletes this request's
        // reserved rows and deducts the ledger; the ledger UPDATE comes last so the
        // hot inventory row is held only for that statement plus commit.
        assertReservedMatches(requestId, items);
        int deleted = unitMapper.deleteReservedByRequest(requestId);
        if (deleted != items.stream().mapToInt(PaidCouponItemDTO::getQuantity).sum()) {
            throw new BaseException("库存账本不一致");
        }
        if (reservationMapper.markClaimed(requestId) != 1) {
            throw new BaseException("库存账本不一致");
        }
        if (inventoryMapper.decrementBatch(items) != items.size()) {
            throw new BaseException("库存账本不一致");
        }
        batch.setState(CLAIMED);
        return toView(batch);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public Map<PaidCouponClaimKey, BatchOutcome<PaidCouponReservationVO>> claimBatch(Collection<PaidCouponClaimKey> keys) {
        List<String> requestIds = new ArrayList<>(new TreeSet<>(keys.stream()
                .map(PaidCouponClaimKey::getRequestId).collect(Collectors.toList())));
        Map<String, PaidCouponReservationBatch> batches = new HashMap<>();
        for (PaidCouponReservationBatch batch : reservationMapper.lockByRequestIds(requestIds)) {
            batches.put(batch.getRequestId(), batch);
        }
        Map<String, List<PaidCouponItemDTO>> reserved = new HashMap<>();
        for (PaidCouponReservedCountDTO row : unitMapper.countReservedByRequests(requestIds)) {
            reserved.computeIfAbsent(row.getRequestId(), k -> new ArrayList<>())
                    .add(new PaidCouponItemDTO(row.getCouponId(), row.getQuantity()));
        }

        Map<PaidCouponClaimKey, BatchOutcome<PaidCouponReservationVO>> outcomes = new HashMap<>();
        Map<PaidCouponClaimKey, PaidCouponReservationBatch> claiming = new HashMap<>();
        Map<Long, Integer> totals = new TreeMap<>();
        int expectedSold = 0;
        for (PaidCouponClaimKey key : keys) {
            PaidCouponReservationBatch batch = batches.get(key.getRequestId());
            if (batch == null || !key.getUserId().equals(batch.getUserId())) {
                outcomes.put(key, BatchOutcome.failure("预留不存在"));
            } else if (CLAIMED.equals(batch.getState())) {
                outcomes.put(key, BatchOutcome.success(toView(batch)));
            } else if (RELEASED.equals(batch.getState())) {
                outcomes.put(key, BatchOutcome.failure("预留已释放，不能确认"));
            } else if (batch.getDue() != null && batch.getDue() == 1) {
                outcomes.put(key, BatchOutcome.failure("预留已到期，不能确认"));
            } else {
                List<PaidCouponItemDTO> items = PaidCouponItems.parse(batch.getItems());
                if (!items.equals(reserved.get(key.getRequestId()))) {
                    outcomes.put(key, BatchOutcome.failure("库存账本不一致"));
                    continue;
                }
                claiming.put(key, batch);
                for (PaidCouponItemDTO item : items) {
                    totals.merge(item.getCouponId(), item.getQuantity(), Integer::sum);
                    expectedSold += item.getQuantity();
                }
            }
        }
        if (claiming.isEmpty()) {
            return outcomes;
        }

        List<String> claimedIds = claiming.keySet().stream()
                .map(PaidCouponClaimKey::getRequestId).sorted().collect(Collectors.toList());
        int deleted = unitMapper.deleteReservedByRequests(claimedIds);
        if (deleted != expectedSold) {
            throw new BaseException("库存账本不一致");
        }
        if (reservationMapper.markClaimedBatch(claimedIds) != claimedIds.size()) {
            throw new BaseException("库存账本不一致");
        }
        // One ledger UPDATE per batch: the hot inventory rows are locked once for all
        // claims in it. Any shortfall throws and each request retries on its own.
        List<PaidCouponItemDTO> decrements = new ArrayList<>(totals.size());
        totals.forEach((couponId, quantity) -> decrements.add(new PaidCouponItemDTO(couponId, quantity)));
        if (inventoryMapper.decrementBatch(decrements) != decrements.size()) {
            throw new BaseException("库存账本不一致");
        }
        claiming.forEach((key, batch) -> {
            batch.setState(CLAIMED);
            outcomes.put(key, BatchOutcome.success(toView(batch)));
        });
        return outcomes;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public Map<PaidCouponReserveKey, BatchOutcome<PaidCouponReservationVO>> reserveBatch(
            Collection<PaidCouponReserveKey> keys, int ttlSeconds) {
        Map<PaidCouponReserveKey, BatchOutcome<PaidCouponReservationVO>> outcomes = new HashMap<>();
        Map<String, Integer> perRequest = new HashMap<>();
        keys.forEach(key -> perRequest.merge(key.getRequestId(), 1, Integer::sum));
        List<String> requestIds = new ArrayList<>(perRequest.keySet());
        Set<String> existing = new HashSet<>(reservationMapper.findExistingRequestIds(requestIds));

        // Replays, conflicting duplicates and oversized requests keep the single path's
        // batch-row-first serialization and messages.
        List<PaidCouponReserveKey> batched = new ArrayList<>();
        Map<Long, Integer> demand = new TreeMap<>();
        int units = 0;
        for (PaidCouponReserveKey key : keys) {
            int quantity = key.getItems().stream().mapToInt(PaidCouponItemDTO::getQuantity).sum();
            if (perRequest.get(key.getRequestId()) > 1 || existing.contains(key.getRequestId())
                    || units + quantity > WRITE_BATCH) {
                outcomes.put(key, BatchOutcome.fallback());
                continue;
            }
            batched.add(key);
            units += quantity;
            key.getItems().forEach(item -> demand.merge(item.getCouponId(), item.getQuantity(), Integer::sum));
        }
        if (batched.isEmpty()) {
            return outcomes;
        }

        List<PaidCouponItemDTO> demandItems = new ArrayList<>(demand.size());
        demand.forEach((couponId, quantity) -> demandItems.add(new PaidCouponItemDTO(couponId, quantity)));
        Map<Long, ArrayDeque<PaidCouponUnit>> supply = new HashMap<>();
        for (PaidCouponUnit unit : lockFromScanHints(demandItems)) {
            supply.computeIfAbsent(unit.getCouponId(), k -> new ArrayDeque<>()).add(unit);
        }

        // Requests that cannot be fully covered fall back to the single path, which owns
        // replenish and sold-out messages. Their locked units are simply left in the pool.
        List<PaidCouponReserveKey> granted = new ArrayList<>();
        List<PaidCouponUnit> taken = new ArrayList<>();
        List<PaidCouponReservedUnitDTO> reservedRows = new ArrayList<>();
        for (PaidCouponReserveKey key : batched) {
            boolean covered = key.getItems().stream().allMatch(item -> supply.containsKey(item.getCouponId())
                    && supply.get(item.getCouponId()).size() >= item.getQuantity());
            if (!covered) {
                outcomes.put(key, BatchOutcome.fallback());
                continue;
            }
            for (PaidCouponItemDTO item : key.getItems()) {
                for (int i = 0; i < item.getQuantity(); i++) {
                    PaidCouponUnit unit = supply.get(item.getCouponId()).poll();
                    taken.add(unit);
                    reservedRows.add(new PaidCouponReservedUnitDTO(key.getRequestId(), unit.getCouponId(), unit.getUnitId()));
                }
            }
            granted.add(key);
        }
        if (granted.isEmpty()) {
            return outcomes;
        }

        LocalDateTime expiresAt = reservationMapper.databaseNow().plusSeconds(ttlSeconds);
        List<PaidCouponReservationBatch> rows = new ArrayList<>(granted.size());
        for (PaidCouponReserveKey key : granted) {
            PaidCouponReservationBatch row = new PaidCouponReservationBatch();
            row.setRequestId(key.getRequestId());
            row.setUserId(key.getUserId());
            row.setItems(key.getCanonicalItems());
            row.setState(RESERVED);
            row.setExpiresAt(expiresAt);
            rows.add(row);
        }
        // A replay that committed after the existence check throws DuplicateKey here and
        // rolls back the batch; every request then retries on the single path.
        reservationMapper.insertReservedBatch(rows);
        deleteAvailable(taken);
        unitMapper.insertReservedUnitRows(reservedRows);
        for (int i = 0; i < granted.size(); i++) {
            outcomes.put(granted.get(i), BatchOutcome.success(toView(rows.get(i))));
        }
        return outcomes;
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

    /**
     * Units are consumed from the low end, so the front of each coupon's index range fills
     * with delete-marked records until purge catches up, and a scan from the minimum walks
     * all of them. Start above the last unit this JVM locked; replenish only appends higher
     * ids, and a second pass below the hint still reaches units skipped by rolled-back
     * transactions, so the hint only moves where the scan starts.
     */
    private List<PaidCouponUnit> lockFromScanHints(List<PaidCouponItemDTO> items) {
        List<PaidCouponUnitScanDTO> scans = new ArrayList<>(items.size());
        for (PaidCouponItemDTO item : items) {
            long hint = poolHints.scanFrom(item.getCouponId());
            scans.add(new PaidCouponUnitScanDTO(item.getCouponId(), item.getQuantity(), hint > 0 ? hint : null, null));
        }
        List<PaidCouponUnit> locked = new ArrayList<>(unitMapper.lockAvailableUnitsInRange(scans));
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, Long> highest = new HashMap<>();
        for (PaidCouponUnit unit : locked) {
            counts.merge(unit.getCouponId(), 1, Integer::sum);
            highest.merge(unit.getCouponId(), unit.getUnitId(), Math::max);
        }
        List<PaidCouponUnitScanDTO> wrap = new ArrayList<>();
        for (PaidCouponUnitScanDTO scan : scans) {
            int missing = scan.getQuantity() - counts.getOrDefault(scan.getCouponId(), 0);
            if (missing > 0 && scan.getFromUnitId() != null) {
                wrap.add(new PaidCouponUnitScanDTO(scan.getCouponId(), missing, null, scan.getFromUnitId()));
            }
        }
        if (!wrap.isEmpty()) {
            List<PaidCouponUnit> below = unitMapper.lockAvailableUnitsInRange(wrap);
            below.forEach(unit -> counts.merge(unit.getCouponId(), 1, Integer::sum));
            locked.addAll(below);
        }
        counts.forEach((couponId, count) -> poolHints.locked(couponId, highest.getOrDefault(couponId, 0L), count));
        return locked;
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
