-- ═════════════════════════════════════════════════════════════════════════════
-- 源码提交分析 —— 清数据脚本（MySQL / benchmarkdb）
--
-- 目标库：与 01_schema_ddl.sql 同库。无外键约束，TRUNCATE 顺序无关、且重置自增 id。
-- 用途：测试时重置，下次 /scan 会重新全量采集 + 聚合，重建所有生成数据。
-- ═════════════════════════════════════════════════════════════════════════════

-- ── A. 清生成数据（保留 code_repo_config 接入配置与 code_author_alias 别名）──────
--    场景：换扫描参数 / 重跑一次干净的分析。仓库不用重新登记，别名修正不丢。
TRUNCATE TABLE code_file_author_stat;
TRUNCATE TABLE code_repo_author_stat;
TRUNCATE TABLE code_tx_file_map;
TRUNCATE TABLE code_tx_person_stat;
TRUNCATE TABLE code_file_domain;
TRUNCATE TABLE code_domain_person_stat;
TRUNCATE TABLE code_domain_author_stat;

-- 可选：仅把某仓库标记为"需重新全量"（不删数据，下次该仓库走 FULL）
-- UPDATE code_repo_config SET last_sync_commit = NULL WHERE repo_name = '<repoName>';

-- ── B. 完全重置（连仓库配置与别名一起清；需要时手动放开下面注释）─────────────────
--    场景：彻底回到空库。注意：清空后大屏仓库下拉为空，需重新 /scan 登记仓库。
-- TRUNCATE TABLE code_author_alias;
-- TRUNCATE TABLE code_repo_config;
