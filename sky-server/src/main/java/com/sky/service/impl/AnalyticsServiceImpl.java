package com.sky.service.impl;

import com.sky.service.AnalyticsService;
import com.sky.vo.UVPVReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class AnalyticsServiceImpl implements AnalyticsService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public UVPVReportVO getUVPVReport(LocalDate begin, LocalDate end) {
        List<String> dateList = new ArrayList<>();

        // 收集日期列表
        LocalDate date = begin;
        while (!date.isAfter(end)) {
            dateList.add(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
            date = date.plusDays(1);
        }

        // Pipeline 批量查询：N天只需 1 次网络往返（原来 2N 次）
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String dateStr : dateList) {
                connection.pfCount(("uv:" + dateStr).getBytes());
                connection.get(("pv:" + dateStr).getBytes());
            }
            return null;
        });

        // 解析结果：results 中按 [uv0, pv0, uv1, pv1, ...] 交替排列
        List<String> uvList = new ArrayList<>();
        List<String> pvList = new ArrayList<>();
        for (int i = 0; i < dateList.size(); i++) {
            Object uv = results.get(i * 2);
            Object pv = results.get(i * 2 + 1);
            uvList.add(uv == null ? "0" : uv.toString());
            pvList.add(pv == null ? "0" : pv.toString());
        }

        return UVPVReportVO.builder()
                .dateList(String.join(",", dateList))
                .uvList(String.join(",", uvList))
                .pvList(String.join(",", pvList))
                .build();
    }
}
