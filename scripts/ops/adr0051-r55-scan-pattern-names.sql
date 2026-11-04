-- ADR 0051 裁决 R55 / aster-lang-truffle#156 上线前只读扫描（aster-api 库：policy_versions）。
--
-- 背景：
--   (A) R55 改变裸模式名的 Core IR：首字母不是 ASCII 大写、判为枚举成员的名字由 PatName（兜底绑定）改为无参数
--       PatCtor（只匹配同名成员）；Java 侧中文裸名判为绑定时由 PatCtor（永不匹配）改为 PatName。
--   (B) truffle#156 改变运行时作用域：case 体内与外层同名的 Let、与外层局部同名的模式绑定，由「覆盖 / 读外层」
--       改为「只在本分支内遮蔽」。对所有语言生效，包括 en / de。
--   带存储 IR 的版本（core_json 非空，或有 CORE_JSON 产物）编译时优先读存储 IR，得不到 (A) 的新形态；
--   (B) 的运行时语义变化则对所有版本立即生效。启动期重验只做类型检查，发现不了这两类语义变化。
--
-- 输出：section = 'A' 的行是 (A) 的命中（kind：non-ascii-initial 首字母为中文 / 天城文 / 变音等非 ASCII 字母；
-- lower-ascii-member 小写 ASCII 名且出现在枚举声明行，Java 拒绝这种声明，一般只会出现在 cloud 库）；
-- section = 'B' 的行是 (B) 的粗筛候选（reason：let-declared-Nx 同名 Let 出现 N 次；pattern-binds-let-name 模式绑定名与某个 Let 同名）。
-- section = 'C' 的行是 (C) 的粗筛候选（aster-lang-core#231 / aster-lang-ts#273，唯一变严的方向）：调用位置的名字另有同名 Let
-- （call-of-let-name）或写了非函数类型的同名形参（call-of-param-name）；后缀 +same-rule 表示还有同名规则（局部名遮蔽规则，
-- 含 If 分支内 Let 外泄后在分支外调用同名规则）。升级后这类调用报 E001，执行门 enforce 下整个版本返回 422。
-- C 不分语言（pattern_lang 为空），已排除单段 builtin（add / sub / … / await，名单见 aster-lang-core CallTargetBuiltins）与右侧为 lambda 的 Let。
-- C 的 builtin-call-shadowed-by-local（aster-lang-truffle#158，结果可能改变而非报错）：调用名是单段 builtin，且另有同名 Let 或形参；
-- 升级后同名局部若是函数值（lambda、函数类型形参）就调用局部，此前 truffle 一律调用 builtin。
-- stored_core_json / stored_core_artifact 为 t 的行，存储 IR 不会随发版更新；has_alias_set 为 t 的行用了关键词别名，正则可能漏扫。
--
-- 关键词取自语言包（aster-lang-ts src/config/lexicons/*.ts，与 aster-lang-locales 一致）：
--   en Match / When / Let … be / Define … as one of / null；de pruefe / bei / sei … gleich / Definiere … als eines von / null；
--   zh 匹配于 / 当 / 令 … 定义为 / 定义 … 为以下之一 / 空值；hi मिलान / जब / मानें … हो / परिभाषित … इनमें से एक / शून्य。
-- 四种写法对每个版本都扫，不按 locale 列分流（locale 可能为空或不规范，分流会静默漏扫）；pattern_lang 标出命中的写法。
-- 正则只能粗筛：关键词与名字之间须有空格（与 adr0052 扫描相同），不识别跨行、注释与字符串中的伪命中；
-- 命中行按 docs/runbooks/adr0051-r55-pattern-scope-rollout.md §2 离线确认。
--
-- 用法（由人执行，只读事务，不写任何数据）：
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0051-r55-scan-pattern-names.sql
BEGIN TRANSACTION READ ONLY;

WITH langs(pattern_lang, match_re, when_re, ctor_re, let_re, one_of_re, null_word, as_word, rule_head_re, rule_name_re) AS (
  VALUES
    ('en', '(?n)^[ \t]*match[ \t]',  '(?n)^[ \t]*when[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*when[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*let[ \t]+([^[:space:]]+)[ \t]+be([ \t]|$)',
           '(?n)^[ \t]*define[ \t]+[^[:space:]]+[ \t]+as[ \t]+one[ \t]+of[ \t]+(.*)$', 'null',
           'as', '(?n)^[ \t]*rule[ \t]+(.*)$', '(?n)^[ \t]*rule[ \t]+([^[:space:],，(（:：]+)'),
    ('de', '(?n)^[ \t]*pruefe[ \t]', '(?n)^[ \t]*bei[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*bei[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*sei[ \t]+([^[:space:]]+)[ \t]+gleich([ \t]|$)',
           '(?n)^[ \t]*definiere[ \t]+[^[:space:]]+[ \t]+als[ \t]+eines[ \t]+von[ \t]+(.*)$', 'null',
           'als', '(?n)^[ \t]*regel[ \t]+(.*)$', '(?n)^[ \t]*regel[ \t]+([^[:space:],，(（:：]+)'),
    ('zh', '(?n)^[ \t]*匹配于',         '(?n)^[ \t]*当[ \t]+([^[:space:],，(（]+)[ \t]*[,，]',
           '(?n)^[ \t]*当[ \t]+[^[:space:],，(（]+[ \t]*[(（]([^)）]*)[)）]',
           '(?n)^[ \t]*令[ \t]+([^[:space:]]+)[ \t]+定义为',
           '(?n)^[ \t]*定义[ \t]+[^[:space:]]+[ \t]+为以下之一[ \t]*(.*)$', '空值',
           '作为', '(?n)^[ \t]*规则[ \t]+(.*)$', '(?n)^[ \t]*规则[ \t]+([^[:space:],，(（:：]+)'),
    ('hi', '(?n)^[ \t]*मिलान[ \t]',  '(?n)^[ \t]*जब[ \t]+([^[:space:],(]+)[ \t]*,',
           '(?n)^[ \t]*जब[ \t]+[^[:space:],(]+[ \t]*\(([^)]*)\)',
           '(?n)^[ \t]*मानें[ \t]+([^[:space:]]+)[ \t]+हो([ \t]|$)',
           '(?n)^[ \t]*परिभाषित[ \t]+[^[:space:]]+[ \t]+इनमें से एक[ \t]+(.*)$', 'शून्य',
           'रूप में', '(?n)^[ \t]*नियम[ \t]+(.*)$', '(?n)^[ \t]*नियम[ \t]+([^[:space:],，(（:：]+)')
),
src AS (
  SELECT v.id::text AS id, v.policy_id, v.version::text AS version, v.tenant_id, v.status::text AS status,
         v.active, v.locale, v.content,
         v.core_json IS NOT NULL AS stored_core_json,
         EXISTS (SELECT 1 FROM policy_artifacts a
                 WHERE a.policy_version_id = v.id AND a.artifact_type = 'CORE_JSON') AS stored_core_artifact,
         coalesce(v.alias_set, '') <> '' AS has_alias_set
  FROM policy_versions v
),
-- 模式名（逐个分支）：不以 ASCII 大写、数字、引号、下划线开头，且不是 null 关键词
pattern_names AS (
  SELECT s.id, l.pattern_lang, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.when_re, 'gi') AS m
  WHERE lower(m[1]) <> l.null_word AND m[1] <> '_' AND m[1] !~ '^[A-Z0-9_"''「-]'
),
-- 构造器模式的绑定名（`When Some(x),` 中的 x）
ctor_binds AS (
  SELECT s.id, l.pattern_lang, btrim(b) AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.ctor_re, 'gi') AS m
  CROSS JOIN LATERAL regexp_split_to_table(m[1], '[,，]') AS b
  WHERE btrim(b) NOT IN ('', '_')
),
-- 含 match 关键词的版本中的 Let 声明
lets AS (
  SELECT s.id, l.pattern_lang, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.let_re, 'gi') AS m
  WHERE s.content ~* l.match_re
),
a_hits AS (
  SELECT p.id, p.pattern_lang, p.name,
         CASE WHEN p.name ~ '^[a-z]' THEN 'lower-ascii-member' ELSE 'non-ascii-initial' END AS kind
  FROM pattern_names p
  JOIN src s ON s.id = p.id
  JOIN langs l ON l.pattern_lang = p.pattern_lang
  WHERE p.name !~ '^[a-z]'
     OR EXISTS (SELECT 1 FROM regexp_matches(s.content, l.one_of_re, 'gi') AS d
                WHERE d[1] ~* ('(^|[ \t,、])'
                               || regexp_replace(p.name, '([.^$|?*+()\[\]{}\\])', '\\\1', 'g')
                               || '([ \t,、.。]|$)'))
),
b_hits AS (
  SELECT id, pattern_lang, name, 'let-declared-' || count(*) || 'x' AS kind
  FROM lets GROUP BY id, pattern_lang, name HAVING count(*) >= 2
  UNION
  SELECT b.id, b.pattern_lang, b.name, 'pattern-binds-let-name'
  FROM (SELECT id, pattern_lang, name FROM pattern_names UNION SELECT id, pattern_lang, name FROM ctor_binds) AS b
  JOIN lets t ON t.id = b.id AND t.pattern_lang = b.pattern_lang AND t.name = b.name
),
-- (C) 调用位置的名字另有同名 Let / 写了非函数类型的形参（aster-lang-core#231 / aster-lang-ts#273 的 #268 E001）。
-- 先去掉字符串字面量与成员调用（`.length(` 等），再取「名字 + 左括号」；排除单段 builtin：
-- 名单取自 aster-lang-core CallTargetBuiltins.NAMES（镜像 aster-lang-truffle Builtins 中不带点的注册名），
-- 调用这些名字时 truffle 直接分派 builtin，不看同名局部，不报 E001。
src_code AS (
  SELECT s.id, regexp_replace(
           regexp_replace(s.content, '"[^"\n]*"|「[^」\n]*」', '""', 'g'),
           '\.[^][[:space:](),，。.:：「」"''+*/<>=!-]+[ \t]*[(（]', '.(', 'g') AS code
  FROM src s
),
calls AS (
  SELECT DISTINCT c.id, m[1] AS name
  FROM src_code c
  CROSS JOIN LATERAL regexp_matches(c.code, '([^][[:space:](),，。.:：「」"''+*/<>=!-]+)[ \t]*[(（]', 'g') AS m
  WHERE m[1] NOT IN ('add', 'sub', 'mul', 'div', 'intdiv', 'mod', 'eq', 'ne', 'lt', 'lte', 'gt', 'gte',
                     'and', 'or', 'not', 'await')
),
-- 所有 Let（不限含 match 的版本）；右侧为 lambda（`function` / `a function`，关键词未本地化）的不算
plain_lets AS (
  SELECT DISTINCT s.id, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.let_re || '[ \t]*(?:a[ \t]+)?(function)?', 'gi') AS m
  WHERE m[array_length(m, 1)] IS NULL
),
-- 规则头里写了类型、且类型不是函数类型的形参（未写类型的形参不报，见 core#231）
rule_heads AS (
  SELECT s.id, l.as_word, h[1] AS head
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.rule_head_re, 'gi') AS h
),
typed_params AS (
  SELECT DISTINCT r.id, p[1] AS name
  FROM rule_heads r
  CROSS JOIN LATERAL regexp_matches(r.head, '([^[:space:],，:：]+)[ \t]+' || r.as_word || '[ \t]+([^,，:：]+)', 'gi') AS p
  WHERE p[2] !~ '->|\('
),
rule_names AS (
  SELECT DISTINCT s.id, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.rule_name_re, 'gi') AS m
),
-- 与单段 builtin 同名的调用（truffle#158：同名局部是函数值时改为调用局部）
builtin_calls AS (
  SELECT DISTINCT c.id, m[1] AS name
  FROM src_code c
  CROSS JOIN LATERAL regexp_matches(c.code, '([^][[:space:](),，。.:：「」"''+*/<>=!-]+)[ \t]*[(（]', 'g') AS m
  WHERE m[1] IN ('add', 'sub', 'mul', 'div', 'intdiv', 'mod', 'eq', 'ne', 'lt', 'lte', 'gt', 'gte',
                 'and', 'or', 'not', 'await')
),
-- 任意 Let 与任意形参名（含未写类型、函数类型），供 builtin 同名判定
any_lets AS (
  SELECT DISTINCT s.id, m[1] AS name
  FROM src s
  CROSS JOIN langs l
  CROSS JOIN LATERAL regexp_matches(s.content, l.let_re, 'gi') AS m
),
any_params AS (
  SELECT DISTINCT r.id, btrim(regexp_replace(p, '[ \t]+' || r.as_word || '[ \t].*$', '', 'i')) AS name
  FROM rule_heads r
  CROSS JOIN LATERAL regexp_split_to_table(
    regexp_replace(r.head, '^[^[:space:]]+[ \t]+[^[:space:]]+[ \t]+', ''), '[,，]') AS p
),
c_hits AS (
  SELECT c.id, NULL::text AS pattern_lang,
         CASE WHEN EXISTS (SELECT 1 FROM rule_names r WHERE r.id = c.id AND r.name = c.name)
              THEN 'call-of-let-name+same-rule' ELSE 'call-of-let-name' END AS kind, c.name
  FROM calls c JOIN plain_lets t ON t.id = c.id AND t.name = c.name
  UNION
  SELECT c.id, NULL::text,
         CASE WHEN EXISTS (SELECT 1 FROM rule_names r WHERE r.id = c.id AND r.name = c.name)
              THEN 'call-of-param-name+same-rule' ELSE 'call-of-param-name' END, c.name
  FROM calls c JOIN typed_params t ON t.id = c.id AND t.name = c.name
  UNION
  SELECT b.id, NULL::text, 'builtin-call-shadowed-by-local', b.name
  FROM builtin_calls b
  WHERE EXISTS (SELECT 1 FROM any_lets t WHERE t.id = b.id AND t.name = b.name)
     OR EXISTS (SELECT 1 FROM any_params t WHERE t.id = b.id AND t.name = b.name)
),
hits AS (
  SELECT DISTINCT 'A' AS section, id, pattern_lang, kind, name FROM a_hits
  UNION ALL
  SELECT 'B', id, pattern_lang, kind, name FROM b_hits
  UNION ALL
  SELECT 'C', id, pattern_lang, kind, name FROM c_hits
)
SELECT h.section, s.id, s.policy_id, s.version, s.tenant_id, s.status, s.active, s.locale,
       h.pattern_lang, h.kind, h.name, s.stored_core_json, s.stored_core_artifact, s.has_alias_set
FROM hits h
JOIN src s ON s.id = h.id
ORDER BY h.section, s.stored_core_json DESC, s.stored_core_artifact DESC, s.tenant_id NULLS FIRST,
         s.policy_id, s.version, h.name;

ROLLBACK;
