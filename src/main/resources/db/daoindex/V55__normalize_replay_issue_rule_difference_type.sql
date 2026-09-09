UPDATE dii_replay_issue
   SET issue_type = '规则性差异问题'
 WHERE TRIM(issue_type) = '规则差异问题';

UPDATE dii_replay_issue_history
   SET issue_type = '规则性差异问题'
 WHERE TRIM(issue_type) = '规则差异问题';

UPDATE dii_replay_issue_history
   SET before_snapshot = REPLACE(before_snapshot, '"issueType":"规则差异问题"', '"issueType":"规则性差异问题"')
 WHERE before_snapshot LIKE '%"issueType":"规则差异问题"%';

UPDATE dii_replay_issue_history
   SET after_snapshot = REPLACE(after_snapshot, '"issueType":"规则差异问题"', '"issueType":"规则性差异问题"')
 WHERE after_snapshot LIKE '%"issueType":"规则差异问题"%';

UPDATE dii_replay_issue_history
   SET incoming_snapshot = REPLACE(incoming_snapshot, '"issueType":"规则差异问题"', '"issueType":"规则性差异问题"')
 WHERE incoming_snapshot LIKE '%"issueType":"规则差异问题"%';

UPDATE dii_replay_issue_round
   SET incoming_snapshot = REPLACE(incoming_snapshot, '"issueType":"规则差异问题"', '"issueType":"规则性差异问题"')
 WHERE incoming_snapshot LIKE '%"issueType":"规则差异问题"%';
