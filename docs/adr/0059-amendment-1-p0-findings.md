# ADR 0059 修订 1：P0 实测结论（跨 CLI 比较口径、值的字节形态、IR 漂移判据、体积上限、资源、导出检查）

- 状态：**ACCEPTED**（2026-11-16，用户授权按推荐方案裁决）
- 日期：2026-11-17（P0 实测）；裁决日期沿用用户授权日 2026-11-16
- 修订对象：[ADR 0059](0059-executable-policy-export.md) §0、§3、§4.1、§4.2、§4.3、§5.1、§5.3、§9、§13
- 证据：实施计划 `docs/superpowers/plans/2026-11-16-adr0059-p0-p1.md` 的「P0 结果」一节（wontlost-ltd/aster-api#554），含命令、机器、JDK / Node 版本、产物哈希与原始数字；spike 代码在 aster-lang-truffle / aster-lang-ts / aster-lang-test 的 `spike/adr0059-p0` 分支（不合并）
- 相关：aster-cloud/aster-lang-core#279（不再把 ANTLR 工具链发布为运行时依赖）、aster-cloud/aster-lang-ts#339（ts 与 core 的 Core IR origin 列对齐）、ADR 0055 §5 / §6（规范 IR 字节、`ir --full` 门的列投影）、ADR 0031（错误码单一来源）
- 被测版本：生态 1.0.49（aster-lang-truffle / core / runtime 位于 tag `v1.0.49`）、aster-lang-ts 3.2.0（tag `v3.2.0`）、aster-api `cdf2c990` 之前的 main（`773a92a6`，P0 期间 ADR 0059 尚未合并，被测代码与之无关）

本修订共六项，编号 A0–A5，沿用实施计划中的编号（与 ADR 0053 / 0056 用「A1、A2」给整份修订编号不同，这里的 A0–A5 是本修订内的条目号）。下文的「修订条款」是对 ADR 0059 相应段落的替换或增补，其余段落不变。

---

## A0：跨 CLI 一致性的比较口径（§4.1、§13）

**P0 证据。** 计划把「同一包、两个 CLI、stdout 逐字节相同（含 `meta`）」作为一致性门，而 §4.1 的信封 `M` 含 `engine`（`aster-lang-ts-interpreter` / `aster-lang-truffle`）与 `cliVersion`（各自版本）。这两个字段按定义两侧不同，全量逐字节比较不可能通过。这是 ADR 与计划自身的矛盾，在 P0 结果的审查中发现。

**修订条款。** §4.1 信封段末尾增补：「两个 CLI 的 `run` / `verify` / `inspect` stdout 在把 `meta.engine`、`meta.cliVersion` 的值分别替换为字面量 `"<ENGINE>"`、`"<CLI_VERSION>"` 后逐字节相同；`version` 子命令不参与跨 CLI 比较。归一函数随包格式真相源发布（aster-lang-test `conformance/package-v1/normalize-stdout.mjs` 与其向量），JVM 侧按同一向量另实现。」§13 P1 行「同一包两 CLI 结果一致」按此口径理解。

## A1：值的字节形态（§4.1、§5.3）

**P0 证据。**
- tier1-parity 语料 241 个样本、960 个 case：ts 解释器结果树与本地 aster-api `/evaluate-json` 的 `result` 树 943 个相同，9 个预期错误两侧都失败，7 个 Enum 只差 ts 多出的 `"__type":"?"` 键，1 个是测量工具的产物。补充探针覆盖 Map 输出、Long、Map / List / Double 入参、Decimal、Ok、None，树形全部相同。
- 两套现有规范 JSON（ts `canonicalJson`、core `CanonicalJson.canonicalJson`）在空 TypeContext 下对非整数数字和超出 ±(2⁵³−1) 的整数报 `NON_INTEGER_NUMBER`。960 个 case 中 17 个（全部 Double 结果，含 Data 内的 Double 字段）以及 Long 探针无法序列化。
- `/evaluate-json` 的 `result` 是 Jackson 输出：键按插入序，Double 用 Java 写法（如 `1.4285714285714284E14`，ts 为 `142857142857142.84`）。任何跨语言规范形式都不可能与它逐字节相同。
- DateTime 与 PII 值：语料与探针都没有产出，树形未取得。

**修订条款。**
- §4.1「`<V>` 的序列化与 aster-api `/evaluate-json` 结果里的值逐字节相同（§5.3 的共享编解码）」改为：「`<V>` 是求值结果的 JSON 树（与 aster-api `/evaluate-json` 的 `result` 树相等）按包格式的**值规范 JSON** 序列化：对象键按 Unicode 码点升序（与 ADR 0055 §5 规范 JSON 同一比较器，不是 UTF-16 码元序；向量含非 BMP 字符的键）；无空白；字符串按 RFC 8785 §3.2.2.2 转义；整数（含 Long，不限 2⁵³）输出精确十进制；非整数 Double 按 RFC 8785 §3.2.2.3（ECMAScript `Number::toString`，最短往返表示）；`-0` 输出 `0`。逐类型树形写在 aster-lang-test `conformance/package-v1/VALUE-FORMS.md`，两侧跑同一组向量（含固定种子生成的 10 000 个随机 double）。Long 超出 2⁵³ 时消费方需用大数 JSON 解析器读取。」
- 同段增补：「DateTime 与 PII 值的树形在 VALUE-FORMS 补齐前不在承诺内（见 A5 的 E738）。」
- §5.3 JRE 段「以『同一批探针在 aster-api 与 jre CLI 上输出逐字节相同』的黄金测试守住」改为「以『aster-api `/evaluate-json` 的 `result` 经同一值规范 JSON 序列化后，与 jre CLI 的 `<V>` 逐字节相同』的黄金测试守住」。
- 输入侧增补（§4.1 `run` 行的「按 `schema/input.json` 校验输入」细化）：`--input` 必须是对象，键恰为入口全部形参名（逐字节匹配，不做平台 `NamedContextMapper` 的变音模糊匹配），缺键与多余键都是退出码 2；0 参入口只接受 `{}`；JSON 整数超出 ±(2⁵³−1) 是退出码 2（P0 探针：输入 `9007199254740993`，ts 解析后得 …992，平台 Jackson 保留精度得 …994）；Decimal 只接受字符串。P0 实测 959 个 0–5 参 case 中，按形参名的对象与位置数组在平台上结果全部相同，CLI 的严格约定是平台约定的子集。

## A2：IR 漂移判据（§3、§4.2、§5.3）

**P0 证据。** 用 aster-api 生产编译序列（`PolicyCompiler`：aliasSet 空 → `InProcessCnlParser.parse`，非空 → `parseWithUserAliases`；词汇 → `VocabularyScopes.build(VocabularyLoader.loadFromMap(…))`；`CoreIrJson.serialize`）与 ts `compile(…).coreJson` 对 682 个编译请求逐字节比较：344 个对照（含 27 个带词汇）、336 个别名改写（en-US / zh-CN / de-DE，其中 24 个同时带词汇）、2 个手写。**逐字节相同 0 个；去掉 `origin.start.col` / `origin.end.col` 后 682 个全部相同，其他差异 0 个。** 每个样本都有差异（例如 `Module.end`：ts 报末行 col 1，Java 报 col 6）；带别名时 Java 的 col 指别名还原为规范拼写后的文本，ts 指原文，差异更多。按原 §4.2「B 本地编译后字节与 `coreIr.sha256` 不同 → `PKG_IR_DRIFT`」，Node 上的 B 包会 100% 失败。col 是诊断位置，不参与求值；ADR 0055 §6 的 `ir --full` 门已把删除这两个字段作为全局比较投影。

**修订条款。**
- §3 manifest 的 `coreIr` 增加字段 `positionFreeSha256`：
  ```json
  "coreIr": { "version": 2, "sha256": "<规范字节>", "positionFreeSha256": "<去列投影>", "toolchainId": "…" }
  ```
  定义（结构化，唯一规范算法）：把规范 Core IR v2 字节按 Core IR v2 schema 严格读入（ts `readCoreIr` / core `CoreIrJson.read`），只删除每个 `origin` 对象的 `start.col` 与 `end.col`，再按 ADR 0055 §5 的规范序列化规则（core `aster.core.canonical.CanonicalJson` / ts `canonicalJson`：对象键按 Unicode 码点升序——两侧实现分别是 `CODE_POINT_ORDER` 与 `compareByCodePoint`，不是 UTF-16 码元序——数组保序、`null` 显式输出、无空白；Core IR v2 中只有字符串与安全整数，Double / Long 字面量都以字符串承载，所以这两个序列化器可以直接处理投影后的树）序列化，取 SHA-256。不得用全局文本替换实现。schema 要求 `col` 为必填整数，缺失或为 `null` 的 IR 在严格读入阶段即失败（B 包按 `PKG_IR_DRIFT` 处理）。与 aster-lang-test `scripts/lib/ir-byte-parity.mjs` 的比较投影同一定义，包格式真相源附向量 `vectors/position-free-hash.json`。
- `coreIr.sha256` 不变，仍是平台 IR 的精确字节哈希：用于审计、`meta.coreIrSha256`，以及 A 包的交叉校验——`compiled/core.json` 字节的 SHA-256 必须等于它，否则退出码 3、`PKG_INTEGRITY`。
- §0「五个决定」表「执行的是平台产物」一行中「B 在本地重编译，**字节必须与 `manifest.coreIr.sha256` 相同**才运行」改为「B 在本地重编译，**去掉位置列后的字节必须与 `manifest.coreIr.positionFreeSha256` 相同**才运行」。
- §4.1 命令表 `aster run` 行「B 本地编译并比对 `coreIr.sha256`」改为「B 本地编译并比对 `coreIr.positionFreeSha256`；A 另校验 `compiled/core.json` 与 `coreIr.sha256`」。
- §4.2 首句「B 在本地编译后字节与 `coreIr.sha256` 不同 → 退出码 4、错误码 `PKG_IR_DRIFT`」改为：「B 在本地编译后，**两个 CLI 都**按 `positionFreeSha256` 比对，不等 → 退出码 4、错误码 `PKG_IR_DRIFT`。」理由段末尾增补：「判据是去掉位置列后的 IR 字节；位置列是诊断信息，不参与求值。两个 CLI 用同一判据（而不是 JVM 用精确哈希、Node 用投影哈希），保证同一 B 包在两侧的信封在 A0 归一后逐字节相同。」
- §5.3 Node 段 ①「IR 逐字节相同（A 由构造保证，B 由 §4.2 保证）」改为「IR 相同：A 执行平台的精确字节；B 去掉位置列后逐字节相同（§4.2）」。
- B 包本地编译（`run`、`verify --recompile`、`compile <pkg>`）与 `compile <file> --alias-set` 都按 aster-api `UserAliasValidator` 的规则校验 aliasSet（两侧相同；JVM 的 `parseWithUserAliases` 本就在编译内校验，ts 移植同一规则），别名按 `AliasOverlayLexicon.wrap` 的合并语义叠加。P0 发现 ts `compile` 不校验 aliasSet、且 `resolve-policy-types.ts` 对同一 kind 是替换而非合并（当前四个内置词法表两侧都没有 base 别名，所以暂无可观测差异）。
- 列对齐另由 aster-cloud/aster-lang-ts#339 追踪；对齐后投影哈希依然成立，包格式不需回改。

## A3：体积上限（§9）

**P0 证据。**
- JVM fat jar（shadow 9.2.2，stock `eclipse-temurin:25-jre`）：全部依赖 54.14 MiB；排除 `io.quarkus` 后 26.97 MiB；再排除 ANTLR 工具链（`org.antlr:antlr4`、`com.ibm.icu`）后 11.45 MiB；加 picocli 估算 CLI 为 11.84 MiB。truffle 主代码不引用 quarkus；runtime 里引用 quarkus 的两个类（`IdempotencyKeyManager`、`InMemoryWorkflowRuntime`）在 jar 内无人引用。ANTLR 工具链经 aster-lang-core 的 Gradle `antlr` 配置泄漏进已发布 POM（aster-cloud/aster-lang-core#279）；排除后 CNL 编译路径照常可用。冷启动中位数 878 ms。
- Node 单文件 `aster.mjs`（esbuild 0.24.2，minify，含四个内置词法表、ajv、fflate）：632 130 字节（0.60 MiB），冷启动中位数约 210 ms。

**修订条款。** §9 表中 node-a 由 8 MiB 改为 **1 MiB**（`ceil(0.60 × 1.5)`），jre-a 由 48 MiB 改为 **18 MiB**（`ceil(11.84 × 1.5)`，前提是 CLI 运行时闭包排除 `io.quarkus`、`org.antlr:antlr4`、`com.ibm.icu`）。依据列改为「P0 实测（本修订 A3）」。§9 表依据列中「可能的 quarkus-core」一并删去。§9 末段增补：「P0 测的是 spike 产物；P1 首次产出正式 `aster.mjs` / `aster-cli.jar` 时，若超出上表，可按同一规则以正式产物重定一次基线并写回本节，此后改动须修订 ADR。」

## A4：资源（§4.3）

**P0 证据。**
- Node Worker 缺省栈（约 4 MiB）下，线性递归在 3 000–4 000 层就出现 V8 `Maximum call stack size exceeded`，§4.3 的 `maxCallDepth = 10 000` 无从生效；`resourceLimits.stackSizeMb = 16` 时 10 000 层成功、20 000 层溢出；`= 32` 时 20 000 层成功。
- `worker.terminate()` 在 1 000 ms 超时下 1006–1008 ms 结束同步求值，进程正常退出。
- JVM 在缺省线程栈上 1 000 层成功、2 000 层 `Resource exhausted: Stack overflow`；`-Xss8m` 下 20 000 层仍溢出。看门狗 `close(true)` 在 1 000 ms 超时下 1 056 ms 取消。

**修订条款。** §4.3 增补：「Node 求值 Worker 以 `resourceLimits: { stackSizeMb: 32 }` 启动，使 `maxCallDepth = 10 000` 先于 JS 栈溢出生效。JVM CLI 在缺省线程栈上求值，不调 `-Xss`，与 aster-api 一致。两侧在约 2 000–10 000 层递归之间结果不同（Node 完成、JVM `RESOURCE_EXHAUSTED`），属本节与 §5.3 排除在承诺之外的资源类结果；深度 20 000 的递归两侧都是 `RESOURCE_EXHAUSTED`。」

## A5：导出时拒绝（§5.1、§13）

码号说明：§12.1 Q8 已为「依赖签名事实」预留 E736（不启用），本修订新增的两条取 E737、E738。

**P0 证据。**
- 入口选择：aster-api 不按名字取成员，而是用 `CoreIrEntrySelector.withEntry` 把目标 Func 移到所有 Func 之前（`PolicyEvaluationService.toCompiledPolicy`、`DynamicCnlExecutor` 同一做法），因为 Truffle Loader 只认 `AsterConfig.DEFAULT_FUNCTION`（环境变量 `ASTER_TRUFFLE_FUNC`，缺省 `main`）→ `main` → 第一个 Func。模块里存在 `main` 而入口是别的函数时，`withEntry` 报 `shadowed`，JVM 无法执行该入口，ts 却能执行。语料 `entry_annotation.aster` 就属这一类。
- DateTime / PII 值树形未取得（见 A1）。

**修订条款。** §5.1 增加两条**入口级**检查，并厘清作用域：§5.1 首段「判定范围是**整个模块**」改为只约束 E730–E732；E733（原条件本就是「入口参数或返回类型」）、E737、E738 对 manifest `policy.entries` 中的每个入口逐一判定。§5.1 表头「条件（在整个模块的 Core IR 上判定）」改为「条件（E730–E732 在整个模块的 Core IR 上判定；E733、E737、E738 对每个入口判定）」。诊断照常全部给出：同一入口同时命中 E733 与 E738 时两条都报；E738 不进入函数类型（函数类型由 E733 拒绝），避免同一位置重复诊断。表增加两行：

| 码 | 条件 | 理由 |
|---|---|---|
| E737 | 某个入口的函数名不是 `main`，而模块里存在名为 `main` 的函数 | JVM 的入口选择被 `main` 抢占（`CoreIrEntrySelector` 报 `shadowed`），Node 能执行，两侧必然不一致。诊断写明被抢占的入口名 |
| E738 | 入口的形参类型或返回类型中出现 DateTime 或 PII 类型。递归检查以下各处：`List`、`Map` 的键与值、`Maybe` / `Option`、`Result` 两侧、Data 的全部字段、Enum 变体参数，以及类型别名展开后的类型（不进入函数类型，见上）；展开时维护已访问集合 | 值规范 JSON 尚未定义二者的树形（A1）。诊断给出类型路径（如 `return.List<Claim>.submittedAt: DateTime`，`submittedAt` 是 Data `Claim` 的字段名）。VALUE-FORMS 补齐后修订本条 |

- JVM CLI 检测到环境变量 `ASTER_TRUFFLE_FUNC` 即以退出码 2（`USAGE`）拒绝运行，优先入口集合因此恒为 `{main}`；导出检查只按 `main` 判定。若平台进程设置了 `ASTER_TRUFFLE_FUNC`，导出端点回 503，错误码 `export_runtime_misconfigured`，不按该值放宽或收紧。
- JVM CLI 选入口时复用 aster-api 的 `CoreIrEntrySelector` 与 `EntryFunctionException`（P1 复制到 aster-lang-truffle 公共包，P2 aster-api 改为依赖它）：入口不存在、或被 `main` 抢占，都报 `ENTRY_NOT_FOUND`。
- §0「五个决定」表中的「E730–E733」改读为「E730–E733、E737、E738」，§13 P2 行的「E730–E735」改读为「E730–E735、E737、E738」。

---

## 影响

- **包格式 v1**：manifest 多一个必填字段 `coreIr.positionFreeSha256`（A2）。格式尚未发布，`formatVersion` 仍为 `1.0`。
- **实施计划**：#554 已按 A0–A5 修订 Global Constraints 与 T1–T9；T1 在本修订合入后开始。
- **aster-api**：P2 导出端点同时计算两个 IR 哈希、实施 E737 / E738，P2 的黄金门按 A1 比较。P1 不改 aster-api（main 冻结期间）。
- **未改变**：签名配方与被签字节（§6）、B 包 1 MiB 上限、退出码表、「没有绕过开关」。
