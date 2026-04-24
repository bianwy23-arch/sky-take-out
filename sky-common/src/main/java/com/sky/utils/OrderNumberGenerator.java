package com.sky.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单号生成器
 *
 * 格式：{yyyyMMddHHmmss}{6位userId取模}{4位序列号}
 * 示例：202603111430520001230001
 * 长度：24 字符
 *
 * 设计目标：支持从订单号中反解 userId 后缀，用于分库分表的精确路由。
 */
public class OrderNumberGenerator {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 生成订单号
     * @param userId 当前下单用户 ID
     * @return 24 位订单号
     */
    public static String generate(Long userId) {
        String timePart = LocalDateTime.now().format(FMT);
        String userPart = String.format("%06d", userId % 1_000_000);
        String seqPart  = String.format("%04d", SEQUENCE.getAndIncrement() % 10_000);
        return timePart + userPart + seqPart;
    }

    /**
     * 从订单号反解 userId 后缀（用于分库分表路由）
     * 取位：第 14~19 位（0-indexed），共 6 位
     *
     * @param orderNumber 24 位订单号
     * @return userId % 1_000_000 的值
     */
    public static long extractUserIdSuffix(String orderNumber) {
        if (orderNumber == null || orderNumber.length() < 20) {
            throw new IllegalArgumentException("Invalid order number: " + orderNumber);
        }
        return Long.parseLong(orderNumber.substring(14, 20));
    }
}
