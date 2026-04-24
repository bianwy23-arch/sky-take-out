package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UVPVReportVO implements Serializable {

    // 日期，以逗号分隔，例如：2026-03-10,2026-03-11,...
    private String dateList;

    // UV（独立访客数），以逗号分隔
    private String uvList;

    // PV（页面访问量），以逗号分隔
    private String pvList;
}
