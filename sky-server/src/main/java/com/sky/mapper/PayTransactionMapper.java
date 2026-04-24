package com.sky.mapper;

import com.sky.entity.PayTransaction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayTransactionMapper {

    @Insert("insert into pay_transaction(order_no, transaction_id, event_type, created_time, user_id) " +
            "values(#{orderNo}, #{transactionId}, #{eventType}, #{createdTime}, #{userId})")
    int insert(PayTransaction payTransaction);
}

