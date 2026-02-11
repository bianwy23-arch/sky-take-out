package com.sky.mapper;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShoppingCartMapper {
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    /**
     * 根据用户id更新购物车商品数量
     * @param cart
     */
    @Update("update shopping_cart set number = #{number} where id = #{id}")
    void updateByUserId(ShoppingCart cart);

    /**
     * 插入购物车数据
     * @param shoppingCart
     */
    void insert(ShoppingCart shoppingCart);


    /**
     * 根据用户id删除购物车数据
     * @param currentId
     */
    void deleteByUserId(Long currentId);


    /**
     * 根据id删除购物车数据
     * @param id
     */
    @Delete("delete from shopping_cart where id=#{id}")
    void deleteById(Long id);
}
