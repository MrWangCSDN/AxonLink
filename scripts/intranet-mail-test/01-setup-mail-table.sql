-- ============================================================
-- 协同人邮件发送功能 · 内网数据库准备脚本
-- 执行库：结果库 benchmarkdb（与 dii_replay_issue 同库）
-- 说明：V46 迁移脚本的建表语句 + 可选测试数据
-- ============================================================

-- ── 1. 邮件发送记录表（协同人邮件功能核心表）──────────────────────
--    与 src/main/resources/db/daoindex/V46__dii_replay_issue_mail.sql 完全一致
CREATE TABLE IF NOT EXISTS dii_replay_issue_mail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id BIGINT NOT NULL,
    issue_key VARCHAR(1024) NOT NULL,
    recipient_username VARCHAR(128),
    recipient_email VARCHAR(320) NOT NULL,
    sender_email VARCHAR(320) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sent_at DATETIME DEFAULT NULL,
    failure_message VARCHAR(1000) DEFAULT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_replay_issue_mail_dedupe (replay_issue_id, recipient_email, sender_email, content_hash),
    INDEX idx_replay_issue_mail_issue (replay_issue_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题协同邮件发送记录';

-- ── 2. 依赖表检查（邮件收件人解析依赖，若缺失需补齐）────────────────
-- 用户表（存 email/username/emp_no，收件人邮箱从这里取）
-- CREATE TABLE IF NOT EXISTS ccbs_ai_sys_user (...);  -- 通常已存在，无需重建
-- 交易人员清单（开发负责人/科技负责人收件人从这里取）
-- CREATE TABLE IF NOT EXISTS dii_replay_transaction_person (...);  -- V40，通常已存在

-- ── 3. 可选：预置测试用户邮箱（协同人收件人）──────────────────────
-- 收件人邮箱缺省回退为 <username>@spdbdev.com；若要用真实邮箱，更新对应行：
-- UPDATE ccbs_ai_sys_user SET email = '你的测试邮箱@spdb.com' WHERE username = '<协同人账号>';

-- ── 4. 验证 ─────────────────────────────────────────────────
SELECT '邮件表已就绪' AS check_result
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'dii_replay_issue_mail';
