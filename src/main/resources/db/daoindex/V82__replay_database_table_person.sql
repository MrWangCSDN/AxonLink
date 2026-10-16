CREATE TABLE IF NOT EXISTS dii_replay_database_table_person (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name VARCHAR(256) NOT NULL,
    domain_name VARCHAR(128) NOT NULL,
    table_comment VARCHAR(1000),
    developer_display VARCHAR(1000),
    developer_usernames VARCHAR(1000),
    developer_emp_nos VARCHAR(1000),
    technology_owner_display VARCHAR(1000),
    technology_owner_usernames VARCHAR(1000),
    technology_owner_emp_nos VARCHAR(1000),
    imported_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_database_table_person_name UNIQUE (table_name),
    INDEX idx_replay_database_table_person_domain (domain_name)
);
