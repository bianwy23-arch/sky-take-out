package com.sky.mapper;

import com.sky.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserCouponMapper {

    @Insert("INSERT INTO user_coupon(user_id, coupon_id, status, grab_time) VALUES(#{userId}, #{couponId}, #{status}, #{grabTime})")
    void insert(UserCoupon userCoupon);

    @Select("SELECT uc.*, c.name AS coupon_name FROM user_coupon uc LEFT JOIN coupon c ON uc.coupon_id = c.id WHERE uc.user_id = #{userId} ORDER BY uc.grab_time DESC")
    List<UserCoupon> listByUserId(Long userId);

    @Select("SELECT COUNT(1) FROM user_coupon WHERE user_id = #{userId} AND coupon_id = #{couponId}")
    int existsByUserIdAndCouponId(Long userId, Long couponId);
}
