CREATE TABLE IF NOT EXISTS dii_replay_transaction_person (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain VARCHAR(64) NOT NULL,
    old_transaction_code VARCHAR(64) NOT NULL,
    old_transaction_name VARCHAR(256),
    developer VARCHAR(512),
    developer_usernames VARCHAR(512),
    bank_owner VARCHAR(512),
    bank_owner_emp_nos VARCHAR(512),
    imported_at DATETIME NOT NULL,
    UNIQUE KEY uq_replay_old_transaction_code (old_transaction_code),
    INDEX idx_replay_person_domain (domain),
    INDEX idx_replay_person_name (old_transaction_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量交易人员清单';
