package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponVO {
    private Long id;
    private String name;
    private Integer type;
    private BigDecimal threshold;
    private BigDecimal discount;
    private Integer totalCount;
    private Integer remainingCount;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean grabbed;  // 当前用户是否已领取（用户端用）
}
