-- Normalize the visible domain to the page-derived group name.
-- The result database is not managed by Flyway; execute this script manually.
UPDATE dii_replay_issue
   SET domain = group_name
 WHERE group_name IS NOT NULL
   AND TRIM(group_name) <> ''
   AND (domain IS NULL OR domain <> group_name);
