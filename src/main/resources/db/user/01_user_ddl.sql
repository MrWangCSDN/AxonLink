-- ═════════════════════════════════════════════════════════════════════════════
-- 用户管理 —— 全量建表 DDL（MySQL / benchmarkdb）
--
-- 目标库：与 code-commit 同库。
-- 应用方式：本工程未接 Flyway 运行时，需 DBA / 部署脚本手工执行本文件。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
-- 内容：用户基本信息维护，不做权限控制。
-- ═════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS ccbs_ai_sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(50)  NOT NULL                COMMENT '用户名（唯一）',
    real_name   VARCHAR(50)                          COMMENT '真实姓名',
    emp_no      VARCHAR(50)                          COMMENT '工号',
    email       VARCHAR(100)                         COMMENT '邮箱',
    phone       VARCHAR(20)                          COMMENT '手机号',
    department  VARCHAR(100)                         COMMENT '部门',
    status      TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0-禁用 1-启用',
    remark      VARCHAR(500)                         COMMENT '备注',
    creator_id  BIGINT                                COMMENT '创建人 ID',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updater_id  BIGINT                                COMMENT '更新人 ID',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ccbs_ai_sys_user_username (username),
    KEY idx_ccbs_ai_sys_user_emp_no (emp_no),
    KEY idx_ccbs_ai_sys_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（ccbs-ai 模块）';
