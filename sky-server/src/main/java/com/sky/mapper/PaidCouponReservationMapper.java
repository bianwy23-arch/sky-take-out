package com.sky.mapper;

import com.sky.entity.PaidCouponReservationBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaidCouponReservationMapper {

    PaidCouponReservationBatch lockByRequestId(@Param("requestId") String requestId);

    List<PaidCouponReservationBatch> lockByRequestIds(@Param("requestIds") List<String> requestIds);

    PaidCouponReservationBatch findByRequestId(@Param("requestId") String requestId);

    int insertReserved(@Param("requestId") String requestId,
                       @Param("userId") Long userId,
                       @Param("items") String items,
                       @Param("expiresAt") java.time.LocalDateTime expiresAt);

    int insertReservedBatch(@Param("batches") List<PaidCouponReservationBatch> batches);

    List<String> findExistingRequestIds(@Param("requestIds") List<String> requestIds);

    int markClaimed(@Param("requestId") String requestId);

    int markClaimedBatch(@Param("requestIds") List<String> requestIds);

    int markReleased(@Param("requestId") String requestId);

    List<PaidCouponReservationBatch> selectExpired(@Param("cursorExpiresAt") LocalDateTime cursorExpiresAt,
                                                    @Param("cursorRequestId") String cursorRequestId,
                                                    @Param("limit") int limit);

    String transactionIsolation();

    java.time.LocalDateTime databaseNow();
}
