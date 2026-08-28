ALTER TABLE dii_replay_issue
    ADD COLUMN review_status VARCHAR(16) DEFAULT NULL COMMENT '无需处理审核状态：PENDING/APPROVED' AFTER cooperation_person_real_name,
    ADD COLUMN reviewer_username VARCHAR(128) DEFAULT NULL COMMENT '审核人用户名' AFTER review_status,
    ADD COLUMN reviewer_real_name VARCHAR(128) DEFAULT NULL COMMENT '审核人中文名' AFTER reviewer_username,
    ADD COLUMN reviewed_at DATETIME DEFAULT NULL COMMENT '审核时间' AFTER reviewer_real_name,
    ADD INDEX idx_replay_issue_review_status (review_status, group_name);

ALTER TABLE dii_replay_issue_history
    ADD COLUMN review_status VARCHAR(16) DEFAULT NULL COMMENT '操作后审核状态' AFTER cooperation_person_real_name,
    ADD COLUMN reviewer_username VARCHAR(128) DEFAULT NULL COMMENT '操作后审核人用户名' AFTER review_status,
    ADD COLUMN reviewer_real_name VARCHAR(128) DEFAULT NULL COMMENT '操作后审核人中文名' AFTER reviewer_username,
    ADD COLUMN reviewed_at DATETIME DEFAULT NULL COMMENT '操作后审核时间' AFTER reviewer_real_name;
