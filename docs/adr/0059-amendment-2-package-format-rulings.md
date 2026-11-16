# ADR 0059 修订 2：包格式 v1 细则裁决（T1 实施与独立评审）

- 状态：**PROPOSED**（待用户确认；实现已按本修订进行，见 aster-cloud/aster-lang-test#216）
- 日期：2026-11-17
- 修订对象：[ADR 0059](0059-executable-policy-export.md) §3、§4.1、§6.2，以及实施计划 `docs/superpowers/plans/2026-11-16-adr0059-p0-p1.md` 的 Global Constraints、T2 Step 3、T4 Step 3
- 唯一来源：aster-lang-test `conformance/package-v1/README.md`（规则正文）与同目录的 schema、`exit-codes.json`、夹具。本修订只记录裁决与理由，不复述条款。

T1 把包格式写成可执行夹具时，发现 ADR 与计划有若干处没有定义、或定义后无法让两个独立 CLI 的输出逐字节相同。下列裁决已经写进真相源；两个 CLI（T2–T9）以真相源为准。

| 编号 | 裁决 | 理由 |
|---|---|---|
| R1 | 信封 `error.message` 逐字等于 `exit-codes.json` 中该错误码的固定文本；诊断（文件、哈希、编译诊断、引擎异常、入口被 `main` 抢占的原因）只写 stderr。计划原写「`PKG_IR_DRIFT` 的 message 带编译诊断」作废 | 修订 1 A0 只归一 `meta.engine` / `meta.cliVersion`；message 若含引擎文本，失败信封不可能跨 CLI 逐字节相同 |
| R2 | 新增错误码 `PROBE_MISMATCH`（退出码 1），只由 `verify --probes` 产生；`tests/probes.json` 的期望可以是 `{"ok":true,"value":V}` 或 `{"code":"EVAL_ERROR","ok":false}`，比较须无损读取 | §5.3 的探针按 schema 生成，会命中求值错误；`JSON.parse` 读 Long > 2⁵³ 会误判 |
| R3 | 检查顺序：manifest 大小 → 严格 JSON → 文件字节 = 规范 JSON 的 UTF-8 → `format` / `formatVersion`（**先于 schema**）→ schema → 语义规则 → `coreIr.version` → 签名 → 完整性 → IR → 入口 → 输入 → 求值。`formatVersion` 语法为 `^(0\|[1-9][0-9]{0,8})\.(0\|[1-9][0-9]{0,8})$` | 计划 T2 Step 3 的「schema → formatVersion」会让带新字段的 1.1 manifest 报 `PKG_INVALID_MANIFEST`，与 §3「更高次版本按不受支持拒绝」相反 |
| R4 | `meta` 字段的取值时机、`verify` 各阶段的检查项与「第一项失败即停止」、`inspect` 走 read → manifest → signature → integrity 且按 §4.1 输出输入 / 输出 schema | 失败信封要逐字节相同，`meta` 的填写时机必须定死 |
| R5 | 读包：路径为目录即目录形态、为普通文件即 zip（与扩展名无关，ADR §2 与计划中「`.zip`」的写法据此理解）；目录内符号链接、单文件或累计 > 64 MiB 即失败；zip 须中央目录与本地头一致、EOCD 在末尾、无前后缀字节与注释、无 ZIP64 / 加密、唯一的 NFC 安全根目录（可为非 ASCII）、CRC 校验；`manifest.json` ≤ 1 MiB、`manifest.sig.json` ≤ 64 KiB | aster-api 用 `ZipOutputStream` 写出（数据描述符、UTF-8 标志）；只靠流式读取会让「`unzip` 看到的」与「CLI 执行的」不同 |
| R6 | 函数名 / 模块名使用两侧 canonicalizer 的 Unicode 标识符规则并要求 NFC；`files[].path` 仍为 ASCII；schema 模式一律以 `(?![\s\S])` 收尾 | zh-CN 等 locale 产出中文名；Java `Matcher.find` 下 `$` 会匹配末尾换行之前 |
| R7 | 输入类型映射：Int 为 int32（超出 `INPUT_INVALID`）；Long 为安全整数 token 或 int64 十进制字符串；Decimal 字符串至多 80 位数字；List / Map&lt;Text,V&gt; / Maybe（`null` 为 None）/ 无载荷 Enum（变体名）的形态；Result、非 Text 键 Map、嵌套 Maybe 不能作为输入（导出以 E733 拒绝）；严格 JSON（无 BOM、无重复键、无尾随内容、深度 ≤ 64、≤ 16 MiB） | Truffle Int 为 32 位（整数宽度一致性另见 aster-cloud/aster-lang-truffle#183）；ts decimal.js `precision: 80`；Jackson 与 `JSON.parse` 的缺省宽容度不同 |
| R8 | 信任锚：原始 `--pubkey` 不看 kid；JWK 形态带 kid 时必须相符；锚文件不合法、`--jwks` 与 `--pubkey` 同时给出、重复选项、`--timeout` 语法错误一律 `USAGE` | 两侧参数解析库（cac / picocli）的缺省行为不同 |
| R9 | 值规范 JSON 嵌套层级上限 64（与 ts `SERIALIZE_MAX_DEPTH` 相同），超出与含孤立代理项的字符串为 `EVAL_ERROR`；Decimal 值为去掉末尾 0 的普通写法 | JVM 侧原本没有深度上限 |
| R10 | manifest 中所有数字必须是 ±(2⁵³−1) 内的整数（schema 给出 maximum；用任意精度整数读入的实现也必须拒绝超出者）；zip 先按文件大小判 64 MiB 再读入 | 规范 JSON 的数字域只到安全整数；JVM 用 BigInteger 读入时不会自然失败 |

ADR 0031（错误码单一来源）：`PROBE_MISMATCH` 是 CLI 退出信封里的码，不是编译诊断码，不进 E 码表；若日后要在平台侧展示，再按 ADR 0031 登记。
