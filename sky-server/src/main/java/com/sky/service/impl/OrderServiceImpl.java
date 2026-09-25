package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.document.OrderDocument;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.OrderIdGenerator;
import com.sky.utils.OrderNumberGenerator;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    private static final int MAX_OPTIMISTIC_RETRY = 3;
    private static final long ADMIN_DB_FALLBACK_MINUTES = 70L;

    /** Redis key: ZSET，score = 下单时间戳(ms)，member = "{orderId}:{userId}" */
    private static final String PENDING_ORDER_ZSET = "order:pending";

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private PayTransactionMapper payTransactionMapper;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Autowired
    private OrderIdGenerator orderIdGenerator;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ElasticsearchOperations esOperations;

    @Autowired
    private com.sky.websocket.OrderStatusNotifier orderStatusNotifier;

    private static final String ES_ORDER_INDEX = "sky_orders";

    /**
     * 用户下单
     */
    @Transactional
    @Override
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        log.info("用户下单：{}", ordersSubmitDTO);

        Long addressBookId = ordersSubmitDTO.getAddressBookId();
        AddressBook addressBook = addressBookMapper.getById(addressBookId);
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        Long currentId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder().userId(currentId).build();
        List<ShoppingCart> cartList = shoppingCartMapper.list(shoppingCart);
        if (cartList == null || cartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        // 先预占库存，失败直接回滚整个下单事务
        lockStock(cartList);

        // 构建订单
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setId(orderIdGenerator.nextId());
        orders.setUserId(currentId);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        // 新订单号：{yyyyMMddHHmmss}{userId后6位}{4位序列}，支持从订单号反解路由分片
        orders.setNumber(OrderNumberGenerator.generate(currentId));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setAddress(addressBook.getDetail());
        orderMapper.insert(orders);

        // 构建订单明细，必须设置 userId（分片键，保证与 orders 路由到同一分片）
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : cartList) {
            OrderDetail orderDetail = OrderDetail.builder()
                    .orderId(orders.getId())
                    .userId(currentId)          // 分片键
                    .name(cart.getName())
                    .image(cart.getImage())
                    .dishId(cart.getDishId())
                    .setmealId(cart.getSetmealId())
                    .dishFlavor(cart.getDishFlavor())
                    .number(cart.getNumber())
                    .amount(cart.getAmount())
                    .build();
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        // 下单成功后清空购物车
        shoppingCartMapper.deleteByUserId(currentId);

        // 保存 Outbox 事件消息（分片键同为 userId）
        saveOutboxMessage(orders.getNumber(), "ORDER_CREATED",
                "orderId=" + orders.getId() + ",userId=" + currentId + ",amount=" + orders.getAmount(),
                currentId);

        // 写入 Redis ZSET 待支付集合，用于高效超时扫描
        double score = orders.getOrderTime()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String member = orders.getId() + ":" + currentId;
        redisTemplate.opsForZSet().add(PENDING_ORDER_ZSET, member, score);

        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();
    }


    /**
     * 订单支付（Mock实现：无真实微信支付，返回占位数据，通过 /notify/paySuccess/mock 触发实际状态变更）
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Orders order = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (Orders.PAID.equals(order.getPayStatus())) {
            throw new OrderBusinessException("该订单已支付");
        }
        // Mock：返回占位数据，前端拿到后直接调用 /notify/paySuccess/mock 完成支付
        return OrderPaymentVO.builder()
                .nonceStr("MOCK_NONCE")
                .paySign("MOCK_SIGN")
                .timeStamp(String.valueOf(System.currentTimeMillis() / 1000))
                .signType("RSA")
                .packageStr("prepay_id=MOCK_" + order.getNumber())
                .build();
    }

    /**
     * 支付成功，修改订单状态
     */
    @Override
    @Transactional
    public void paySuccess(String outTradeNo, String transactionId) {
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 幂等：已支付则直接返回
        if (Orders.PAID.equals(ordersDB.getPayStatus())) {
            return;
        }
        if (!Orders.PENDING_PAYMENT.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        boolean isNewPayment = true;
        try {
            payTransactionMapper.insert(PayTransaction.builder()
                    .orderNo(outTradeNo)
                    .transactionId(transactionId)
                    .eventType("PAY_SUCCESS")
                    .createdTime(LocalDateTime.now())
                    .userId(ordersDB.getUserId())   // 分片键
                    .build());
        } catch (DuplicateKeyException ex) {
            // pay_transaction 已存在：说明是重试，跳过库存确认和订单状态更新，但仍需补写 outbox
            log.warn("pay_transaction duplicate, skip stock/order update, orderId={}", ordersDB.getId());
            isNewPayment = false;
        }

        if (isNewPayment) {
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
            confirmLockedStock(orderDetailList);

            Orders orders = Orders.builder()
                    .id(ordersDB.getId())
                    .userId(ordersDB.getUserId())
                    .status(Orders.TO_BE_CONFIRMED)
                    .payStatus(Orders.PAID)
                    .checkoutTime(LocalDateTime.now())
                    .build();
            orderMapper.update(orders);
        }

        // 无论是否重试都补写 outbox（saveOutboxMessage 内部有幂等保护）
        saveOutboxMessage(outTradeNo, "ORDER_PAID",
                "orderId=" + ordersDB.getId() + ",transactionId=" + transactionId,
                ordersDB.getUserId());

        // 支付成功后从待支付 ZSET 移除
        String member = ordersDB.getId() + ":" + ordersDB.getUserId();
        redisTemplate.opsForZSet().remove(PENDING_ORDER_ZSET, member);
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), ordersDB.getId(), Orders.TO_BE_CONFIRMED, "支付成功，等待商家接单");
    }

    @Override
    public PageResult pageQuery(int page, int pageSize, Integer status) {
        PageHelper.startPage(page, pageSize);
        Long currentId = BaseContext.getCurrentId();
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(currentId);
        ordersPageQueryDTO.setStatus(status);
        Page<Orders> pages = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList = new ArrayList<>();
        if (pages != null && pages.size() > 0) {
            for (Orders orders : pages) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                List<OrderDetail> list = orderDetailMapper.getByOrderIdAndUserId(orders.getId(), orders.getUserId());
                orderVO.setOrderDetailList(list);
                orderVOList.add(orderVO);
            }
        }
        long total = pages.getTotal();
        return new PageResult(total, orderVOList);
    }

    @Override
    public OrderVO detail(Long id) {
        Orders orders = orderMapper.getById(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderIdAndUserId(id, orders.getUserId()));
        return orderVO;
    }

    @Transactional
    @Override
    public void cancel(Long id) throws Exception {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setUserId(ordersDB.getUserId());
        if (Orders.PENDING_PAYMENT.equals(ordersDB.getStatus())) {
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
            releaseStock(orderDetailList);
            // 从待支付 ZSET 移除
            String member = ordersDB.getId() + ":" + ordersDB.getUserId();
            redisTemplate.opsForZSet().remove(PENDING_ORDER_ZSET, member);
        }
        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            // 已付款：库存已从锁定转为真实扣减，需恢复 stock_available
            List<OrderDetail> paidDetailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
            restoreConfirmedStock(paidDetailList);
            // Mock环境：跳过真实微信退款
            log.info("Mock退款：订单 {} 用户取消退款（Mock）", ordersDB.getNumber());
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
        saveOutboxMessage(ordersDB.getNumber(), "ORDER_CANCELLED",
                "orderId=" + ordersDB.getId() + ",payStatus=" + ordersDB.getPayStatus(),
                ordersDB.getUserId());
    }

    @Transactional
    @Override
    public void cancelTimeoutOrder(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            return;
        }
        if (!Orders.PENDING_PAYMENT.equals(ordersDB.getStatus())) {
            return;
        }
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
        releaseStock(orderDetailList);

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setUserId(ordersDB.getUserId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("支付超时自动取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);

        saveOutboxMessage(ordersDB.getNumber(), "ORDER_CANCELLED",
                "orderId=" + ordersDB.getId() + ",reason=timeout",
                ordersDB.getUserId());
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), ordersDB.getId(), Orders.CANCELLED, "您的订单因超时未支付已自动取消");
    }

    @Override
    public void repetition(Long id) {
        Long currentId = BaseContext.getCurrentId();
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderIdAndUserId(id, currentId);
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(currentId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        List<OrderVO> recentDbOrders = queryRecentAdminOrdersFromDb(ordersPageQueryDTO);
        List<OrderVO> esOrders = queryAdminOrdersFromEs(ordersPageQueryDTO);
        Map<Long, OrderVO> merged = new LinkedHashMap<>();
        recentDbOrders.forEach(order -> merged.put(order.getId(), order));
        esOrders.forEach(order -> merged.putIfAbsent(order.getId(), order));

        List<OrderVO> mergedList = merged.values().stream()
                .sorted(Comparator.comparing(OrderVO::getOrderTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int page = Math.max(ordersPageQueryDTO.getPage(), 1);
        int pageSize = Math.max(ordersPageQueryDTO.getPageSize(), 1);
        int fromIndex = Math.min((page - 1) * pageSize, mergedList.size());
        int toIndex = Math.min(fromIndex + pageSize, mergedList.size());
        return new PageResult(mergedList.size(), mergedList.subList(fromIndex, toIndex));
    }

    private List<OrderVO> queryAdminOrdersFromEs(OrdersPageQueryDTO ordersPageQueryDTO) {
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        if (ordersPageQueryDTO.getNumber() != null && !ordersPageQueryDTO.getNumber().isEmpty()) {
            boolQuery.filter(QueryBuilders.termQuery("number", ordersPageQueryDTO.getNumber()));
        }
        if (ordersPageQueryDTO.getPhone() != null && !ordersPageQueryDTO.getPhone().isEmpty()) {
            boolQuery.filter(QueryBuilders.termQuery("phone", ordersPageQueryDTO.getPhone()));
        }
        if (ordersPageQueryDTO.getStatus() != null) {
            boolQuery.filter(QueryBuilders.termQuery("status", ordersPageQueryDTO.getStatus()));
        }
        if (ordersPageQueryDTO.getBeginTime() != null) {
            long beginMillis = ordersPageQueryDTO.getBeginTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            boolQuery.filter(QueryBuilders.rangeQuery("orderTimeMillis").gte(beginMillis));
        }
        if (ordersPageQueryDTO.getEndTime() != null) {
            long endMillis = ordersPageQueryDTO.getEndTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            boolQuery.filter(QueryBuilders.rangeQuery("orderTimeMillis").lte(endMillis));
        }

        int page = ordersPageQueryDTO.getPage();
        int pageSize = ordersPageQueryDTO.getPageSize();
        org.springframework.data.elasticsearch.core.query.NativeSearchQuery query =
                new NativeSearchQueryBuilder()
                        .withQuery(boolQuery)
                        .withPageable(PageRequest.of(page - 1, pageSize))
                        .build();

        // 索引不存在时返回空结果（首次使用 ES 前无数据）
        org.springframework.data.elasticsearch.core.IndexOperations indexOps =
                esOperations.indexOps(OrderDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
            return new ArrayList<>();
        }

        SearchHits<OrderDocument> hits = esOperations.search(query, OrderDocument.class);
        return hits.getSearchHits().stream()
                .map(hit -> docToOrderVO(hit.getContent()))
                .collect(Collectors.toList());
    }

    private List<OrderVO> queryRecentAdminOrdersFromDb(OrdersPageQueryDTO source) {
        OrdersPageQueryDTO dbQuery = new OrdersPageQueryDTO();
        BeanUtils.copyProperties(source, dbQuery);
        LocalDateTime fallbackBeginTime = LocalDateTime.now().minusMinutes(ADMIN_DB_FALLBACK_MINUTES);
        if (dbQuery.getBeginTime() == null || dbQuery.getBeginTime().isBefore(fallbackBeginTime)) {
            dbQuery.setBeginTime(fallbackBeginTime);
        }
        if (dbQuery.getEndTime() != null && dbQuery.getEndTime().isBefore(fallbackBeginTime)) {
            return new ArrayList<>();
        }
        PageHelper.startPage(1, Math.max(source.getPage() * source.getPageSize(), source.getPageSize()));
        Page<Orders> page = orderMapper.pageQuery(dbQuery);
        return getOrderVOList(page);
    }

    private OrderVO docToOrderVO(OrderDocument doc) {
        OrderVO vo = new OrderVO();
        vo.setId(doc.getId());
        vo.setUserId(doc.getUserId());
        vo.setNumber(doc.getNumber());
        vo.setStatus(doc.getStatus());
        vo.setPayStatus(doc.getPayStatus());
        vo.setPhone(doc.getPhone());
        vo.setConsignee(doc.getConsignee());
        vo.setAddress(doc.getAddress());
        if (doc.getAmount() != null) {
            vo.setAmount(BigDecimal.valueOf(doc.getAmount()));
        }
        if (doc.getOrderTimeMillis() != null) {
            vo.setOrderTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(doc.getOrderTimeMillis()), ZoneId.systemDefault()));
        }
        vo.setCancelReason(doc.getCancelReason());
        vo.setOrderDishes(doc.getOrderDishes());
        return vo;
    }

    @Override
    public OrderStatisticsVO statistics() {
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    @Override
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderIdAndUserId(id, orders.getUserId()));
        orderVO.setOrderDishes(getOrderDishesStr(orders));
        return orderVO;
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = orderMapper.getById(ordersConfirmDTO.getId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        orders.setStatus(Orders.CONFIRMED);
        orderMapper.update(orders);
        updateEsOrderStatus(ordersConfirmDTO.getId(), Orders.CONFIRMED, null);
        orderStatusNotifier.notifyUser(orders.getUserId(), orders.getId(), Orders.CONFIRMED, "您的订单已被商家接单");
    }

    @Override
    @Transactional
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        if (ordersDB == null || ordersDB.getStatus() == null || ordersDB.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            // 已付款：库存已真实扣减，拒单需恢复
            List<OrderDetail> detailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
            restoreConfirmedStock(detailList);
            // Mock环境：跳过真实微信退款
            log.info("Mock退款：订单 {} 拒单退款（Mock）", ordersDB.getNumber());
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setUserId(ordersDB.getUserId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
        updateEsOrderStatus(ordersDB.getId(), Orders.CANCELLED, ordersRejectionDTO.getRejectionReason());
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), ordersDB.getId(), Orders.CANCELLED,
                "您的订单已被商家拒绝：" + ordersRejectionDTO.getRejectionReason());
    }

    @Override
    @Transactional
    public void adminCancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Integer payStatus = ordersDB.getPayStatus();
        List<OrderDetail> detailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
        if (payStatus == Orders.PAID) {
            // 已付款：库存已真实扣减，需恢复 stock_available
            restoreConfirmedStock(detailList);
            // Mock环境：跳过真实微信退款，直接记录
            log.info("Mock退款：订单 {} 取消，金额已原路退回（Mock）", ordersDB.getNumber());
        } else {
            // 未付款：库存处于锁定状态，释放锁定
            releaseStock(detailList);
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setUserId(ordersDB.getUserId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelTime(LocalDateTime.now());
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orderMapper.update(orders);
        updateEsOrderStatus(ordersDB.getId(), Orders.CANCELLED, ordersCancelDTO.getCancelReason());
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), ordersDB.getId(), Orders.CANCELLED,
                "您的订单已被商家取消：" + ordersCancelDTO.getCancelReason());
    }

    @Override
    public void delivery(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(id);
        orders.setUserId(ordersDB.getUserId());
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
        updateEsOrderStatus(id, Orders.DELIVERY_IN_PROGRESS, null);
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), id, Orders.DELIVERY_IN_PROGRESS, "您的订单正在配送中");
    }

    @Override
    public void complete(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null || !Orders.DELIVERY_IN_PROGRESS.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(id);
        orders.setUserId(ordersDB.getUserId());
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(orders);
        updateEsOrderStatus(id, Orders.COMPLETED, null);
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), id, Orders.COMPLETED, "您的订单已完成，感谢您的惠顾");
    }

    public List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> ordersList = page.getResult();
        if (ordersList != null && ordersList.size() > 0) {
            for (Orders orders : ordersList) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDish = getOrderDishesStr(orders);
                orderVO.setOrderDishes(orderDish);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    private String getOrderDishesStr(Orders orders) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderIdAndUserId(orders.getId(), orders.getUserId());
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDishes = x.getName() + "*" + x.getNumber() + ";";
            return orderDishes;
        }).collect(Collectors.toList());
        return String.join(";", orderDishList);
    }

    private void lockStock(List<ShoppingCart> cartList) {
        for (ShoppingCart cart : cartList) {
            if (cart.getDishId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < MAX_OPTIMISTIC_RETRY; i++) {
                Dish dish = dishMapper.getById(cart.getDishId());
                if (dish == null || dish.getStockAvailable() == null || dish.getStockAvailable() < cart.getNumber()) {
                    throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
                }
                int rows = dishMapper.lockStock(cart.getDishId(), cart.getNumber(), dish.getVersion());
                if (rows == 1) {
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
            }
        }
    }

    private void confirmLockedStock(List<OrderDetail> orderDetailList) {
        for (OrderDetail orderDetail : orderDetailList) {
            if (orderDetail.getDishId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < MAX_OPTIMISTIC_RETRY; i++) {
                Dish dish = dishMapper.getById(orderDetail.getDishId());
                if (dish == null || dish.getStockLocked() == null || dish.getStockLocked() < orderDetail.getNumber()) {
                    throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
                }
                int rows = dishMapper.confirmLockedStock(orderDetail.getDishId(), orderDetail.getNumber(), dish.getVersion());
                if (rows == 1) {
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
            }
        }
    }

    private void releaseStock(List<OrderDetail> orderDetailList) {
        for (OrderDetail orderDetail : orderDetailList) {
            if (orderDetail.getDishId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < MAX_OPTIMISTIC_RETRY; i++) {
                Dish dish = dishMapper.getById(orderDetail.getDishId());
                if (dish == null || dish.getStockLocked() == null || dish.getStockLocked() < orderDetail.getNumber()) {
                    throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
                }
                int rows = dishMapper.releaseStock(orderDetail.getDishId(), orderDetail.getNumber(), dish.getVersion());
                if (rows == 1) {
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                throw new OrderBusinessException(MessageConstant.DISH_STOCK_NOT_ENOUGH);
            }
        }
    }

    /**
     * 退还已确认库存：支付成功后取消/拒单时恢复 stock_available。
     * 与 releaseStock 的区别：stock_locked 已在 confirmLockedStock 时归零，无需再动。
     * 失败时只记录日志，不抛异常——取消本身不应因库存补偿失败而回滚。
     */
    private void restoreConfirmedStock(List<OrderDetail> orderDetailList) {
        for (OrderDetail orderDetail : orderDetailList) {
            if (orderDetail.getDishId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < MAX_OPTIMISTIC_RETRY; i++) {
                Dish dish = dishMapper.getById(orderDetail.getDishId());
                if (dish == null) {
                    break;
                }
                int rows = dishMapper.restoreConfirmedStock(orderDetail.getDishId(), orderDetail.getNumber(), dish.getVersion());
                if (rows == 1) {
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                log.error("restoreConfirmedStock failed after {} retries, dishId={}", MAX_OPTIMISTIC_RETRY, orderDetail.getDishId());
            }
        }
    }

    /**
     * 局部更新 ES 订单 status（及可选的 cancelReason）。
     * ES 写失败只记录日志，不阻断主业务。
     */
    private void updateEsOrderStatus(Long orderId, Integer status, String cancelReason) {
        try {
            Document doc = Document.create();
            doc.put("status", status);
            if (cancelReason != null) {
                doc.put("cancelReason", cancelReason);
            }
            UpdateQuery updateQuery = UpdateQuery.builder(String.valueOf(orderId))
                    .withDocument(doc)
                    .build();
            esOperations.update(updateQuery, IndexCoordinates.of(ES_ORDER_INDEX));
        } catch (Exception e) {
            log.error("ES status sync failed, orderId={}, status={}", orderId, status, e);
        }
    }

    /**
     * 保存 Outbox 消息（必须携带 userId 作为分片键）
     */
    private void saveOutboxMessage(String bizKey, String eventType, String payload, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        OutboxMessage outboxMessage = OutboxMessage.builder()
                .bizKey(bizKey)
                .eventType(eventType)
                .payload(payload)
                .status(OutboxMessage.NEW)
                .retryCount(0)
                .nextRetryTime(now)
                .createdTime(now)
                .updateTime(now)
                .userId(userId)     // 分片键
                .build();
        outboxMessageMapper.insert(outboxMessage);
    }
}
