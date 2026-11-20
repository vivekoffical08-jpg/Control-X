CREATE TABLE IF NOT EXISTS command_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id VARCHAR(128) NOT NULL,
    command_text TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    result_message TEXT,
    latency_ms INTEGER UNSIGNED,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_command_history_device_created (device_id, created_at),
    INDEX idx_command_history_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
