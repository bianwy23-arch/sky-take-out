-- 优惠券活动表（单表，路由到 ds_0）
CREATE TABLE IF NOT EXISTS coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '券名称，如"满30减5"',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '1=满减 2=折扣',
    threshold DECIMAL(10,2) COMMENT '使用门槛金额',
    discount DECIMAL(10,2) NOT NULL COMMENT '优惠金额',
    total_count INT NOT NULL COMMENT '发放总量',
    remaining_count INT NOT NULL COMMENT '剩余数量',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=进行中 0=已结束',
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户领券记录表（单表，路由到 ds_0）
CREATE TABLE IF NOT EXISTS user_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用',
    grab_time DATETIME NOT NULL,
    UNIQUE KEY uk_user_coupon (user_id, coupon_id),
    KEY idx_coupon_id (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 抢券死信消息表
CREATE TABLE IF NOT EXISTS coupon_grab_dead_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64),
    coupon_id BIGINT,
    user_id BIGINT,
    payload TEXT NOT NULL,
    error_type VARCHAR(64),
    error_message VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=REPLAYED 2=COMPENSATED 3=IGNORED 4=UNKNOWN 5=COMPENSATING',
    replay_count INT NOT NULL DEFAULT 0,
    handle_action VARCHAR(64),
    dead_time DATETIME NOT NULL,
    handle_time DATETIME,
    created_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_id (status, id),
    KEY idx_user_coupon (user_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Redis 回滚失败补偿表：用于幂等重试 coupon_grab_rollback.lua
CREATE TABLE IF NOT EXISTS coupon_redis_compensation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=SUCCESS 2=FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    last_error VARCHAR(500),
    created_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_coupon_user_source (coupon_id, user_id, source),
    KEY idx_status_retry_time (status, next_retry_time),
    KEY idx_coupon_user (coupon_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 应用层订单 ID 号段表：用于 Snowflake 遇到大幅时钟回拨时兜底
CREATE TABLE IF NOT EXISTS id_segment (
    biz_type    VARCHAR(64) PRIMARY KEY,
    max_id      BIGINT      NOT NULL,
    step        INT         NOT NULL,
    update_time DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO id_segment(biz_type, max_id, step, update_time)
VALUES ('orders', 1000000000000000, 1000, NOW())
ON DUPLICATE KEY UPDATE biz_type = biz_type;
