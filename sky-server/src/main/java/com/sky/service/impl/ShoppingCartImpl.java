package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ShoppingCartImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    @Override
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
       //查询当前要加入购物车的菜品是否存在
            log.info("添加菜品：{}",shoppingCartDTO);
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
            //threadlocal绑定了拦截器解析jwt得到的userid
            shoppingCart.setUserId(BaseContext.getCurrentId());
            List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

            if(list != null && list.size() > 0){
                //如果已经存在，增加数量
                ShoppingCart cart = list.get(0);
                cart.setNumber(cart.getNumber()+1);
                shoppingCartMapper.updateByUserId(cart);
            }else{
                //如果不存在数据，需要添加数据

                //先判断是菜品还是套餐
                if(shoppingCartDTO.getDishId() != null){
                    //本次添加到购物车的是菜品
                    Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
                    shoppingCart.setName(dish.getName());
                    shoppingCart.setImage(dish.getImage());
                    shoppingCart.setAmount(dish.getPrice());
                    shoppingCart.setNumber(1);
                    shoppingCart.setCreateTime(LocalDateTime.now());

                }else{
                    //本次添加到购物车是套餐
                    Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                    shoppingCart.setName(setmeal.getName());
                    shoppingCart.setImage(setmeal.getImage());
                    shoppingCart.setAmount(setmeal.getPrice());
                    shoppingCart.setNumber(1);
                    shoppingCart.setCreateTime(LocalDateTime.now());

                }
                //最后插入新数据
                shoppingCartMapper.insert(shoppingCart);
            }

    }

    @Override
    public List<ShoppingCart> showShoppingCart() {
        Long currentId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(currentId)
                .build();
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        return  list;
    }
}
