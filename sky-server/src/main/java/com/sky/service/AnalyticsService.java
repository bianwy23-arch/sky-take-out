package com.sky.service;

import com.sky.vo.UVPVReportVO;

import java.time.LocalDate;

public interface AnalyticsService {

    /**
     * 查询指定日期范围内的 UV/PV 统计数据
     *
     * @param begin 开始日期（含）
     * @param end   结束日期（含）
     * @return UV/PV 报表 VO
     */
    UVPVReportVO getUVPVReport(LocalDate begin, LocalDate end);
}
