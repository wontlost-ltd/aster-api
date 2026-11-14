-- ADR 0054 §11 D1（R66，#464）：aster-cloud 把策略默认版本部署为 aster-api 的活跃版本。只做 expand，不改历史行语义。

-- 版本冻结的领域词汇（cloud 部署时的 DomainVocabulary JSON）：编译与 source envelope 读同一快照；NULL = 无词汇（存量版本 envelope 不变）。
ALTER TABLE policy_versions ADD COLUMN vocabulary_json JSONB;
-- 外部来源版本标识（cloud 版本 id）；同租户唯一，部署重放据此幂等。
ALTER TABLE policy_versions ADD COLUMN external_ref VARCHAR(100);
CREATE UNIQUE INDEX policy_versions_tenant_external_ref_uq
    ON policy_versions (tenant_id, external_ref) WHERE external_ref IS NOT NULL;

-- 策略目录的外部归属（cloud 策略 id）与最近一次被接受的部署代际：generation 更小的请求按 stale_generation 拒绝。
ALTER TABLE policy_catalog ADD COLUMN external_ref VARCHAR(100);
ALTER TABLE policy_catalog ADD COLUMN external_generation BIGINT;
CREATE INDEX policy_catalog_tenant_external_ref_ix
    ON policy_catalog (tenant_id, external_ref) WHERE external_ref IS NOT NULL;
