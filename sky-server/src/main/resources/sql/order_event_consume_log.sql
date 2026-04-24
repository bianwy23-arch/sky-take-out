CREATE TABLE IF NOT EXISTS order_event_consume_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    consumer_name VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    biz_key VARCHAR(64),
    event_type VARCHAR(32),
    payload TEXT,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_consumer_event (consumer_name, event_id),
    KEY idx_biz_key (biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

