package com.sky.mapper;

import com.sky.entity.CouponGrabDeadMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponGrabDeadMessageMapper {

    @Insert("insert into coupon_grab_dead_message(event_id, coupon_id, user_id, payload, error_type, error_message, " +
            "status, replay_count, handle_action, dead_time, handle_time, created_time, update_time) " +
            "values(#{eventId}, #{couponId}, #{userId}, #{payload}, #{errorType}, #{errorMessage}, " +
            "#{status}, #{replayCount}, #{handleAction}, #{deadTime}, #{handleTime}, #{createdTime}, #{updateTime})")
    int insert(CouponGrabDeadMessage message);

    @Select("select * from coupon_grab_dead_message where status = 0 order by id asc limit #{limit}")
    List<CouponGrabDeadMessage> listPending(@Param("limit") int limit);

    @Update("update coupon_grab_dead_message set status = #{status}, replay_count = #{replayCount}, " +
            "handle_action = #{handleAction}, handle_time = #{handleTime}, update_time = #{updateTime} where id = #{id}")
    int markHandled(@Param("id") Long id,
                    @Param("status") Integer status,
                    @Param("replayCount") Integer replayCount,
                    @Param("handleAction") String handleAction,
                    @Param("handleTime") LocalDateTime handleTime,
                    @Param("updateTime") LocalDateTime updateTime);
}
