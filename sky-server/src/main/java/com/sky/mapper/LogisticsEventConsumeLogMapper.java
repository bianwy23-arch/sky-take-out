package com.sky.mapper;

import com.sky.entity.LogisticsEventConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogisticsEventConsumeLogMapper {

    @Insert("insert into logistics_event_consume_log(event_id, order_no, logistics_status, payload, created_time) " +
            "values(#{eventId}, #{orderNo}, #{logisticsStatus}, #{payload}, #{createdTime})")
    int insert(LogisticsEventConsumeLog consumeLog);
}

