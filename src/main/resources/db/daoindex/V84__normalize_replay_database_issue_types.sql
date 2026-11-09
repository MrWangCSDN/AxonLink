UPDATE dii_replay_database_issue
   SET issue_type = CASE TRIM(issue_type)
       WHEN '代码问题' THEN '修改代码'
       WHEN '规则性差异问题' THEN '规则性差异'
       WHEN '迁移问题' THEN '修改迁移规则'
       WHEN '防腐问题' THEN '其他问题'
       WHEN '需规问题' THEN '其他问题'
       WHEN '平台问题' THEN '其他问题'
       WHEN '合理差异' THEN '其他问题'
       ELSE issue_type
   END,
       data_snapshot = CASE
       WHEN data_snapshot IS NULL THEN data_snapshot
       ELSE REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(data_snapshot,
           '"issueType":"代码问题"', '"issueType":"修改代码"'),
           '"issueType":"规则性差异问题"', '"issueType":"规则性差异"'),
           '"issueType":"迁移问题"', '"issueType":"修改迁移规则"'),
           '"issueType":"防腐问题"', '"issueType":"其他问题"'),
           '"issueType":"需规问题"', '"issueType":"其他问题"'),
           '"issueType":"平台问题"', '"issueType":"其他问题"'),
           '"issueType":"合理差异"', '"issueType":"其他问题"')
       END
 WHERE TRIM(issue_type) IN ('代码问题','规则性差异问题','迁移问题','防腐问题','需规问题','平台问题','合理差异');
