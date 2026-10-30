-- ADR 0052 上线前只读扫描：找出 policy_versions.content 中的 Set 语句（en / de / zh 三种写法）。
--
-- 背景：升级前 Java 规范化把 `Set x to …`（不区分大小写）、`setze x auf …`、`将 x 设为 …` 改写成 Let 声明，
-- 首次声明用 Set 也能编译；升级后 Set 只能赋值给已有的 Let 绑定，否则报 E101。
--
-- 三条模式对每个版本都扫一遍，不按 locale 列分流：locale 可能为空或写成 zh_CN 等不规范形式，
-- 按列分流会静默漏扫。三种语言的关键词互不重叠，交叉误报的风险很低；pattern_lang 列标出命中的是哪一种写法。
--
-- has_let_same_name 只是估算：文中任何位置有同名 Let 就记为 t，不检查先后顺序与作用域。
-- 处理规则见 docs/runbooks/adr0052-set-writeback-rollout.md §1：f 的行必须改写；t 的行须人工确认 Let 在 Set 之前且在同一作用域链上。
--
-- 用法（由人执行，只读事务，不写任何数据）：
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0052-scan-set-statements.sql
BEGIN TRANSACTION READ ONLY;

WITH patterns(pattern_lang, set_re, let_prefix, let_suffix) AS (
  VALUES
    ('en', '(?n)^[ \t]*set[ \t]+([^[:space:]]+)[ \t]+to[ \t]',     '(?n)^[ \t]*let[ \t]+', '[ \t]+be[ \t]'),
    ('de', '(?n)^[ \t]*setze[ \t]+([^[:space:]]+)[ \t]+auf[ \t]',  '(?n)^[ \t]*sei[ \t]+', '[ \t]+gleich[ \t]'),
    ('zh', '(?n)^[ \t]*将[ \t]+([^[:space:]]+)[ \t]+设为',          '(?n)^[ \t]*令[ \t]+',  '[ \t]+定义为')
),
hits AS (
  SELECT v.id, v.policy_id, v.version, v.tenant_id, v.status, v.active, v.locale, p.pattern_lang,
         m[1] AS set_target,
         v.content ~* (p.let_prefix
                       || regexp_replace(m[1], '([.^$|?*+()\[\]{}\\])', '\\\1', 'g')
                       || p.let_suffix) AS has_let_same_name
  FROM policy_versions v
  CROSS JOIN patterns p
  CROSS JOIN LATERAL regexp_matches(v.content, p.set_re, 'gi') AS m
)
SELECT id, policy_id, version, tenant_id, status, active, locale, pattern_lang, set_target, has_let_same_name
FROM hits
ORDER BY has_let_same_name, tenant_id NULLS FIRST, policy_id, version, set_target;

ROLLBACK;
