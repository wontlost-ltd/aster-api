-- ADR 0051 裁决 R55 / aster-lang-truffle#156 上线前只读扫描（aster-cloud 库："PolicyVersion" 与 "Policy" 当前内容）。
--
-- 背景：
--   (A) R55 改变裸模式名的 Core IR：首字母不是 ASCII 大写、判为枚举成员的名字由 PatName（兜底绑定）改为无参数
--       PatCtor（只匹配同名成员）；Java 侧中文裸名判为绑定时由 PatCtor（永不匹配）改为 PatName。
--   (B) truffle#156 改变运行时作用域：case 体内与外层同名的 Let、与外层局部同名的模式绑定，由「覆盖 / 读外层」
--       改为「只在本分支内遮蔽」。对所有语言生效，包括 en / de。
--   cloud 库不存编译产物（"PolicyVersion" 没有 core_json 列，执行与预览都按源码现编译），升级 aster-lang-ts 后
--   (A) 立即生效；cloud 侧 Core IR 直接执行路径交给 truffle 时 (B) 同样立即生效。
--   与 aster-api 库的 adr0051-r55-scan-pattern-names.sql 规则相同，只是数据源不同；两库都要扫。
--
-- 输出：section = 'A' 的行是 (A) 的命中（kind：non-ascii-initial 首字母为中文 / 天城文 / 变音等非 ASCII 字母；
-- lower-ascii-member 小写 ASCII 名且出现在枚举声明行，Java 拒绝这种声明，一般只会出现在 cloud 库）；
-- section = 'B' 的行是 (B) 的粗筛候选（reason：let-declared-Nx 同名 Let 出现 N 次；pattern-binds-let-name 模式绑定名与某个 Let 同名）。
-- source_table：PolicyVersion（已保存的版本）或 Policy（编辑中的当前内容，含未保存为版本的草稿）；has_alias_set 为 t 的行用了关键词别名，正则可能漏扫。
--
-- 关键词取自语言包（aster-lang-ts src/config/lexicons/*.ts，与 aster-lang-locales 一致）：
--   en Match / When / Let … be / Define … as one of / null；de pruefe / bei / sei … gleich / Definiere … als eines von / null；
--   zh 匹配于 / 当 / 令 … 定义为 / 定义 … 为以下之一 / 空值；hi मिलान / जब / मानें … हो / परिभाषित … इनमें से एक / शून्य。
-- 四种写法对每个版本都扫，不按 locale 列分流（locale 可能为空或不规范，分流会静默漏扫）；pattern_lang 标出命中的写法。
-- 正则只能粗筛：关键词与名字之间须有空格（与 adr0052 扫描相同），不识别跨行、注释与字符串中的伪命中；
-- 命中行按 docs/runbooks/adr0051-r55-pattern-scope-rollout.md §2 离线确认。
--
-- 用法（由人执行，只读事务，不写任何数据）：
--   psql "$CLOUD_DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0051-r55-scan-pattern-names-cloud.sql
BEGIN TRANSACTION READ ONLY;

WITH langs(pattern_lang, match_re, when_re, ctor_re, let_re, one_of_re, null_word) AS (
  VALUES
    ('en', '(?n)^[ \t]*match[ \t]',  '(?n)^[ \t]*when[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*when[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*let[ \t]+([^[:space:]]+)[ \t]+be([ \t]|$)',
           '(?n)^[ \t]*define[ \t]+[^[:space:]]+[ \t]+as[ \t]+one[ \t]+of[ \t]+(.*)$', 'null'),
    ('de', '(?n)^[ \t]*pruefe[ \t]', '(?n)^[ \t]*bei[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*bei[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*sei[ \t]+([^[:space:]]+)[ \t]+gleich([ \t]|$)',
           '(?n)^[ \t]*definiere[ \t]+[^[:space:]]+[ \t]+als[ \t]+eines[ \t]+von[ \t]+(.*)$', 'null'),
    ('zh', '(?n)^[ \t]*匹配于',         '(?n)^[ \t]*当[ \t]+([^[:space:],，(（]+)[ \t]*[,，]',
           '(?n)^[ \t]*当[ \t]+[^[:space:],，(（]+[ \t]*[(（]([^)）]*)[)）]',
           '(?n)^[ \t]*令[ \t]+([^[:space:]]+)[ \t]+定义为',
           '(?n)^[ \t]*定义[ \t]+[^[:space:]]+[ \t]+为以下之一[ \t]*(.*)$', '空值'),
    ('hi', '(?n)^[ \t]*मिलान[ \t]',  '(?n)^[ \t]*जब[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*जब[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*मानें[ \t]+([^[:space:]]+)[ \t]+हो([ \t]|$)',
           '(?n)^[ \t]*परिभाषित[ \t]+[^[:space:]]+[ \t]+इनमें से एक[ \t]+(.*)$', 'शून्य')
),
src AS (
  SELECT 'PolicyVersion' AS source_table, pv.id, pv."policyId" AS policy_id, pv.version::text AS version,
         p."userId" AS user_id, p."teamId" AS team_id, pv.status::text AS status, p."deletedAt" IS NOT NULL AS deleted,
         pv.content, coalesce(pv."aliasSet", '') <> '' AS has_alias_set
  FROM "PolicyVersion" pv
  LEFT JOIN "Policy" p ON p.id = pv."policyId"
  UNION ALL
  SELECT 'Policy', p.id, p.id, 'current', p."userId", p."teamId", NULL, p."deletedAt" IS NOT NULL,
         p.content, false
  FROM "Policy" p
),
-- 模式名（逐个分支）：不以 ASCII 大写、数字、引号、下划线开头，且不是 null 关键词
pattern_names AS (
  SELECT s.source_table, s.id, l.pattern_lang, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.when_re, 'gi') AS m
  WHERE lower(m[1]) <> l.null_word AND m[1] <> '_' AND m[1] !~ '^[A-Z0-9_"''「-]'
),
-- 构造器模式的绑定名（`When Some(x),` 中的 x）
ctor_binds AS (
  SELECT s.source_table, s.id, l.pattern_lang, btrim(b) AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.ctor_re, 'gi') AS m
  CROSS JOIN LATERAL regexp_split_to_table(m[1], '[,，]') AS b
  WHERE btrim(b) NOT IN ('', '_')
),
-- 含 match 关键词的版本中的 Let 声明
lets AS (
  SELECT s.source_table, s.id, l.pattern_lang, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.let_re, 'gi') AS m
  WHERE s.content ~* l.match_re
),
a_hits AS (
  SELECT p.source_table, p.id, p.pattern_lang, p.name,
         CASE WHEN p.name ~ '^[a-z]' THEN 'lower-ascii-member' ELSE 'non-ascii-initial' END AS kind
  FROM pattern_names p
  JOIN src s ON s.id = p.id AND s.source_table = p.source_table
  JOIN langs l ON l.pattern_lang = p.pattern_lang
  WHERE p.name !~ '^[a-z]'
     OR EXISTS (SELECT 1 FROM regexp_matches(s.content, l.one_of_re, 'gi') AS d
                WHERE d[1] ~* ('(^|[ \t,、])'
                               || regexp_replace(p.name, '([.^$|?*+()\[\]{}\\])', '\\\1', 'g')
                               || '([ \t,、.。]|$)'))
),
b_hits AS (
  SELECT source_table, id, pattern_lang, name, 'let-declared-' || count(*) || 'x' AS kind
  FROM lets GROUP BY source_table, id, pattern_lang, name HAVING count(*) >= 2
  UNION
  SELECT b.source_table, b.id, b.pattern_lang, b.name, 'pattern-binds-let-name'
  FROM (SELECT source_table, id, pattern_lang, name FROM pattern_names
        UNION SELECT source_table, id, pattern_lang, name FROM ctor_binds) AS b
  JOIN lets t ON t.source_table = b.source_table AND t.id = b.id AND t.pattern_lang = b.pattern_lang AND t.name = b.name
),
hits AS (
  SELECT DISTINCT 'A' AS section, source_table, id, pattern_lang, kind, name FROM a_hits
  UNION ALL
  SELECT 'B', source_table, id, pattern_lang, kind, name FROM b_hits
)
SELECT h.section, h.source_table, s.id, s.policy_id, s.version, s.user_id, s.team_id, s.status, s.deleted,
       h.pattern_lang, h.kind, h.name, s.has_alias_set
FROM hits h
JOIN src s ON s.source_table = h.source_table AND s.id = h.id
ORDER BY h.section, h.source_table, s.team_id NULLS FIRST, s.policy_id, s.version, h.name;

ROLLBACK;
