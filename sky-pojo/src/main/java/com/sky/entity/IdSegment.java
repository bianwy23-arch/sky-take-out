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
public class IdSegment implements Serializable {

    private static final long serialVersionUID = 1L;

    private String bizType;
    private Long maxId;
    private Integer step;
    private LocalDateTime updateTime;
}
