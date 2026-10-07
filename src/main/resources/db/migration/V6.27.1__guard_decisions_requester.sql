-- Action Guard（ADR 0040 §4.2）：记录提交决策请求的已解析身份，供审批时的四眼校验使用。
-- requested_by 为请求方用户（RequestIdentityResolver.performedBy），requested_api_key_id 为已验证的 API key；
-- 审批人与二者任一相同即拒绝（segregation_of_duties）。本迁移之前写入的行两列为 NULL，只按 principal_id 比对。
ALTER TABLE guard_decisions
    ADD COLUMN requested_by VARCHAR(255),
    ADD COLUMN requested_api_key_id VARCHAR(255);
