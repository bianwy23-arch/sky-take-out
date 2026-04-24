package com.sky.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CouponCreateDTO {
    private String name;
    private Integer type;           // 1=满减 2=折扣
    private BigDecimal threshold;   // 使用门槛金额
    private BigDecimal discount;    // 优惠金额
    private Integer totalCount;     // 发放总量
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
