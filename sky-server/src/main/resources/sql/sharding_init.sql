-- ===========================================================
-- 苍穹外卖 分库分表初始化脚本
-- 拓扑：2 库(sky_order_0, sky_order_1) × 8 表后缀(_0~_7)
-- 执行前提：take_out_v2 数据库已存在（用于复制广播表结构）
-- ===========================================================

-- ===========================================================
-- sky_order_0：分片表 + 所有单表（dish, employee 等）
-- ===========================================================
CREATE DATABASE IF NOT EXISTS sky_order_0 DEFAULT CHARACTER SET utf8mb4;
USE sky_order_0;

-- ---------- 分片表：orders_0 ~ orders_7 ----------
-- orders 表已有 user_id，直接作为分片键，id 改为非自增（由 ShardingSphere 雪花算法生成）
DROP TABLE IF EXISTS orders_0;
CREATE TABLE orders_0 (
    id                      BIGINT          NOT NULL COMMENT '雪花ID，由ShardingSphere生成',
    number                  VARCHAR(50)     NOT NULL COMMENT '订单号（含userId后缀，支持反解路由）',
    status                  INT             NOT NULL COMMENT '1待付款 2待接单 3已接单 4派送中 5已完成 6已取消',
    user_id                 BIGINT          NOT NULL COMMENT '分片键',
    address_book_id         BIGINT          NOT NULL,
    order_time              DATETIME        NOT NULL,
    checkout_time           DATETIME        DEFAULT NULL,
    pay_method              INT             DEFAULT NULL COMMENT '1微信 2支付宝',
    pay_status              TINYINT         NOT NULL DEFAULT 0 COMMENT '0未付款 1已付款 2退款',
    amount                  DECIMAL(10, 2)  NOT NULL,
    remark                  VARCHAR(100)    DEFAULT NULL,
    phone                   VARCHAR(11)     DEFAULT NULL,
    address                 VARCHAR(255)    DEFAULT NULL,
    user_name               VARCHAR(32)     DEFAULT NULL,
    consignee               VARCHAR(32)     DEFAULT NULL,
    cancel_reason           VARCHAR(255)    DEFAULT NULL,
    rejection_reason        VARCHAR(255)    DEFAULT NULL,
    cancel_time             DATETIME        DEFAULT NULL,
    estimated_delivery_time DATETIME        DEFAULT NULL,
    delivery_status         TINYINT         NOT NULL DEFAULT 1 COMMENT '0立即送达 1选择具体时间',
    delivery_time           DATETIME        DEFAULT NULL,
    pack_amount             INT             DEFAULT NULL,
    tableware_number        INT             DEFAULT NULL,
    tableware_status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0按餐量提供 1选择具体数量',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_number (number),
    KEY idx_status (status),
    KEY idx_status_order_time (status, order_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表（分片）';

CREATE TABLE orders_1 LIKE orders_0;
CREATE TABLE orders_2 LIKE orders_0;
CREATE TABLE orders_3 LIKE orders_0;
CREATE TABLE orders_4 LIKE orders_0;
CREATE TABLE orders_5 LIKE orders_0;
CREATE TABLE orders_6 LIKE orders_0;
CREATE TABLE orders_7 LIKE orders_0;

-- ---------- 分片表：order_detail_0 ~ order_detail_7 ----------
-- 新增 user_id 字段作为分片键，与 orders 绑定路由到同一分片
DROP TABLE IF EXISTS order_detail_0;
CREATE TABLE order_detail_0 (
    id          BIGINT          NOT NULL COMMENT '雪花ID',
    name        VARCHAR(32)     NOT NULL,
    order_id    BIGINT          NOT NULL COMMENT '关联 orders.id',
    user_id     BIGINT          NOT NULL COMMENT '分片键（与orders.user_id相同）',
    dish_id     BIGINT          DEFAULT NULL,
    setmeal_id  BIGINT          DEFAULT NULL,
    dish_flavor VARCHAR(50)     DEFAULT NULL,
    number      INT             NOT NULL,
    amount      DECIMAL(10, 2)  NOT NULL,
    image       VARCHAR(255)    DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表（分片）';

CREATE TABLE order_detail_1 LIKE order_detail_0;
CREATE TABLE order_detail_2 LIKE order_detail_0;
CREATE TABLE order_detail_3 LIKE order_detail_0;
CREATE TABLE order_detail_4 LIKE order_detail_0;
CREATE TABLE order_detail_5 LIKE order_detail_0;
CREATE TABLE order_detail_6 LIKE order_detail_0;
CREATE TABLE order_detail_7 LIKE order_detail_0;

-- ---------- 分片表：pay_transaction_0 ~ pay_transaction_7 ----------
DROP TABLE IF EXISTS pay_transaction_0;
CREATE TABLE pay_transaction_0 (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    order_no       VARCHAR(64)  NOT NULL,
    transaction_id VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(32)  NOT NULL,
    created_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id        BIGINT       NOT NULL COMMENT '分片键',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_id (transaction_id),
    UNIQUE KEY uk_order_event (order_no, event_type),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付事务表（分片，幂等防重）';

CREATE TABLE pay_transaction_1 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_2 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_3 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_4 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_5 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_6 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_7 LIKE pay_transaction_0;

-- ---------- 分片表：outbox_message_0 ~ outbox_message_7 ----------
DROP TABLE IF EXISTS outbox_message_0;
CREATE TABLE outbox_message_0 (
    id              BIGINT       NOT NULL COMMENT '雪花ID',
    biz_key         VARCHAR(64)  NOT NULL COMMENT '业务键（订单号）',
    event_type      VARCHAR(32)  NOT NULL COMMENT 'ORDER_CREATED|ORDER_PAID|ORDER_CANCELLED',
    payload         TEXT         DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-NEW 1-SENT 2-FAILED',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    last_error      VARCHAR(500) DEFAULT NULL,
    created_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    user_id         BIGINT       NOT NULL COMMENT '分片键',
    PRIMARY KEY (id),
    KEY idx_status_retry_time (status, next_retry_time),
    UNIQUE KEY uk_biz_event (biz_key, event_type),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Outbox 消息表（分片）';

CREATE TABLE outbox_message_1 LIKE outbox_message_0;
CREATE TABLE outbox_message_2 LIKE outbox_message_0;
CREATE TABLE outbox_message_3 LIKE outbox_message_0;
CREATE TABLE outbox_message_4 LIKE outbox_message_0;
CREATE TABLE outbox_message_5 LIKE outbox_message_0;
CREATE TABLE outbox_message_6 LIKE outbox_message_0;
CREATE TABLE outbox_message_7 LIKE outbox_message_0;

-- ---------- 单表：从 take_out_v2 复制结构（路由到 ds_0）----------
-- 执行本脚本前请确保 take_out_v2 数据库存在
CREATE TABLE IF NOT EXISTS dish             LIKE take_out_v2.dish;
CREATE TABLE IF NOT EXISTS category        LIKE take_out_v2.category;
CREATE TABLE IF NOT EXISTS setmeal         LIKE take_out_v2.setmeal;
CREATE TABLE IF NOT EXISTS setmeal_dish    LIKE take_out_v2.setmeal_dish;
CREATE TABLE IF NOT EXISTS dish_flavor     LIKE take_out_v2.dish_flavor;
CREATE TABLE IF NOT EXISTS employee        LIKE take_out_v2.employee;
CREATE TABLE IF NOT EXISTS user            LIKE take_out_v2.user;
CREATE TABLE IF NOT EXISTS address_book    LIKE take_out_v2.address_book;
CREATE TABLE IF NOT EXISTS shopping_cart   LIKE take_out_v2.shopping_cart;

-- 消费日志：单表，存 ds_0，用于 MQ 幂等
CREATE TABLE IF NOT EXISTS order_event_consume_log    LIKE take_out_v2.order_event_consume_log;
CREATE TABLE IF NOT EXISTS logistics_event_consume_log LIKE take_out_v2.logistics_event_consume_log;

-- ---------- 迁移初始数据（广播表/单表）从 take_out_v2 到 sky_order_0 ----------
INSERT INTO dish             SELECT * FROM take_out_v2.dish;
INSERT INTO category         SELECT * FROM take_out_v2.category;
INSERT INTO setmeal           SELECT * FROM take_out_v2.setmeal;
INSERT INTO setmeal_dish      SELECT * FROM take_out_v2.setmeal_dish;
INSERT INTO dish_flavor       SELECT * FROM take_out_v2.dish_flavor;
INSERT INTO employee          SELECT * FROM take_out_v2.employee;
INSERT INTO user              SELECT * FROM take_out_v2.user;
INSERT INTO address_book      SELECT * FROM take_out_v2.address_book;
INSERT INTO shopping_cart     SELECT * FROM take_out_v2.shopping_cart;


-- ===========================================================
-- sky_order_1：仅包含分片表（不需要单表）
-- ===========================================================
CREATE DATABASE IF NOT EXISTS sky_order_1 DEFAULT CHARACTER SET utf8mb4;
USE sky_order_1;

-- ---------- 分片表：orders_0 ~ orders_7 ----------
DROP TABLE IF EXISTS orders_0;
CREATE TABLE orders_0 (
    id                      BIGINT          NOT NULL,
    number                  VARCHAR(50)     NOT NULL,
    status                  INT             NOT NULL,
    user_id                 BIGINT          NOT NULL,
    address_book_id         BIGINT          NOT NULL,
    order_time              DATETIME        NOT NULL,
    checkout_time           DATETIME        DEFAULT NULL,
    pay_method              INT             DEFAULT NULL,
    pay_status              TINYINT         NOT NULL DEFAULT 0,
    amount                  DECIMAL(10, 2)  NOT NULL,
    remark                  VARCHAR(100)    DEFAULT NULL,
    phone                   VARCHAR(11)     DEFAULT NULL,
    address                 VARCHAR(255)    DEFAULT NULL,
    user_name               VARCHAR(32)     DEFAULT NULL,
    consignee               VARCHAR(32)     DEFAULT NULL,
    cancel_reason           VARCHAR(255)    DEFAULT NULL,
    rejection_reason        VARCHAR(255)    DEFAULT NULL,
    cancel_time             DATETIME        DEFAULT NULL,
    estimated_delivery_time DATETIME        DEFAULT NULL,
    delivery_status         TINYINT         NOT NULL DEFAULT 1,
    delivery_time           DATETIME        DEFAULT NULL,
    pack_amount             INT             DEFAULT NULL,
    tableware_number        INT             DEFAULT NULL,
    tableware_status        TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_number (number),
    KEY idx_status (status),
    KEY idx_status_order_time (status, order_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE orders_1 LIKE orders_0;
CREATE TABLE orders_2 LIKE orders_0;
CREATE TABLE orders_3 LIKE orders_0;
CREATE TABLE orders_4 LIKE orders_0;
CREATE TABLE orders_5 LIKE orders_0;
CREATE TABLE orders_6 LIKE orders_0;
CREATE TABLE orders_7 LIKE orders_0;

-- ---------- 分片表：order_detail_0 ~ order_detail_7 ----------
DROP TABLE IF EXISTS order_detail_0;
CREATE TABLE order_detail_0 (
    id          BIGINT          NOT NULL,
    name        VARCHAR(32)     NOT NULL,
    order_id    BIGINT          NOT NULL,
    user_id     BIGINT          NOT NULL,
    dish_id     BIGINT          DEFAULT NULL,
    setmeal_id  BIGINT          DEFAULT NULL,
    dish_flavor VARCHAR(50)     DEFAULT NULL,
    number      INT             NOT NULL,
    amount      DECIMAL(10, 2)  NOT NULL,
    image       VARCHAR(255)    DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_detail_1 LIKE order_detail_0;
CREATE TABLE order_detail_2 LIKE order_detail_0;
CREATE TABLE order_detail_3 LIKE order_detail_0;
CREATE TABLE order_detail_4 LIKE order_detail_0;
CREATE TABLE order_detail_5 LIKE order_detail_0;
CREATE TABLE order_detail_6 LIKE order_detail_0;
CREATE TABLE order_detail_7 LIKE order_detail_0;

-- ---------- 分片表：pay_transaction_0 ~ pay_transaction_7 ----------
DROP TABLE IF EXISTS pay_transaction_0;
CREATE TABLE pay_transaction_0 (
    id             BIGINT       NOT NULL,
    order_no       VARCHAR(64)  NOT NULL,
    transaction_id VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(32)  NOT NULL,
    created_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id        BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_id (transaction_id),
    UNIQUE KEY uk_order_event (order_no, event_type),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE pay_transaction_1 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_2 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_3 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_4 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_5 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_6 LIKE pay_transaction_0;
CREATE TABLE pay_transaction_7 LIKE pay_transaction_0;

-- ---------- 分片表：outbox_message_0 ~ outbox_message_7 ----------
DROP TABLE IF EXISTS outbox_message_0;
CREATE TABLE outbox_message_0 (
    id              BIGINT       NOT NULL,
    biz_key         VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    payload         TEXT         DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 0,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    last_error      VARCHAR(500) DEFAULT NULL,
    created_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    user_id         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_retry_time (status, next_retry_time),
    UNIQUE KEY uk_biz_event (biz_key, event_type),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_message_1 LIKE outbox_message_0;
CREATE TABLE outbox_message_2 LIKE outbox_message_0;
CREATE TABLE outbox_message_3 LIKE outbox_message_0;
CREATE TABLE outbox_message_4 LIKE outbox_message_0;
CREATE TABLE outbox_message_5 LIKE outbox_message_0;
CREATE TABLE outbox_message_6 LIKE outbox_message_0;
CREATE TABLE outbox_message_7 LIKE outbox_message_0;
