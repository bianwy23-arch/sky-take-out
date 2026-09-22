-- Paid coupon reservation tables. Execute on sky_order_0 only.
-- These tables are single-database tables and must not be created on sky_order_1.

CREATE TABLE IF NOT EXISTS paid_coupon_inventory (
    coupon_id    BIGINT NOT NULL COMMENT '券ID，同时是库存账本主键',
    total        INT    NOT NULL COMMENT '固定总量',
    remaining    INT    NOT NULL COMMENT '尚未正式售出的数量 L',
    next_unit_id BIGINT NOT NULL COMMENT '下一个待分配单位编号，只增不复用',
    PRIMARY KEY (coupon_id),
    CONSTRAINT chk_inventory_remaining CHECK (remaining >= 0 AND remaining <= total),
    CONSTRAINT chk_inventory_next_unit CHECK (next_unit_id >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paid_coupon_available_unit (
    coupon_id BIGINT NOT NULL,
    unit_id   BIGINT NOT NULL,
    PRIMARY KEY (coupon_id, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paid_coupon_reserved_unit (
    request_id VARCHAR(64) NOT NULL,
    coupon_id  BIGINT      NOT NULL,
    unit_id    BIGINT      NOT NULL,
    PRIMARY KEY (request_id, coupon_id, unit_id),
    UNIQUE KEY uk_coupon_unit (coupon_id, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paid_coupon_reservation_batch (
    request_id VARCHAR(64)  NOT NULL,
    user_id    BIGINT       NOT NULL,
    items      TEXT         NOT NULL COMMENT '规范化清单，coupon_id 升序的 JSON 数组，须可完整还原',
    state      VARCHAR(16)  NOT NULL COMMENT 'RESERVED / CLAIMED / RELEASED',
    expires_at DATETIME     NOT NULL,
    PRIMARY KEY (request_id),
    KEY idx_state_expires_request (state, expires_at, request_id),
    CONSTRAINT chk_batch_state CHECK (state IN ('RESERVED', 'CLAIMED', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
