package com.sky.mapper;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.dto.PaidCouponReservedCountDTO;
import com.sky.dto.PaidCouponReservedUnitDTO;
import com.sky.dto.PaidCouponUnitScanDTO;
import com.sky.entity.PaidCouponUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PaidCouponUnitMapper {

    List<PaidCouponUnit> lockAvailableUnits(@Param("items") List<PaidCouponItemDTO> items);

    List<PaidCouponUnit> lockAvailableUnitsInRange(@Param("scans") List<PaidCouponUnitScanDTO> scans);

    int deleteAvailableUnits(@Param("units") List<PaidCouponUnit> units);

    int insertAvailableUnits(@Param("units") List<PaidCouponUnit> units);

    int insertReservedUnits(@Param("requestId") String requestId, @Param("units") List<PaidCouponUnit> units);

    int insertReservedUnitRows(@Param("rows") List<PaidCouponReservedUnitDTO> rows);

    int deleteReservedByRequest(@Param("requestId") String requestId);

    List<PaidCouponItemDTO> countReservedByRequest(@Param("requestId") String requestId);

    int deleteReservedByRequests(@Param("requestIds") List<String> requestIds);

    List<PaidCouponReservedCountDTO> countReservedByRequests(@Param("requestIds") List<String> requestIds);
}
