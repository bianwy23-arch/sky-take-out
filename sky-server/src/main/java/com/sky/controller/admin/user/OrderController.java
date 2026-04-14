package com.sky.controller.admin.user;

import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@Api(tags = "C端订单接口")
@RequestMapping("/user/order")
@Slf4j
public class OrderController {


    private static final String ORDER_TOKEN_PREFIX = "order:token:";

    @Autowired
    private OrderService orderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/token")
    @ApiOperation("获取下单令牌")
    public Result<String> getOrderToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = ORDER_TOKEN_PREFIX + BaseContext.getCurrentId() + ":" + token;
        stringRedisTemplate.opsForValue().set(key, "1", 10, TimeUnit.MINUTES);
        return Result.success(token);
    }

    @PostMapping("/submit")
    @ApiOperation("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO,
                                        @RequestHeader(value = "X-Order-Token") String orderToken) {
        log.info("用户下单：{}", ordersSubmitDTO);
        // 防重：原子删除令牌，delete 返回 true 说明 key 存在且本次是第一个请求
        String key = ORDER_TOKEN_PREFIX + BaseContext.getCurrentId() + ":" + orderToken;
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            return Result.error("请勿重复提交订单");
        }
        OrderSubmitVO orderSubmitVO = orderService.submitOrder(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @ApiOperation("订单支付")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        log.info("生成预支付交易单：{}", orderPaymentVO);
        // Mock环境：无真实微信支付，直接触发支付成功回调
        orderService.paySuccess(ordersPaymentDTO.getOrderNumber(), "MOCK-" + ordersPaymentDTO.getOrderNumber());
        return Result.success(orderPaymentVO);
    }
    @GetMapping("/historyOrders")
    @ApiOperation("用戶查询历史订单")
    public Result<PageResult> history(int page, int pageSize,Integer status){
        com.sky.result.PageResult pageResult =orderService.pageQuery(page,pageSize,status);
        return Result.success(pageResult);
    }

    @GetMapping("/orderDetail/{id}")
    @ApiOperation("用户查询订单详情")
    public Result<OrderVO> details(@PathVariable("id") Long id){
        OrderVO orderVO =orderService.detail(id);
        return Result.success(orderVO);
    }

    @PutMapping("/cancel/{id}")
    @ApiOperation("用户取消订单")
    public Result cancel(@PathVariable("id") Long id) throws Exception {
        orderService.cancel(id);
        return Result.success();
    }

    @PostMapping("/repetition/{id}")
    @ApiOperation("用户再来一单")
    public Result repetition(@PathVariable("id") Long id){
        orderService.repetition(id);
        return Result.success();
    }
}
