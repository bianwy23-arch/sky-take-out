package com.sky.mapper;

import com.sky.dto.PaidCouponPoolSnapshot;
import com.sky.entity.PaidCouponInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PaidCouponInventoryMapper {

    int insert(PaidCouponInventory inventory);

    PaidCouponPoolSnapshot snapshot(@Param("couponId") Long couponId);

    List<PaidCouponInventory> lockByCouponIds(@Param("couponIds") List<Long> couponIds);

    int decrementRemaining(@Param("couponId") Long couponId, @Param("quantity") int quantity);

    int advanceNextUnitId(@Param("couponId") Long couponId, @Param("nextUnitId") long nextUnitId);
}
