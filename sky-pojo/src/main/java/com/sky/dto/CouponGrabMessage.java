package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponGrabMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long couponId;
    private LocalDateTime grabTime;
}
