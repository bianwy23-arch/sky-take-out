package com.sky.mapper;

import com.sky.entity.OutboxMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMessageMapper {

    @Insert("insert into outbox_message(biz_key, event_type, payload, status, retry_count, next_retry_time, " +
            "created_time, update_time, user_id) " +
            "values(#{bizKey}, #{eventType}, #{payload}, #{status}, #{retryCount}, #{nextRetryTime}, " +
            "#{createdTime}, #{updateTime}, #{userId})")
    int insert(OutboxMessage outboxMessage);

    @Select("select * from outbox_message where status = 0 and next_retry_time <= now() order by id asc limit #{limit}")
    List<OutboxMessage> listReady(@Param("limit") int limit);

    @Update("update outbox_message set status = #{status}, update_time = #{updateTime}, last_error = null where id = #{id} and user_id = #{userId}")
    int updateStatus(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("status") Integer status,
                     @Param("updateTime") LocalDateTime updateTime);

    @Update("update outbox_message set status = 1, update_time = #{updateTime}, last_error = null " +
            "where id = #{id} and user_id = #{userId} and status = 0")
    int markSending(@Param("id") Long id, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update outbox_message set status = 2, update_time = #{updateTime}, last_error = null " +
            "where id = #{id} and user_id = #{userId} and status = 1")
    int markSent(@Param("id") Long id, @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update outbox_message set status = #{status}, retry_count = #{retryCount}, next_retry_time = #{nextRetryTime}, " +
            "last_error = #{lastError}, update_time = #{updateTime} where id = #{id} and user_id = #{userId}")
    int markRetry(@Param("id") Long id,
                  @Param("userId") Long userId,
                  @Param("status") Integer status,
                  @Param("retryCount") Integer retryCount,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError,
                  @Param("updateTime") LocalDateTime updateTime);
}
