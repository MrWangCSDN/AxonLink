-- Manual migration. Apply before using the authorized count transfer endpoint.
-- A committed row represents one fully completed, idempotent transfer.
CREATE TABLE IF NOT EXISTS dii_replay_reopen_count_transfer (
 request_id VARCHAR(36) PRIMARY KEY,
 request_fingerprint CHAR(64) NOT NULL,
 operator_username VARCHAR(128) NOT NULL,
 operator_name VARCHAR(128),
 source_owner_username VARCHAR(128),
 source_owner_name VARCHAR(128),
 target_owner_username VARCHAR(128) NOT NULL,
 target_owner_name VARCHAR(128),
 transfer_count INT NOT NULL,
 replay_type VARCHAR(16) NOT NULL,
 batch_names TEXT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 event_ids LONGTEXT NOT NULL,
 occurred_at DATETIME NOT NULL,
 INDEX idx_reopen_count_transfer_operator (operator_username, occurred_at)
) ENGINE=InnoDB;
