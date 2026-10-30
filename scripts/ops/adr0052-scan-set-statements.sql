-- ADR 0052 上线前只读扫描：找出 policy_versions.content 中的 Set 语句（en / de / zh）。
--
-- 背景：升级前 Java 规范化把 `Set x to …`（不区分大小写）、`setze x auf …`、`将 x 设为 …` 改写成 Let 声明，
-- 首次声明用 Set 也能编译；升级后 Set 只能赋值给已有的 Let 绑定，否则报 E101。
-- has_let_same_name = false 的行就是高风险候选（同名 Let 在文中任何位置都不存在）。
-- 这是估算：作用域与顺序以 report 模式重验的诊断为准（见 docs/runbooks/adr0052-set-writeback-rollout.md）。
--
-- 用法（由人执行，只读事务，不写任何数据）：
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0052-scan-set-statements.sql
BEGIN TRANSACTION READ ONLY;

WITH patterns(lang, set_re, let_prefix, let_suffix) AS (
  VALUES
    ('en', '(?n)^[ \t]*set[ \t]+([^[:space:]]+)[ \t]+to[ \t]',     '(?n)^[ \t]*let[ \t]+', '[ \t]+be[ \t]'),
    ('de', '(?n)^[ \t]*setze[ \t]+([^[:space:]]+)[ \t]+auf[ \t]',  '(?n)^[ \t]*sei[ \t]+', '[ \t]+gleich[ \t]'),
    ('zh', '(?n)^[ \t]*将[ \t]+([^[:space:]]+)[ \t]+设为',          '(?n)^[ \t]*令[ \t]+',  '[ \t]+定义为')
),
versions AS (
  SELECT id, policy_id, version, tenant_id, status, active, content,
         split_part(lower(coalesce(nullif(locale, ''), 'en-US')), '-', 1) AS lang
  FROM policy_versions
),
hits AS (
  SELECT v.id, v.policy_id, v.version, v.tenant_id, v.status, v.active, v.lang,
         m[1] AS set_target,
         v.content ~* (p.let_prefix
                       || regexp_replace(m[1], '([.^$|?*+()\[\]{}\\])', '\\\1', 'g')
                       || p.let_suffix) AS has_let_same_name
  FROM versions v
  JOIN patterns p ON p.lang = v.lang
  CROSS JOIN LATERAL regexp_matches(v.content, p.set_re, 'gi') AS m
)
SELECT id, policy_id, version, tenant_id, status, active, lang, set_target, has_let_same_name
FROM hits
ORDER BY has_let_same_name, tenant_id NULLS FIRST, policy_id, version, set_target;

ROLLBACK;
