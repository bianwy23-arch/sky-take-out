CREATE TABLE IF NOT EXISTS pay_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '分片键',
    order_no VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_transaction_id (transaction_id),
    UNIQUE KEY uk_order_event (order_no, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '分片键',
    biz_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload TEXT,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-NEW,1-SENT,2-FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    last_error VARCHAR(500),
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_retry_time (status, next_retry_time),
    UNIQUE KEY uk_biz_event (biz_key, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

