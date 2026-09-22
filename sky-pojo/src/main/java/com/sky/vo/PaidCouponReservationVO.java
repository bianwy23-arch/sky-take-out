package com.sky.vo;

import com.sky.dto.PaidCouponItemDTO;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PaidCouponReservationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String state;
    private LocalDateTime expiresAt;
    private List<PaidCouponItemDTO> items;
}
