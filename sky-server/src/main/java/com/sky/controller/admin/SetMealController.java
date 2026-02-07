package com.sky.controller.admin;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/admin/setmeal")
@Api("套餐相關接口")
public class SetMealController {
    @Autowired
    private SetmealService setmealService;
@ApiOperation("新增套餐")
@PostMapping
    public Result save(@RequestBody SetmealDTO setmealDTO){
        log.info("新增套餐：{}",setmealDTO);
        setmealService.saveWithDish(setmealDTO);
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("套餐分頁查詢")
    public Result<PageResult> page(SetmealPageQueryDTO setmealPageQueryDTO){
    log.info("套餐分頁查詢：{}",setmealPageQueryDTO);
    PageResult pageResult =setmealService.pageQuery(setmealPageQueryDTO);
    return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("批量刪除")
    public Result delete(@RequestParam List<Long> ids){
    log.info("批量刪除：{}",ids);
    setmealService.deteleBatch(ids);
    return Result.success();
    }

    @PutMapping
    @ApiOperation("修改套餐")
    public Result update(@RequestBody SetmealDTO setmealDTO){
    log.info("修改套餐：{}",setmealDTO);
    setmealService.update(setmealDTO);
    return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("根據id查詢套餐")
    public Result<SetmealVO> getByIdWithDish(@PathVariable Long id){
    log.info("根據id查詢套餐：{}",id);
    SetmealVO setmealVO =setmealService.getByIdWithDish(id);
    return Result.success(setmealVO);
    }

    @PostMapping("/status/{status}")
    @ApiOperation("起售、禁售套餐")
    public Result startOrStop(@PathVariable Integer status,Long id){
    log.info("起售、禁售套餐：{}",status,id);
    setmealService.startOrStop(status,id);
    return Result.success();
    }
}
