UPDATE dii_replay_issue_history h
   SET h.context_round_id = (
       SELECT ir.round_id
         FROM dii_replay_issue_round ir
         JOIN dii_replay_import_round r ON r.id = ir.round_id
        WHERE ir.replay_issue_id = h.replay_issue_id
          AND r.imported_at <= h.operation_at
        ORDER BY r.imported_at DESC, r.id DESC
        LIMIT 1
   )
 WHERE h.operation_type = '人工保存'
   AND h.context_round_id IS NULL
   AND EXISTS (
       SELECT 1
         FROM dii_replay_issue_round ir
         JOIN dii_replay_import_round r ON r.id = ir.round_id
        WHERE ir.replay_issue_id = h.replay_issue_id
          AND r.imported_at <= h.operation_at
   );
