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
    UNIQUE KEY uk_user_coupon (user_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
