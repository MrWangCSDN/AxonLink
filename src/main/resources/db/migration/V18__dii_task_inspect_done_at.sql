-- ─────────────────────────────────────────────────────────────────────────────
-- V18：dii_analysis_task 加 inspect_done_at（EXPLAIN 巡检完成时间）
--
-- 背景：任务从 created_at 到 markDone 全程 RUNNING，markDone 在
--   item EXPLAIN + item LLM + pool EXPLAIN + pool LLM 全跑完才登记。
--   接入池 LLM 后 LLM 阶段动辄几十分钟~几小时，导致「耗时」= 含 LLM 的总时长
--   （如 193m），不反映真正的巡检（EXPLAIN）耗时。
--
-- 方案：新增 inspect_done_at，在「两阶段 EXPLAIN（item + pool）跑完、LLM 之前」
--   登记。前端「耗时」改用 inspect_done_at - created_at（纯 EXPLAIN 时长）；
--   LLM 回填 / 单条重跑都在此时间点之后，不再计入耗时。
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_task
    ADD COLUMN inspect_done_at DATETIME
        COMMENT 'EXPLAIN 巡检阶段完成时间（item+pool 两阶段 EXPLAIN 跑完、LLM 之前登记）；耗时口径';
