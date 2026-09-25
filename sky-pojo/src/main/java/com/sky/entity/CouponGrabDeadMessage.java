package com.sky.entity;

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
public class CouponGrabDeadMessage implements Serializable {

    public static final Integer PENDING = 0;
    public static final Integer REPLAYED = 1;
    public static final Integer COMPENSATED = 2;
    public static final Integer IGNORED = 3;
    public static final Integer UNKNOWN = 4;
    public static final Integer COMPENSATING = 5;

    private static final long serialVersionUID = 1L;

    private Long id;
    private String eventId;
    private Long couponId;
    private Long userId;
    private String payload;
    private String errorType;
    private String errorMessage;
    private Integer status;
    private Integer replayCount;
    private String handleAction;
    private LocalDateTime deadTime;
    private LocalDateTime handleTime;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
}
