-- Manual migration. Apply before enabling dii.replay.issue-reopen.enabled.
-- Permanent facts intentionally have no foreign keys to deletable current projections.
CREATE TABLE IF NOT EXISTS dii_replay_reopen_counter (
 issue_key_hash CHAR(64) PRIMARY KEY, issue_key VARCHAR(1024) NOT NULL, next_sequence BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS dii_replay_reopen_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, issue_key_hash CHAR(64) NOT NULL, issue_key VARCHAR(1024) NOT NULL,
 occurrence_no BIGINT NOT NULL, replay_issue_id BIGINT, source_round_id BIGINT NOT NULL,
 replay_type VARCHAR(16), issue_domain VARCHAR(64), group_name VARCHAR(64), batch_name VARCHAR(128),
 transaction_code VARCHAR(128), issue_description MEDIUMTEXT, occurred_at DATETIME NOT NULL, release_at DATETIME,
 default_owner_username VARCHAR(128), default_owner_name VARCHAR(128), current_owner_username VARCHAR(128), current_owner_name VARCHAR(128),
 owner_resolution VARCHAR(32) NOT NULL, developer_snapshot TEXT, version BIGINT NOT NULL DEFAULT 1, pending_appeal_id BIGINT,
 UNIQUE KEY uq_reopen_source (source_round_id,issue_key_hash), UNIQUE KEY uq_reopen_occurrence (issue_key_hash,occurrence_no),
 INDEX idx_reopen_batch (batch_name), INDEX idx_reopen_owner (current_owner_username), INDEX idx_reopen_domain (issue_domain)
);
CREATE TABLE IF NOT EXISTS dii_replay_reopen_appeal (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(128) NOT NULL UNIQUE, request_fingerprint CHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, issue_domain VARCHAR(64) NOT NULL,
 applicant_username VARCHAR(128) NOT NULL, applicant_name VARCHAR(128), suggested_owner_username VARCHAR(128), suggested_owner_name VARCHAR(128),
 final_owner_username VARCHAR(128), final_owner_name VARCHAR(128), leader_username VARCHAR(128) NOT NULL, leader_name VARCHAR(128),
 reason VARCHAR(1000) NOT NULL, evidence VARCHAR(4000) NOT NULL, evidence_hash CHAR(64) NOT NULL, submitted_at DATETIME NOT NULL,
 decided_at DATETIME, decision_reason VARCHAR(1000), closed_at DATETIME, close_reason VARCHAR(1000), version BIGINT NOT NULL DEFAULT 1,
 INDEX idx_reopen_appeal_leader (leader_username,status), INDEX idx_reopen_appeal_applicant (applicant_username)
);
CREATE TABLE IF NOT EXISTS dii_replay_reopen_appeal_item (
 appeal_id BIGINT NOT NULL, event_id BIGINT NOT NULL, expected_version BIGINT NOT NULL,
 before_owner_username VARCHAR(128), before_owner_name VARCHAR(128), PRIMARY KEY (appeal_id,event_id), INDEX idx_reopen_item_event (event_id)
);
CREATE TABLE IF NOT EXISTS dii_replay_reopen_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT, appeal_id BIGINT, issue_domain VARCHAR(64), action VARCHAR(64) NOT NULL,
 operator_username VARCHAR(128), operator_name VARCHAR(128), occurred_at DATETIME NOT NULL, reason VARCHAR(1000), evidence VARCHAR(4000),
 from_owner_username VARCHAR(128), from_owner_name VARCHAR(128), to_owner_username VARCHAR(128), to_owner_name VARCHAR(128), metadata TEXT,
 INDEX idx_reopen_audit_event (event_id,id), INDEX idx_reopen_audit_appeal (appeal_id)
);
CREATE TABLE IF NOT EXISTS dii_replay_reopen_policy (
 issue_domain VARCHAR(64) PRIMARY KEY, leader_usernames TEXT NOT NULL, primary_leader_username VARCHAR(128) NOT NULL,
 version BIGINT NOT NULL, updated_by VARCHAR(128) NOT NULL, updated_at DATETIME NOT NULL
);
