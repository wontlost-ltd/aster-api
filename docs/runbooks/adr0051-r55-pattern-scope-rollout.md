# ADR 0051 R55 / truffle#156 上线 Runbook：裸模式名的 IR 形态与块作用域遮蔽

本文是 ADR 0051 §15.2 裁决 R55 与 ADR 0052 §6（truffle#156 部分）的执行手册。所有命令由人执行；扫描只读，不写任何数据。

涉及制品：aster-lang-core（R55；规则调用检查 core#231）、aster-lang-truffle（#156 作用域、R55 的 ASCII 判定）、aster-lang-ts（R55 + 解释器；规则调用检查 ts#273）。
扫描的 C 类服务于 core#231 / ts#273（#268），与 R55 / #156 同一道门禁，哪一组先进 main 都要先扫。
实现 PR：aster-cloud/aster-lang-core#230、aster-cloud/aster-lang-truffle#158、aster-cloud/aster-lang-ts#274；样本：aster-cloud/aster-lang-test#183。

## ⚠️ 发版门禁（先读）

**运行一次发版列车 = 升级生产 aster-api。** 列车的最后一层自动部署 aster-api：`deploy.yml` 构建镜像后，k3s 的 image-pin PR 自动合并，ArgoCD 滚动更新。中间没有人工的「升级 aster-api」步骤（同 adr0052 runbook）。
core#230、truffle#158 合入 main 后，下一次列车会把它们带上，**不论这次列车是为什么而发**。因此：

**core#231 与 ts#273 已在 main，本门禁现已生效：在两个库的扫描清零（或命中全部处理）之前，不得运行任何列车。**

1. 运行任何列车之前，先跑 §1 的两份只读扫描（aster-api 库与 cloud 库）。
2. A、B、C 三类结果都为空：直接发版。
3. 有命中：先按 §2 由作者改写或重新发布；或者在新工具链下重跑命中版本，确认语义符合作者本意；**确认之后才**运行列车。
   C 类命中（会变成编译错误）必须先由作者修好，不能只做确认。

## 0. 变更摘要（语义变化）

1. **truffle#156，对所有语言生效（含 en / de）**：
   - case 体内 `Let` 一个外层已有的名字，现在只遮蔽外层，离开分支后外层值不变；此前覆盖外层。
   - 模式绑定与外层局部同名时（`When other,`、`When Some(x),`），分支体现在读绑定；此前读外层值。
   - 嵌套 Scope 内的 `Let` 不再写到外层 Scope。
   - 块内没有同名 `Let` 时，`Set` 仍写回外层（ADR 0052 不变）。
   - 局部名（Let、形参、模式绑定）与某枚举成员同名时，读该名字得到局部值（ADR 0051 §6.2 LOCAL 优先，与 TS 一致）；此前 truffle 先查枚举成员表，得到枚举值。
     §1 的扫描不覆盖这一项，由离线精确确认覆盖。
   - 调用目标与单段 builtin（`add`、`gt` 等）同名、且有同名局部时：局部是函数值（lambda、函数类型形参）就调用局部，否则仍调用 builtin。
     此前 truffle 一律调用 builtin，函数类型形参 `gt(1, 2)` 得 false、乘法闭包 `add(2, 3)` 得 5（与 aster-lang-ts#273 同语义）。
     由 §1 的 C 类 `builtin-call-shadowed-by-local` 粗筛。
2. **R55，裸模式名（无括号）的 IR 形态**。首字母不是 ASCII 大写、判为枚举成员的名字产出无参数 `PatCtor`（只匹配同名成员）；判为绑定的产出 `PatName`。
   - zh / hi：中文、天城文成员此前在 TS 与 truffle 上都是兜底绑定，会吞掉其后所有分支（hi 两引擎都如此）。现在只匹配同名成员。
     Java 侧的中文绑定（`当 其他，`）此前永不匹配，分支体读该名字还报 E101；现在作兜底绑定。
   - de 以变音字母开头的成员（`bei Übel,`）：Java 本来就对；TS 此前是兜底，现在修好（只影响 cloud / TS 侧）。
     truffle 的 `PatName` 改为只看 ASCII 大写，`bei Ärger,`（不是成员）在两引擎都是绑定；此前 truffle 判为变体、不匹配。
   - 小写 ASCII 枚举成员（`Define Color as one of red, Green`）：TS 接受这种声明，Java 拒绝。TS 侧 `When red,` 由兜底改为只匹配 `red`。
   - 首字母为 ASCII 大写的名字 IR 不变。
3. **aster-lang-core#231 / aster-lang-ts#273（#268），唯一变严的方向**：调用一个既不是 builtin、也不是规则函数值的非函数局部名，现在报 E001。
   - 例：`Rule main given score as Int … Return score(1, 2).`；`Let total be 1. Return total(n).`。
   - 局部名遮蔽同名规则：有同名规则 `score` 时也报。If 分支内 `Let score be 1.` 外泄到分支外后，再调用 `score(n)` 同样报。
   - 执行门 enforce 下，命中的版本整体返回 422。
   - 不报的情形：单段 builtin（`add`、`sub` 等，名单见 aster-lang-core `CallTargetBuiltins`，与 truffle `Builtins` 同步）、未写类型的形参、函数类型的形参、右侧为 lambda 的 Let。
   - 由 §1 的 C 类扫描检查。
4. `RESOLVER_VERSION` 不递增（R55 的理由）。toolchainId 的 Java 侧 `core=<版本>` 随发版变化，会触发缓存失效与启动期重验。

## 为什么扫描必须在发版前做

- `PolicyCompiler.compile(versionId)` 的读取顺序：`policy_artifacts` 的 CORE_JSON 产物 → `policy_versions.core_json` → 从 `content` 现编译。
  **带存储 IR 的版本拿不到 R55 的新形态**，仍按旧 IR 执行。与此同时，truffle#156 的运行时语义对所有版本**立即**生效。
  结果是同一份源码因有无存储 IR 而表现不同。
- 启动期重验只做类型检查，发现不了这两类语义变化。
- 回放（ADR 0030）在新运行时下重放旧决策时，命中版本可能得出不同结果。
- 生产库 2026-10-29 重建后存量很小，命中概率低，但仍要扫过才能发版。

## 1. 发版前：只读扫描

两个库都要扫：

```bash
# aster-api 库（policy_versions + policy_artifacts）
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0051-r55-scan-pattern-names.sql
# aster-cloud 库（"PolicyVersion" 已保存的版本 + "Policy" 编辑中的当前内容；cloud 不存编译产物）
psql "$CLOUD_DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0051-r55-scan-pattern-names-cloud.sql
```

输出的 `section` 与 `kind` 列：

| section | kind | 含义 |
|---|---|---|
| A | `non-ascii-initial` | 首字母为中文 / 天城文 / 变音等非 ASCII 字母的裸模式名（R55） |
| A | `lower-ascii-member` | 小写 ASCII 裸模式名，且出现在枚举声明行（R55，一般只在 cloud 库） |
| B | `let-declared-Nx` | 含 match 的版本中同名 Let 出现 N 次（#156 候选） |
| B | `pattern-binds-let-name` | 模式绑定名（含构造器模式的字段名）与某个 Let 同名（#156 候选） |
| C | `call-of-let-name` | 调用位置的名字另有同名 Let（右侧不是 lambda），且不是单段 builtin（core#231 E001 候选） |
| C | `call-of-param-name` | 调用位置的名字另有写了非函数类型的同名形参（core#231 E001 候选） |
| C | `builtin-call-shadowed-by-local` | 调用名是单段 builtin，且另有同名 Let 或形参（truffle#158：同名局部是函数值时改为调用局部，结果可能改变；局部不是函数值时结果不变） |
| C | `…+same-rule` | 同时有同名规则：局部名遮蔽规则，包括 If 分支内 Let 外泄后在分支外调用同名规则 |

- `stored_core_json` 或 `stored_core_artifact` 为 `t` 的行：存储 IR 不会随发版更新，优先处理。
- `has_alias_set` 为 `t`：用了关键词别名，正则可能漏扫，须离线确认。

正则只能粗筛，有三类遗漏：
- 关键词与名字之间须有空格；
- 只认语言包里的关键词；
- 不认 match 之外的遮蔽，比如 case 体内 `Let` 一个**形参**。

所以扫描为空也不等于绝对无影响，只说明粗筛规则下没有命中。如需精确确认，见下节。

### 离线精确确认（命中行，或存量不大时对全部版本）

1. 只读导出命中行的 `content`（以及 `locale`、`alias_set`）。
2. 用旧、新两版引擎分别编译（core 1.0.38 与含 core#230 的版本，aster-lang-test `CoreIrFingerprintCli` 的同款流程），比较 Core IR。有差异的就是 A 类的确切命中。
3. 遍历新版 IR，找出 case 体或 Scope 内 `Let` / 模式绑定名与外层局部（含形参）同名的情形：这是 B 类的确切命中。
4. 对确切命中，用旧、新两版 truffle 执行作者提供的代表输入（或已有执行日志的输入），确认结果是否变化。
5. C 类 `builtin-call-shadowed-by-local` 命中：确认同名局部是不是函数值（lambda 或函数类型形参）。是的话用旧、新两版 truffle 执行代表输入确认结果，与第 4 步同。
6. C 类其余命中：用旧、新两版 core（含 core#231）对命中版本跑名称解析 + 类型检查，比较诊断。新增的 E001 `expected a function, got '<name>' of type …` 即为确切命中；
   C 类正则不区分规则，同名 Let 在别的规则里也会记为候选，以第 6 步的结果为准。

## 2. 有命中时：改写或确认后发版

- 结果符合作者本意（新语义正是想要的）：记入 §5 的上线记录即可，无需改写。
- 作者依赖旧行为：
  - 例如在 case 体里 `Let x` 想改外层 x，应改为 `Set x to …`（ADR 0052）。
  - 例如 hi / zh 成员分支原先被当兜底，应补一条兜底分支，或改用想要的成员名。
  - 改好后走正常审批，**发布新版本**。新版本在新工具链下编译，存储 IR 随之按新形态生成，无需迁移工具。
- 带存储 IR、又不能改写的命中版本：作者重新发布同一份源码，得到新的存储 IR；或在 §3 发版后按 §4 回滚处理。
- C 类确切命中：作者给局部变量改名，或改为调用规则本身（删掉同名局部 / 不在 If 分支里声明同名 Let），发布新版本。不修好就发版，该版本会在执行门返回 422。
- 全部处理完后重跑 §1，确认 A / B 类没有未经确认的命中、C 类没有确切命中，再运行列车。

## 3. 发版（= 运行发版列车）

发版顺序：locales → core → truffle（有源码变更）→ ts npm → test。之后按 §5 检查。

## 4. 回滚

aster-api 把 core / truffle 的 pin 回退到上一生态版本，重新部署。回退后旧 IR 形态与旧作用域语义恢复。§2 中已改写发布的版本在新旧工具链下都能编译，无需回退。

## 5. 上线后验证与记录

- `/api/admin/revalidation/invalid` 没有新增条目（R55 只会消除中文绑定的 E101，不应新增错误）。
- 重验 invalid 列表与执行日志中没有 C 类的 E001（`expected a function, got … of type …`），执行门没有因此返回 422。
- 对 §1 中确认过的命中版本，各执行一次代表输入，结果与 §2 的确认一致。
- 记录：扫描时间、两个库各自的命中数、处理方式、列车 trainId。
