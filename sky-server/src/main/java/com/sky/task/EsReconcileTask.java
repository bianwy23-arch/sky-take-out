package com.sky.task;

import com.sky.document.DishDocument;
import com.sky.document.OrderDocument;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ES 与 MySQL 定时对账任务
 *
 * 每小时执行一次，发现不一致时自动修复：
 *   - 菜品：全量对比（数据量小，可接受）
 *   - 订单：只对比最近 24 小时（分片表，全量代价大）
 */
@Component
@Slf4j
public class EsReconcileTask {

    private static final String ES_DISH_INDEX = "sky_dishes";
    private static final String ES_ORDER_INDEX = "sky_orders";

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ElasticsearchOperations esOperations;

    @Scheduled(fixedDelay = 3600000)
    public void reconcile() {
        log.info("[EsReconcileTask] 开始 ES 对账");
        reconcileDishes();
        reconcileRecentOrders();
        log.info("[EsReconcileTask] ES 对账结束");
    }

    // -------- 菜品对账 --------

    private void reconcileDishes() {
        List<Dish> dishes;
        try {
            dishes = dishMapper.list(null);
        } catch (Exception e) {
            log.error("[EsReconcileTask] 菜品对账：查询 MySQL 失败", e);
            return;
        }
        if (dishes == null || dishes.isEmpty()) {
            return;
        }

        int fixed = 0;
        for (Dish dish : dishes) {
            try {
                DishDocument esDoc = esOperations.get(
                        String.valueOf(dish.getId()),
                        DishDocument.class,
                        IndexCoordinates.of(ES_DISH_INDEX));

                if (esDoc == null || isDishInconsistent(dish, esDoc)) {
                    saveOrUpdateDishInEs(dish);
                    fixed++;
                }
            } catch (Exception e) {
                log.error("[EsReconcileTask] 菜品对账异常，dishId={}", dish.getId(), e);
            }
        }
        log.info("[EsReconcileTask] 菜品对账完成，修复 {} 条", fixed);
    }

    private boolean isDishInconsistent(Dish dish, DishDocument doc) {
        if (!safeEquals(dish.getStatus(), doc.getStatus())) return true;
        if (!safeEquals(dish.getName(), doc.getName())) return true;
        double dbPrice = dish.getPrice() != null ? dish.getPrice().doubleValue() : 0.0;
        double esPrice = doc.getPrice() != null ? doc.getPrice() : 0.0;
        if (Double.compare(dbPrice, esPrice) != 0) return true;
        if (!safeEquals(dish.getDescription(), doc.getDescription())) return true;
        return false;
    }

    private void saveOrUpdateDishInEs(Dish dish) {
        String categoryName = null;
        if (dish.getCategoryId() != null) {
            Category category = categoryMapper.getById(dish.getCategoryId());
            if (category != null) {
                categoryName = category.getName();
            }
        }
        DishDocument doc = DishDocument.builder()
                .id(dish.getId())
                .name(dish.getName())
                .categoryId(dish.getCategoryId())
                .categoryName(categoryName)
                .price(dish.getPrice() != null ? dish.getPrice().doubleValue() : null)
                .image(dish.getImage())
                .description(dish.getDescription())
                .status(dish.getStatus())
                .build();
        esOperations.save(doc, IndexCoordinates.of(ES_DISH_INDEX));
    }

    // -------- 订单对账（近 24 小时） --------

    private void reconcileRecentOrders() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Orders> orders;
        try {
            orders = orderMapper.listRecentOrders(since);
        } catch (Exception e) {
            log.error("[EsReconcileTask] 订单对账：查询 MySQL 失败", e);
            return;
        }
        if (orders == null || orders.isEmpty()) {
            return;
        }

        int fixed = 0;
        for (Orders order : orders) {
            try {
                OrderDocument esDoc = esOperations.get(
                        String.valueOf(order.getId()),
                        OrderDocument.class,
                        IndexCoordinates.of(ES_ORDER_INDEX));

                if (esDoc == null || isOrderInconsistent(order, esDoc)) {
                    syncOrderToEs(order);
                    fixed++;
                }
            } catch (Exception e) {
                log.error("[EsReconcileTask] 订单对账异常，orderId={}", order.getId(), e);
            }
        }
        log.info("[EsReconcileTask] 订单对账完成（近24h），修复 {} 条", fixed);
    }

    private boolean isOrderInconsistent(Orders order, OrderDocument doc) {
        return !safeEquals(order.getStatus(), doc.getStatus());
    }

    private void syncOrderToEs(Orders order) {
        List<OrderDetail> details = orderDetailMapper.getByOrderIdAndUserId(order.getId(), order.getUserId());
        String orderDishes = details == null ? "" : details.stream()
                .map(d -> d.getName() + "*" + d.getNumber() + ";")
                .collect(Collectors.joining());

        OrderDocument doc = OrderDocument.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .number(order.getNumber())
                .status(order.getStatus())
                .payStatus(order.getPayStatus())
                .phone(order.getPhone())
                .consignee(order.getConsignee())
                .address(order.getAddress())
                .amount(order.getAmount() != null ? order.getAmount().doubleValue() : null)
                .orderTimeMillis(toEpochMillis(order.getOrderTime()))
                .cancelReason(order.getCancelReason())
                .orderDishes(orderDishes)
                .build();

        esOperations.save(doc, IndexCoordinates.of(ES_ORDER_INDEX));
    }

    // -------- 工具方法 --------

    private <T> boolean safeEquals(T a, T b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private Long toEpochMillis(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
