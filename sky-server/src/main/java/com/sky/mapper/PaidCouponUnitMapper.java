package com.sky.mapper;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.entity.PaidCouponUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PaidCouponUnitMapper {

    List<PaidCouponUnit> lockAvailableUnits(@Param("items") List<PaidCouponItemDTO> items);

    int deleteAvailableUnits(@Param("units") List<PaidCouponUnit> units);

    int insertAvailableUnits(@Param("units") List<PaidCouponUnit> units);

    int insertReservedUnits(@Param("requestId") String requestId, @Param("units") List<PaidCouponUnit> units);

    int deleteReservedByRequest(@Param("requestId") String requestId);

    List<PaidCouponItemDTO> countReservedByRequest(@Param("requestId") String requestId);
}
