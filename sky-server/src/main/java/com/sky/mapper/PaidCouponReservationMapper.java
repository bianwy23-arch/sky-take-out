package com.sky.mapper;

import com.sky.entity.PaidCouponReservationBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaidCouponReservationMapper {

    PaidCouponReservationBatch lockByRequestId(@Param("requestId") String requestId);

    PaidCouponReservationBatch findByRequestId(@Param("requestId") String requestId);

    int insertReserved(@Param("requestId") String requestId,
                       @Param("userId") Long userId,
                       @Param("items") String items,
                       @Param("expiresAt") java.time.LocalDateTime expiresAt);

    int markClaimed(@Param("requestId") String requestId);

    int markReleased(@Param("requestId") String requestId);

    List<PaidCouponReservationBatch> selectExpired(@Param("cursorExpiresAt") LocalDateTime cursorExpiresAt,
                                                    @Param("cursorRequestId") String cursorRequestId,
                                                    @Param("limit") int limit);

    String transactionIsolation();

    java.time.LocalDateTime databaseNow();
}
