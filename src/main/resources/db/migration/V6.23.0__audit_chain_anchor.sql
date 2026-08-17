-- 审计链尾锚定（2026-08-17 安全审计，第 3 层）
--
-- 层 1（权限）与层 2（触发器）解决「不能改」，但都无法解决一类攻击：
--
--   ★**删除链尾**：哈希链是单向的——每条记录指向前驱。删掉最后 N 条后，
--     剩余部分**依然自洽**，verifyChain 返回 valid。攻击者只要把不利记录之后的
--     全部记录一并删除，就能在不触发任何告警的情况下抹掉它们。
--     （删中间记录会断链、能检出；删尾部不会。）
--
--   ★**整链重写**：拿到写权限后按新公式重算整条链，链自洽，无从判断。
--
-- 两者的共同点：**仅凭表内数据无法判断**。必须有一个独立于该表的、
-- 记录「某时刻链尾长什么样」的外部证据。这就是锚点。
--
-- 本表存放周期性快照：(租户, 当时最大 id, 当时链尾 hash, 记录数)。
-- 验证时对比：
--   - 当前 max(id) < 锚点 id            → 链尾被删
--   - 当前该 id 处的 hash ≠ 锚点 hash   → 该点及之前被重写
--   - 当前记录数 < 锚点记录数           → 有记录被删
--
-- 锚点表自身同样是 append-only（复用 V6.22.0 的守卫函数）：否则攻击者
-- 改锚点即可掩盖删除。锚点的可信度最终取决于它被复制到**数据库之外**
-- （对象存储 / 外部时间戳服务 / 另一套凭据的库）——本表是那条链路的**源**，
-- 导出由 AuditChainAnchorService 负责。

CREATE TABLE IF NOT EXISTS audit_chain_anchors (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    -- 锚定时刻该租户链上的最大记录 id（BIGSERIAL = 真实追加顺序）
    anchored_max_id BIGINT       NOT NULL,
    -- 该 id 记录的 current_hash，即当时的链尾
    anchored_hash   VARCHAR(64)  NOT NULL,
    -- 该租户当时的记录总数：即便攻击者伪造出同样的 max_id 与 hash，
    -- 也很难同时对上计数（多一个可被独立核对的维度）
    anchored_count  BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 导出到外部存储的时间；NULL = 尚未外部化（此时锚点与审计表同库同权限，
    -- 防护力有限，仅能防「只改审计表不改锚点表」的粗糙攻击）
    exported_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_audit_anchors_tenant_created
    ON audit_chain_anchors (tenant_id, created_at DESC);

-- 每个租户每个 max_id 只锚一次，避免重复锚点干扰「取最新锚点」的判定
CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_anchors_tenant_maxid
    ON audit_chain_anchors (tenant_id, anchored_max_id);

COMMENT ON TABLE audit_chain_anchors IS
    '审计链尾锚点：周期性记录 (租户, 最大id, 链尾hash, 记录数)，用于检出「删除链尾」与「整链重写」——这两类攻击仅凭 audit_logs 表内数据无法发现。';

-- 锚点表同样 append-only：允许 INSERT 与「标记已导出」的 UPDATE，
-- 但禁止改写锚定内容本身、禁止 DELETE。
CREATE OR REPLACE FUNCTION reject_audit_anchor_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- DELETE 与 audit_logs 同口径：仅在显式声明的保留期清理事务内放行。
    -- 锚点是派生数据，随审计记录一起到期清理是合理的；但常规路径必须删不掉，
    -- 否则攻击者删完链尾再删锚点即可完全掩盖。
    IF (TG_OP = 'DELETE') THEN
        IF current_setting('aster.audit_retention_job', true) IS DISTINCT FROM 'on' THEN
            RAISE EXCEPTION
                'audit_chain_anchors 为 append-only：禁止 DELETE（锚点 id=%）。'
                '删除锚点会掩盖审计链被截断的事实；保留期清理须显式声明 '
                'SET LOCAL aster.audit_retention_job = ''on''。',
                OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;

    -- UPDATE 只允许把 exported_at 从 NULL 置为非 NULL（标记已外部化）。
    -- 锚定内容（tenant/max_id/hash/count）与创建时间一律不可变。
    IF (TG_OP = 'UPDATE') THEN
        IF NEW.tenant_id       IS DISTINCT FROM OLD.tenant_id
        OR NEW.anchored_max_id IS DISTINCT FROM OLD.anchored_max_id
        OR NEW.anchored_hash   IS DISTINCT FROM OLD.anchored_hash
        OR NEW.anchored_count  IS DISTINCT FROM OLD.anchored_count
        OR NEW.created_at      IS DISTINCT FROM OLD.created_at
        OR OLD.exported_at     IS NOT NULL THEN
            RAISE EXCEPTION
                'audit_chain_anchors：锚定内容不可变（锚点 id=%）。'
                '仅允许把尚未导出的锚点的 exported_at 由 NULL 置为非 NULL。',
                OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_anchors_append_only ON audit_chain_anchors;
CREATE TRIGGER trg_audit_anchors_append_only
    BEFORE UPDATE OR DELETE ON audit_chain_anchors
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_anchor_mutation();

DROP TRIGGER IF EXISTS trg_audit_anchors_no_truncate ON audit_chain_anchors;
CREATE TRIGGER trg_audit_anchors_no_truncate
    BEFORE TRUNCATE ON audit_chain_anchors
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_audit_log_truncate();
