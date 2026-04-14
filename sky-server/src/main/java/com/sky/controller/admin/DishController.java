package com.sky.controller.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private Cache<String, List<DishVO>> dishLocalCache;
    @Autowired
    private RBloomFilter<Long> categoryBloomFilter;
    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO){
        log.info("新增菜品：{}",dishDTO);
        dishService.saveWithFlavor(dishDTO);
        String key="dish_"+dishDTO.getCategoryId();
        //清理缓存数据
        cleanCacheData(key);
        // 新增菜品的分类加入布隆过滤器
        categoryBloomFilter.add(dishDTO.getCategoryId());
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO){
        log.info("菜品分页查询：{}",dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("批量删除菜品")
    public Result delete(@RequestParam List<Long> ids){
        log.info("批量删除菜品：{}",ids);
        dishService.deleteBatch(ids);
        cleanCacheData("dish_*");
        return Result.success();
    }
    /**
     * 根据id查询菜品和对应的口味
     * @param id
     * @return
     */
@GetMapping("/{id}")
@ApiOperation("根据id查询菜品")
    public Result<DishVO> getById(@PathVariable Long id){
    log.info("根据id查询菜品：{}",id);
    DishVO dishVO=dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
}


@PutMapping
@ApiOperation("编辑菜品")
   public Result update(@RequestBody DishDTO dishDTO){
    log.info("编辑菜品：{}",dishDTO);
    dishService.updateWithFlavor(dishDTO);
    //清理缓存数据
    cleanCacheData("dish_*");
    return Result.success();
   }


   @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<Dish>> getByList(Long categoryId){
    log.info("根据分类id查询菜品：{}",categoryId);
    List<Dish> dishList=dishService.list(categoryId);
    return Result.success(dishList);
   }

    /**
     * 菜品起售停售
     */
    @ApiOperation("菜品起售停售")
    @PostMapping("/status/{status}")
    public Result startOrStop(@PathVariable Integer status,Long id){
        log.info("菜品起售停售：{}",status,id);
        dishService.startOrStop(status,id);
        //清理缓存数据
       cleanCacheData("dish_*");
        return Result.success();
    }

    @PostMapping("/es/init")
    @ApiOperation("批量同步所有菜品到 ES（一次性初始化）")
    public Result<Integer> syncDishesToEs() {
        log.info("批量同步所有菜品到 ES");
        int count = dishService.syncAllDishesToEs();
        return Result.success(count);
    }

    private void cleanCacheData(String pattern) {
        // 用 SCAN 替代 KEYS，避免阻塞 Redis 单线程
        Set<String> keys = new java.util.HashSet<>();
        org.springframework.data.redis.core.ScanOptions options =
                org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(100).build();
        try (org.springframework.data.redis.core.Cursor<String> cursor =
                     redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        // 同步清理 Caffeine 本地缓存
        dishLocalCache.invalidateAll();
    }
}
