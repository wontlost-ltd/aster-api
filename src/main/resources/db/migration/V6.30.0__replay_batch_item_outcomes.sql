-- 为 replay_batch_item 增加基线/目标四态结论码并回填存量行（ADR 0043 §3）
ALTER TABLE replay_batch_item ADD COLUMN base_outcome VARCHAR(20);
ALTER TABLE replay_batch_item ADD COLUMN target_outcome VARCHAR(20);
ALTER TABLE replay_batch_item ADD COLUMN base_legacy BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE replay_batch_item ADD CONSTRAINT replay_batch_item_base_outcome_ck CHECK (base_outcome IS NULL OR base_outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE','INDETERMINATE','ERROR'));
ALTER TABLE replay_batch_item ADD CONSTRAINT replay_batch_item_target_outcome_ck CHECK (target_outcome IS NULL OR target_outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE','INDETERMINATE','ERROR'));
UPDATE replay_batch_item SET base_outcome = CASE WHEN base_approved THEN 'ALLOW' ELSE 'DENY' END, base_legacy = true WHERE base_outcome IS NULL;
UPDATE replay_batch_item SET target_outcome = CASE WHEN target_approved THEN 'ALLOW' ELSE 'DENY' END WHERE target_outcome IS NULL AND success = true;
