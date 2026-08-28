-- ============================================================
-- V47: 回放清单导入 · 汇总信息表
-- 记录 Excel「汇总信息」页签的内容：
--   批次、领域、覆盖528接口、发送交易量、
--   交易状态分类统计（6 子项：528成功/CCBS失败、CCBS失败明细、528失败/CCBS成功、
--     二者均失败响应码一致、二者均失败响应码不一致、二者均成功、响应码忽略）
--   以及大项内的 接口成功率、比对通过率
-- 与 dii_replay_import_round 通过 round_code 关联（一轮导入可能多行，按批次×领域）
-- ============================================================
CREATE TABLE IF NOT EXISTS dii_replay_issue_summary (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_code                 VARCHAR(64)  NOT NULL COMMENT '导入轮次号(同 dii_replay_import_round.round_code)',
    batch_no                   VARCHAR(128)          COMMENT '批次',
    domain                     VARCHAR(64)           COMMENT '领域',
    covered_interface_count    BIGINT                COMMENT '覆盖528接口',
    sent_transaction_count     BIGINT                COMMENT '发送交易量',
    -- ── 交易状态分类统计（6 子项） ─────────────────────────
    c528_success_ccbs_fail     BIGINT                COMMENT '528成功/CCBS失败',
    ccbs_failure_detail        BIGINT                COMMENT 'CCBS失败明细',
    c528_fail_ccbs_success     BIGINT                COMMENT '528失败/CCBS成功',
    both_fail_same_code        BIGINT                COMMENT '二者均失败响应码一致',
    both_fail_diff_code        BIGINT                COMMENT '二者均失败响应码不一致',
    both_success               BIGINT                COMMENT '二者均成功',
    code_ignored               BIGINT                COMMENT '响应码忽略',
    -- ── 大项内补充指标 ─────────────────────────────────────
    success_rate               DECIMAL(10,4)         COMMENT '成功率/接口成功率(%)',
    match_pass_rate            DECIMAL(10,4)         COMMENT '比对通过率(%)',
    raw_json                   JSON                  COMMENT '页签原始解析结果(兜底)',
    imported_at                DATETIME     NOT NULL,
    updated_at                 DATETIME     NOT NULL,
    INDEX idx_replay_summary_round  (round_code),
    INDEX idx_replay_summary_domain (domain, batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放清单导入汇总信息(Excel 汇总信息页签)';
