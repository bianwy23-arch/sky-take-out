package com.sky.mapper;

import com.sky.entity.CouponRedisCompensation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponRedisCompensationMapper {

    @Insert("insert into coupon_redis_compensation(coupon_id, user_id, source, status, retry_count, next_retry_time, " +
            "last_error, created_time, update_time) values(#{couponId}, #{userId}, #{source}, #{status}, " +
            "#{retryCount}, #{nextRetryTime}, #{lastError}, #{createdTime}, #{updateTime}) " +
            "on duplicate key update status = 0, next_retry_time = values(next_retry_time), " +
            "last_error = values(last_error), update_time = values(update_time)")
    int upsert(CouponRedisCompensation compensation);

    @Select("select * from coupon_redis_compensation where status = 0 and next_retry_time <= now() order by id asc limit #{limit}")
    List<CouponRedisCompensation> listReady(@Param("limit") int limit);

    @Select("select count(*) from coupon_redis_compensation " +
            "where coupon_id = #{couponId} and user_id = #{userId} and status in (0, 2)")
    int existsUnresolvedRollback(@Param("couponId") Long couponId, @Param("userId") Long userId);

    @Update("update coupon_redis_compensation set status = #{status}, retry_count = #{retryCount}, " +
            "next_retry_time = #{nextRetryTime}, last_error = #{lastError}, update_time = #{updateTime} where id = #{id}")
    int mark(@Param("id") Long id,
             @Param("status") Integer status,
             @Param("retryCount") Integer retryCount,
             @Param("nextRetryTime") LocalDateTime nextRetryTime,
             @Param("lastError") String lastError,
             @Param("updateTime") LocalDateTime updateTime);
}
