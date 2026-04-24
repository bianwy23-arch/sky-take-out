package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.AnalyticsService;
import com.sky.vo.UVPVReportVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/admin/analytics")
@Api(tags = "数据分析")
@Slf4j
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/uv-pv")
    @ApiOperation("UV/PV 统计")
    public Result<UVPVReportVO> uvpv(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        log.info("查询 UV/PV 统计，begin={}, end={}", begin, end);
        return Result.success(analyticsService.getUVPVReport(begin, end));
    }
}
