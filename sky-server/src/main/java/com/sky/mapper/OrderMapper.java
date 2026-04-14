package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {
    void insert(Orders orders);

    /**
     * 分页查询
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     * @param id
     * @return
     */
    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    /**
     * 根据订单号查询订单
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    @Select("select id from orders where number = #{number} and user_id = #{userId}")
    Long    getIdByNumberAndUserId(@Param("number") String number, @Param("userId") Long userId);
    /**
     * 修改订单
     * @param orders
     */
    void update(Orders orders);

    /**
     *  查询指定状态的订单数量
     * @param status
     * @return
     */
    @Select("select count(*) from orders where status=#{status}")
    Integer countStatus(Integer status);

    List<Orders> listTimeoutOrders(@Param("status") Integer status, @Param("orderTime") LocalDateTime orderTime);

    List<Orders> listRecentOrders(@Param("orderTimeAfter") LocalDateTime orderTimeAfter);
}
