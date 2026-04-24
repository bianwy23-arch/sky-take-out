package com.sky.mapper;

import com.sky.entity.OrderEventConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderEventConsumeLogMapper {

    @Insert("insert into order_event_consume_log(consumer_name, event_id, biz_key, event_type, payload, created_time) " +
            "values(#{consumerName}, #{eventId}, #{bizKey}, #{eventType}, #{payload}, #{createdTime})")
    int insert(OrderEventConsumeLog consumeLog);
}

