package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Integer type;           // 1=满减 2=折扣
    private BigDecimal threshold;   // 使用门槛金额
    private BigDecimal discount;    // 优惠金额
    private Integer totalCount;     // 发放总量
    private Integer remainingCount; // 剩余数量
    private Integer status;         // 1=进行中 0=已结束
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
