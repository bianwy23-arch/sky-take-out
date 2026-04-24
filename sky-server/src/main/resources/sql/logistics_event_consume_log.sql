CREATE TABLE IF NOT EXISTS logistics_event_consume_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    logistics_status TINYINT NOT NULL,
    payload TEXT,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

