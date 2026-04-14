package com.sky.controller.admin.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private Cache<String, List<DishVO>> dishLocalCache;

    @Autowired
    private RBloomFilter<Long> categoryBloomFilter;
    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/search")
    @ApiOperation("关键词搜索菜品（ES 全文检索）")
    public Result<List<DishVO>> search(String keyword) {
        List<DishVO> list = dishService.searchByKeyword(keyword);
        return Result.success(list);
    }

    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {

        // 1. 布隆过滤器拦截：不存在的 categoryId 直接返回空，防止缓存穿透
        if (!categoryBloomFilter.contains(categoryId)) {
            return Result.success(Collections.emptyList());
        }

        String key = "dish_" + categoryId;

        // 2. L1 Caffeine 本地缓存
        List<DishVO> list = dishLocalCache.getIfPresent(key);
        if (list != null) {
            return Result.success(list);
        }

        // 3. L2 Redis 缓存
        list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null && list.size() > 0) {
            dishLocalCache.put(key, list);
            return Result.success(list);
        }

        // 4. DB 查询，回填双级缓存
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);
        list = dishService.listWithFlavor(dish);
        long ttlSeconds = 1800 + ThreadLocalRandom.current().nextInt(600);
        redisTemplate.opsForValue().set(key, list, ttlSeconds, TimeUnit.SECONDS);
        dishLocalCache.put(key, list);

        return Result.success(list);
    }

}
