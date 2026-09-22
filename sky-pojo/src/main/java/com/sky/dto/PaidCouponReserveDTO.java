package com.sky.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PaidCouponReserveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private List<PaidCouponItemDTO> items;
}
