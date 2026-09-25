package com.sky.mapper;

import com.sky.entity.Coupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CouponMapper {

    @Insert("INSERT INTO coupon(name, type, threshold, discount, total_count, remaining_count, status, start_time, end_time, create_time) " +
            "VALUES(#{name}, #{type}, #{threshold}, #{discount}, #{totalCount}, #{remainingCount}, #{status}, #{startTime}, #{endTime}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Coupon coupon);

    @Select("SELECT * FROM coupon ORDER BY create_time DESC")
    List<Coupon> listAll();

    @Select("SELECT * FROM coupon WHERE status = 1 AND start_time <= NOW() AND end_time >= NOW() ORDER BY create_time DESC")
    List<Coupon> listAvailable();

    @Select("SELECT * FROM coupon WHERE id = #{id}")
    Coupon getById(Long id);

    @Update("UPDATE coupon SET status = #{status} WHERE id = #{id}")
    void updateStatus(Long id, Integer status);

    @Update("UPDATE coupon SET remaining_count = remaining_count - 1 WHERE id = #{id} AND remaining_count > 0")
    int decrementStock(Long id);

    @Update("UPDATE coupon c SET c.remaining_count = GREATEST(0, c.total_count - " +
            "(SELECT COUNT(*) FROM user_coupon uc WHERE uc.coupon_id = c.id)) " +
            "WHERE c.id = #{id}")
    void fixRemainingCount(Long id);
}
