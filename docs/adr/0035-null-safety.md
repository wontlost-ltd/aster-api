# ADR 0035：把 Aster 做成 null-safe 语言

- 状态：**PROPOSED**（2026-08-22。调研完成，代码未动，待用户拍板实施档位）
- 决策者：待用户拍板
- 相关：truffle#74（第 1 项 null 键塌陷）、ADR 0031（Stable/Experimental 边界，决定破坏性变更能走多快）、ADR 0016（双引擎 parity）

---

## 1. 背景与问题

Aster **已经具备** null-safe 所需的全部零件，缺的是**强制**：

| 零件 | 现状（实测） |
|---|---|
| `Some`/`None` + `Maybe.withDefault` | ✅ 可用（`Maybe.withDefault(Some(5),0)=5`、`(None,9)=9`） |
| `Match … When null` | ✅ 可用（缺键匹配到 `When null` 分支，返回 `"absent"`） |
| 类型系统标注可空性 | ❌ **不存在**——`TypeChecker` 无 nullable/Maybe 概念 |
| 编译期强制处理 null | ❌ **不存在** |

于是出现这条真实的漏网路径（实测，非推演）：

```aster
Rule r given k as Text, produce Int:
  Let m be Map.empty().
  Return Map.get(m, k) plus 1.     -- 缺键 → null 参与算术
```

- **编译：通过**（类型检查未拦）
- **运行：失败** `Type mismatch: '+' operator expects numbers, got object and number`

对合规决策引擎，这正是最该消灭的一类：**规则写出来了、审批过了、上线后才在某条特定输入上炸**。

## 2. null 从哪些口子进来（完整清单）

逐个实测得出，非穷举猜测：

| # | 入口 | 现状 | 危害 |
|---|---|---|---|
| 1 | `Map.get` 缺键 | 返回裸 `null`（**已写进文档、进了 corpus、两引擎一致**） | 最主要来源 |
| 2 | 宿主传入 `null` context 值 | GraalVM 包成 `isNull()==true` 的 HostObject，`When null` 能匹配 | 已被正确处理 |
| 3 | `Map.put(m, null, v)` **null 键塌陷** | `mapKey(null)` 与 `mapKey("null")` 都得 `"null"` → **两个逻辑键塌成一个槽位，静默丢数据** | truffle#74 第 1 项 |
| 4 | `List.get` 越界 | 抛错（不返 null） | 无 |

★第 3 项与 null-safe 是**同源不同症**：它不是「null 泄漏出来」，而是「null 被悄悄当成字符串 `"null"`」。TS 侧同样如此（`String(null)==="null"`），属**忠实复刻了 JS 的坑**。

## 3. 三个档位（互斥，按破坏性递增）

### 档位 A：只堵静默丢数据（不改任何对外契约）

- `Map.put/get/remove/contains` 遇 **null 键**显式抛错（两引擎同步）
- 不动 `Map.get` 缺键返回 null 的行为

**破坏面**：几乎为零。null 键当前的行为是「静默覆盖」，没有正确程序会依赖它。
**收益**：消灭一类静默错答案。
**不解决**：第 1 项那条漏网路径依旧。

### 档位 B：A + 编译期警告

- 在 A 之上，`StabilityGate` 式的独立扫描（复用 ADR 0031 的范式）：
  检测「`Map.get` 的结果未经 `Match When null` / `Maybe.withDefault` 就直接参与算术/字段访问」
- 出 **warning 诊断**，不阻断；生产/审批路径可开 strict → 拒绝

**破坏面**：默认不阻断，现有程序照跑。
**收益**：把「上线后才炸」提前到编译期可见。
**代价**：需要一遍数据流分析（`Map.get` 结果的去向），非平凡工程。

### 档位 C：`Map.get` 改返回 `Maybe`

- 真正的语言级 null-safe：类型系统里没有 null，缺键即 `None`

**破坏面（实测清点）**：
- 文档 `stdlib.mdx` 明写「value, or `null` if absent」（en/zh 两版）
- corpus `map_ops.aster`、`map_int_key_probe.aster` 直接 `Return Map.get(m, k)`
- 两引擎实现 + golden 基线
- 可能存在的用户策略

**必须配套**：ADR 0031 的 Stable/Experimental 分级说明 `Map.*` 属 Stable →
按 spec-freeze 承诺「Stable 集 1.x 内语义不变」，档位 C **不能在 1.x 内做**，
需留到 2.0，或提供 `Map.getOrNone` 新 builtin 并把 `Map.get` 标记 deprecated。

## 4. 建议

**先做档位 A，档位 B 单独立项，档位 C 留 2.0。**

理由：
1. A 是纯收益、零破坏，且直接闭掉 truffle#74 第 1 项这个**已知会静默丢数据**的缺口
2. B 的价值最高但工程量最大（数据流分析），值得单独排期而不是搭在 bug 修复里
3. C 与 spec-1.0 freeze 的兼容承诺**直接冲突**（ADR 0031），1.x 内做会稀释「Stable 不变」的信用——
   这恰恰是 ADR 0031 要保护的东西

## 5. 附：本次调研顺带核实的两项（truffle#74 第 2、3 项）

issue 正文未更新，实测**均已修复**：

| 项 | issue 记载 | 实测现状 |
|---|---|---|
| `valueEquals` 环形结构 StackOverflow | 无深度上限 | ⚠️ **Java 已修、TS 未修**（见下） |
| scale 接受 `"2d"` | Java 接受为 2 | ✅ 严格正则拒绝；`"2d"/"2f"/"2D"/"0x10"/"0x1p3"` 全拒 |

**残留一处分叉**：`"0x10"` → TS `Number("0x10")=16` **接受**，Java **拒绝**。
文档签名是 `scale: Int`，字符串 scale 本就未文档化，建议**把 TS 也改成严格正则**（与 Java 对齐、一并拒 hex），
而不是让 Java 去接受 hex——后者与「响亮失败」的立意相悖。

★**订正一处 issue 未记载的分叉**（本次实测发现）：

| 引擎 | 环形结构比较的行为 |
|---|---|
| Java (truffle) | 深度 100 抛 `BuiltinException: 比较深度超过 100 层（疑似环形结构）`——**域内错误、确定性** |
| TS | **无任何深度上限**，抛 `RangeError: Maximum call stack size exceeded`——**栈深相关、不确定** |

即 TS 侧恰恰就是「跟随运行时栈」的形态。

**深度上限是否应跟随 JVM 栈**：**不建议**，且建议把 TS 也改成固定 100。

理由是本项目的第一约束：**两引擎逐字节一致 + 可回放**。跟随栈深意味着同一段规则
在不同机器、不同 JIT 预热状态、不同 `-Xss` 下**给出不同结果**（甚至一次抛错一次不抛），
这与合规决策引擎「同输入必同输出」的承诺直接冲突。
固定阈值是确定性选择；阈值取多少可以讨论，但**必须两侧同值且与运行时栈无关**。

另注：`RangeError` 在 TS 侧是宿主错误而非域内错误，与 Java 的 `BuiltinException`
分类不同，跨引擎的错误对照表也会因此对不齐。
