package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishMapper {


    List<Dish> getBySetmealId(Long id);

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    /**
     * 插入菜品注释
     * @param dish
     */
    @AutoFill(value = OperationType.INSERT)
    void insert(Dish dish);

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);
/**
     * 根据id查询菜品和分类数据
     * @param id
     * @return
     */
@Select("select * from dish where id = #{id}")
    Dish getById(Long id);
/**
     * 根据id删除菜品数据
     * @param id
     */
@Delete("delete from dish where id = #{id}")
    void deleteById(Long id);
/**
     * 根据菜品id集合，批量删除菜品
     * @param ids
     */
    void deleteByIds(List<Long> ids);

    /**
     * 修改菜品信息
     * @param dish
     */

    @AutoFill(value = OperationType.UPDATE)
    void update(Dish dish);


    /**
     * 根据条件查询菜品数据
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

    /**
     * 预占库存：可用库存减少，锁定库存增加
     */
    int lockStock(@Param("dishId") Long dishId, @Param("num") Integer num, @Param("version") Integer version);

    /**
     * 确认扣减：支付成功后从锁定库存扣除
     */
    int confirmLockedStock(@Param("dishId") Long dishId, @Param("num") Integer num, @Param("version") Integer version);

    /**
     * 回补库存：取消订单时释放锁定库存到可用库存
     */
    int releaseStock(@Param("dishId") Long dishId, @Param("num") Integer num, @Param("version") Integer version);

    /**
     * 退还已确认库存：支付后取消/拒单时恢复可用库存（此时 stock_locked 已为 0，只需加回 stock_available）
     */
    int restoreConfirmedStock(@Param("dishId") Long dishId, @Param("num") Integer num, @Param("version") Integer version);
}
