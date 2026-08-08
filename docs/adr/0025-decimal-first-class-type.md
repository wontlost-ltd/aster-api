# ADR 0025：Decimal 一等公民类型（金额精确十进制）

- 状态：ACCEPTED（用户拍板"推进 Decimal 原生"，2026-06-29；设计经 Codex 深度审查 session 019f12e7 锁定）
- 决策者：用户
- 相关：[[date-decimal-compliance-primitives]]（Date.* 已完成，金额是合规原语另一半）、ADR 0024（受控 stdlib）、spec-1.0-freeze、commercial-readiness 重估 72/100（合规原语缺口=头号 P0 blocker）

---

## 1. 背景与问题

推广就绪度评估（72/100）的头号 P0 blocker = 合规原语缺口。Date.* 已补（epoch-day Int builtin）。**金额精确十进制**是另一半，且更硬：信贷/保险金额计算**不能用 Double**（IEEE754 二进制误差，如 `1.08` 不精确）。现状 demo 金额字段隐式 Double——CCO 尽调指这是"假精确"，金融场景不可接受。

**为何选一等公民类型（非 builtin namespace）**：金额是合规规则的**主角**，用运算符（`price times 1.08`）+ 比较（`amount at least threshold`）+ 字面量密集。builtin 路线（`Decimal.mul(Decimal.fromText("1.08"), ...)`）会让金额规则变成函数嵌套，**摧毁 CNL "审计员可读" 的立身之本**，且 Decimal 值无类型→编译期无法拦"金额被当 Double 算"。原生让金额像整数一样自然，类型系统能保护精度。代价：动 grammar（PR-blocking parity）+ 3 套 Core IR + 类型系统 + 双引擎舍入——比 Date 重得多。

## 2. 设计（Codex 深审锁定，每条防双引擎逐位不一致）

### 字面量：显式 `m` 后缀
| 源码 | 类型 |
|---|---|
| `123` | Int |
| `123L` | Long |
| `123.45` | **Double**（保持现有语义） |
| `123m` / `123.45m` | **Decimal** |

- lexer `DECIMAL_LITERAL: [0-9]+ ('.' [0-9]+)? [mM];` **必须排在 FLOAT_LITERAL/INT_LITERAL 之前**（否则 `1.08m` 被切成 FLOAT+IDENT）。
- 不用上下文推断（赋给 `as Decimal` 字段时按 Decimal）：要求类型检查反向影响字面量语义，Core IR literal kind 不再由 parse 决定 → 破坏 parse-parity/IR-hash/错误定位。延续 Date 的"显式优先"哲学。
- 不用全默认 Decimal：破坏现有 Double 语义。

### 混算：禁 Double↔Decimal，允许 Int/Long→Decimal
| 左右 | 允许 | 结果 |
|---|---|---|
| Decimal ⊕ Decimal/Int/Long | ✅ | Decimal |
| Decimal ⊕ Double | ❌ **编译期错误** | — |

**核心原则：整数→Decimal 精确提升；Double→Decimal 禁止**（double 已不精确，提升=假精确，无论 `BigDecimal.valueOf(double)` 还是 `new BigDecimal(double)` 都引入可解释性争议）。用户必须写 `amount times 1.08m`（不是 `1.08`）。错误提示明确引导用 `1.08m`。

### 算术：精确加减乘，不隐式舍入
- `plus`/`minus`/`times`：数学精确结果，scale 自然增长（`1.20m times 1.080m` = `1.296`）。
- **不固定 2 位金额 scale**：税率/费率/利率不是金额（`0.0725m`）；中间值过早舍入改变合规结果；隐式舍入污染可证明链（用户看不到关键决策点）。
- **Decimal 不支持 `/`/`//`/`%`**：除法是第一个强迫 scale+rounding 的操作，塞进普通 `/` 需全局 rounding context = determinism/replay 隐患。Decimal `/` → 编译期错误（不落 Double div）。

### 除法/舍入：显式 builtin（M2）
```
Decimal.round(value, scale, mode)
Decimal.divide(left, right, scale, mode)   // b==0 → deterministic error
```
- scale 0..18；mode v1 最小集 `HALF_UP`/`HALF_EVEN`/`DOWN`（金融最关键）。
- 结果值 canonicalize（`Decimal.divide(1m,2m,2,HALF_EVEN)` = value `"0.5"` 非 `"0.50"`——scale 是 display 层，但进 proof trace operation 参数）。

### Core IR 表示：canonical 十进制字符串
```json
{ "kind": "Decimal", "value": "1.08" }
```
- **存规范化 canonical decimal string，非源码原文**。规则：禁指数（不 `1E+3`）/无前导`+`/`-0`→`0`/去整数部前导零/去小数尾零/小数全零去小数点（`1.00m`→`"1"`）/零统一`"0"`/禁 NaN/Inf。
- **值语义非格式语义**：`1.0m`/`1.00m`/`1m` 在 IR 都是 `"1"`。`1.0m equals to 1.00m` = true。
- **比较用 compareTo（绕开 BigDecimal.equals）**：Java `a.compareTo(b)==0`（`equals` 下 `1.0`≠`1.00`）。决策 hash/trace 用同一 canonical string。

### 双引擎逐位一致
- **Java**: `new BigDecimal(canonicalString)`（不从 double 构造）；运算后 `stripTrailingZeros().toPlainString()`（**不裸 toString 避 `1E+3`**）+ 修 `-0`/`0E-..`→`"0"`；比较 `compareTo`。
- **TS**: **decimal.js**（非 big.js/自研——rounding modes 齐、任意精度、生态成熟）。**锁版本+config**（`Decimal.set({precision, rounding:ROUND_HALF_EVEN, toExpNeg:0, toExpPos:1e9})`），固定 canonicalizer 不裸 toString。
- **v1 精度上限**（防 decimal.js precision 截断 vs Java 精确增长的分歧）：max 38 位有效数字/18 scale（外部值/字面量），中间乘法 max 76；**超限两边都 deterministic error，不静默截断**。

## 3. 分阶段（每里程碑独立 PR + 双引擎验证）

- **M1**（核心链，最硬）：`m` 后缀字面量 + lexer/parser + DecimalExpr（3 套 Core IR + 所有 exhaustive switch）+ `plus/minus/times` 精确 + 比较 + `as Decimal` 字段 + Int/Long→Decimal 提升 + 禁 Double 混算 + 双引擎 canonical 一致 + parse-parity & eval-parity fixture。
- **M2**：`Decimal.round` / `Decimal.divide` builtin（scale+mode）+ fixture（HALF_UP/HALF_EVEN/DOWN/除零/边界）。
- **M3**：demo 金额字段改 `as Decimal` 展示精确计算（信贷/保险）。

## 4. v1 明确不做
Decimal `/`/`//`/`%`、Double→Decimal 隐式转换、scale-preserving identity、上下文推断字面量、科学计数法、locale/currency/formatting、Money 类型、global decimal context。

### v1 已知限制（Codex 审查 019f1350 确认，记录而非阻塞）
- **字面量有效位 ≤38**：超 38 位的 Decimal 字面量被 lexer/parser 硬拒（deterministic error）。原因：TS decimal.js 配 `precision:80`，两个 38 位数之积最多 76 位 < 80 保证乘法精确；若放开则 >80 位结果会被 decimal.js 静默按有效位舍入而 Java BigDecimal 精确 → 双引擎分歧。38 位远超任何真实币种金额。
- **Long→Decimal 仅在 ≤2^53 精确**：TS 解释器 Long 字面量运行时表示为 JS `number`（既有行为，非 Decimal 引入），`>2^53` 的 Long 与 Decimal 运算前已丢精度。Int/Long→Decimal「精确提升」契约仅在当前 TS Long 可精确表示范围内成立。修复需改 Long 全局运行时表示（string/bigint），是独立的更大改动，留后续。金额场景 >2^53（约 9×10^15 最小货币单位）极罕见。

## 5. 双引擎对齐风险点（须专门 fixture）
字面量 canonical（`0m`/`-0m`/`001.2300m`/`1.000m`）、比较（`1.0m equals to 1.00m`=true、`1.01m greater than 1.001m`）、混算（`1m plus 2`✅ / `1m plus 2.0`❌）、乘法 scale 增长、舍入（`round(2.5m,0,HALF_UP)`=3 vs `HALF_EVEN`=2）、除法（`divide(1m,3m,2,*)`、除零 error）、toString（Java `toPlainString` 非 `toString`；decimal.js 固定 canonicalizer；BigDecimal `1.0`.equals(`1.00`)=false 必绕 equals）。

## 6. 回滚
M1 是 grammar+Core IR 节点级新增（加法，不改现有 Int/Long/Double）；若 parity 顽固 red 可回退 M1 整批。Decimal 字面量无后缀代码不受影响（`1.08` 仍 Double）。已发布制品不回收。
