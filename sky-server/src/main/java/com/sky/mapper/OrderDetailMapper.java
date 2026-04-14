package com.sky.mapper;

import com.sky.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderDetailMapper {
    void insertBatch(List<OrderDetail> orderDetailList);

    // 广播查询（仅用于无法获取 userId 的场景，如管理端按 id 查单个订单）
    @Select("select * from order_detail where order_id=#{id}")
    List<OrderDetail> getByOrderId(Long id);

    // 精准路由查询（有 userId 时优先用此方法）
    @Select("select * from order_detail where order_id=#{orderId} and user_id=#{userId}")
    List<OrderDetail> getByOrderIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
