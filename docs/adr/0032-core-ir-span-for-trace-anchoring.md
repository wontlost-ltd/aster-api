# ADR 0032：trace 步骤锚定到源码位置（Core IR span / origin 贯通）

- 状态：PROPOSED（2026-08-05。由 Phase 1 条件漏斗的一个**已确证生产缺陷**倒逼提出）
- 决策者：待用户拍板
- 相关：ADR 0016（双引擎 IR parity）、M2.1b 步骤级 trace（truffle#49 + api#146）、Phase 1 条件漏斗（aster-cloud `fix/phase1-funnel-grouping`）

---

## 1. 背景：一个已经发生的错误，不是假想风险

M2.1b 给执行加了步骤级 trace，`TraceSkeleton.SkeletonStep` 形如：

```java
record SkeletonStep(String stepId, String expression, boolean matched, int depth)
```

Phase 1（条件漏斗 / 死分支检测）要做的事是**跨多次执行统计每个判断点的命中率**。这需要一个「同一个源码判断点」的稳定标识。当时用了 `stepId`。

**`stepId` 不是源码标识，是执行序号。** 它在构造时形如 `<depth>.<sequence>`——第几层、第几步。分支型策略换一个输入就走另一条路径，同一个序号会落到**完全不同的源码节点**上。

生产数据实证（策略 `87f20dc0-57b4-42f8-92f5-882a2937ef7a`，20 次执行呈现 3 种形态）：

```
 stepId | 不同 expression 数 |            被混在一起的节点
--------+--------------------+--------------------------------------
  0.1   |         3          | if condition | match no-arm | return value
  0.2   |         1          | return value
  0.3   |         1          | return value
```

后果是**静默错误**：`0.1` 把一个 if 条件、一个 match 兜底、一个 return 混成一条统计，算出 35% 这种毫无意义的"命中率"；两条真实的死分支被高命中率的 return 掩盖，检测不出来。没有任何报错——业务人员看到的是一个**看起来很合理的错数字**。

另一条佐证：策略 `89f77488`（中文医疗核保）源码有 7 个可追踪节点，其骨架只有 4 步——`stepId` 与源码节点根本不是一一对应。

**已做的止血**（`fix/phase1-funnel-grouping`，已提交）：分组键改为 `stepId + expression`，上述数据正确拆成 5 个节点，两条死分支都被识别。**但这只是止血，不是治愈**——见下节。

---

## 2. 为什么止血不够：expression 是占位符

`TraceAccess.record` 的 `expression` 参数在**全部 5 个调用点都是硬编码字面量**（truffle 仓实测）：

| 文件 | 行 | 传入的 expression |
|---|---|---|
| `nodes/IfNode.java` | 48 | `"if condition"` |
| `nodes/IfExprNode.java` | 46 | `"inline if condition"` |
| `nodes/ReturnNode.java` | 18 | `"return value"` |
| `nodes/MatchNode.java` | 68 | `"match no-arm"` |
| `nodes/MatchNode.java` | 60 | （`recordMatchArm`，只有 armIndex） |

所以一条规则里 `如果 患者.年龄 小于 18` 和 `如果 患者.年龄 大于 65` **产出完全相同的 expression 字符串**。止血后 if 不会再和 return 混，但**同类型的不同条件仍然会被合并**——这是当前修复无法覆盖的残留错聚。

对产品的直接影响：条件漏斗给业务人员看的是"哪个判断点把人挡掉了"。如果两个不同的年龄判断显示为同一行，这个功能的**核心价值就不成立**。

另外 `depth` 参数在所有调用点也硬编码为 `0`，嵌套层级信息同样缺失。

---

## 3. 关键发现：位置信息已经存在，只是没贯通

这是本 ADR 最重要的一条事实，它把方案从"新增能力"降级为"接通已有能力"。

**`aster-lang-core` 的 Core IR 已经有源码位置。** `aster/core/ir/CoreModel.java`：

```java
public static final class Origin {
    public String file;        // 源文件路径
    public Position start;     // 起始位置
    public Position end;       // 结束位置
}

public static final class If implements Stmt {
    public Expr cond;
    public Block thenBlock;
    public Block elseBlock;
    public Origin origin;      // ← 已经在这里
}
```

覆盖面：**46 个 Core IR 节点声明了 `origin`**，`CoreLowering` 中 **50 处调用 `spanToOrigin(...)`** 从 AST 的 `Span` 填充。AST 侧 `Stmt.java` / `Expr.java` 的 Span 覆盖也是完整的（`Stmt.If` 明确带 `@JsonProperty("span") Span span`）。

**断点在 truffle 侧。** `aster-lang-truffle` 有一份**独立的** `aster/truffle/core/CoreModel.java`，反序列化同一份 JSON，但：

```java
@JsonTypeName("If") public static final class If implements Stmt {
    public Expr cond; public Block thenBlock; public Block elseBlock; }
    // ← 没有 origin
```

实测 `grep -c origin` 在 truffle 的 CoreModel 上返回 **0**。也就是说：**执行引擎在反序列化时把位置信息丢掉了**，然后到了需要它的 trace 环节，只能拿硬编码占位符顶上。

> **待确认（Spike 必须先答的问题）**：全部 `.core.json` fixture 样本（扫了 40 个）中 `origin` 出现次数均为 **0**。这说明位置信息可能在**序列化阶段**就已经没了，而不只是 truffle 反序列化丢弃。两种情况的工作量差别很大：
> - **情况 A**：序列化带 origin，只是 truffle 不读 → 改 truffle 一侧即可，成本最低。
> - **情况 B**：序列化不带 origin（fixture 证据倾向这个）→ 还需处理 core 侧的序列化配置，且要评估**产物体积**与**双引擎 parity 基线全量重生**。
>
> 不要在没跑通这一步之前给排期。

---

## 4. 方案

### 4.1 目标

给 `SkeletonStep` 增加一个**稳定的源码锚点**，使跨执行聚合有正确的分组键：

```java
record SkeletonStep(
    String stepId,       // 保留：执行序号，回放/时序仍需要
    String anchor,       // 新增：源码锚点，如 "L21C5-L21C22"（稳定，可跨执行对齐）
    String expression,   // 保留：人类可读文本（后续可由 anchor 反查源码真实文本）
    boolean matched,
    int depth
)
```

`anchor` 由 Core IR 的 `origin` 直接导出，**不引入新的位置体系**。

### 4.2 分阶段

**Spike（先做，1 步）**：回答 §3 的待确认问题——真实链路上 coreJson 到底有没有 `origin`。方法：取一条生产策略源码，跑一次 compile，直接看落库的 coreJson。**这是所有排期的前置，不做完不估工时。**

**S1：贯通位置信息**
- 情况 A：truffle `CoreModel` 补 `origin` 字段（46 个节点里 trace 实际用到的是 If / IfExpr / Return / Match 四类，**可只补这几个**，不必全量）
- 情况 B：额外处理 core 侧序列化 + 重生 parity 基线

**S2：TraceAccess 接线**
- `record(...)` 增加 `anchor` 参数，5 个调用点从节点自身的 origin 取值（节点构造时已能拿到）
- 顺带修 `depth` 硬编码 `0`
- 注意 `@CompilationFinal ENABLED` + `@TruffleBoundary` 的 PE 纪律：anchor 应在**节点构造期**算好存为常量，不要在 `execute` 热路径上拼字符串

**S3：消费侧收敛**
- `TraceSkeleton` 加 `anchor`（**PII 边界不变**：anchor 是源码位置，不是数据值，不引入任何 PII）
- Phase 1 分组键从 `stepId + expression` 改为 `anchor`——语义正确且更稳
- Phase 1 的联合分组作为 anchor 落地前的兼容路径保留

### 4.3 兼容性

- `SkeletonStep` 是**新增字段**，旧骨架 `anchor` 为 null → Phase 1 回退到 `stepId + expression` 分组（当前已实现的行为），不崩
- 历史执行数据无 anchor，**不做回填**：位置信息当时就没采集，回填等于编造。UI 上老数据继续用兼容路径
- Core IR JSON 增字段属于**向后兼容的扩展**，但情况 B 下会触发 parity 基线全量重生，需按 ADR 0016 流程走

---

## 5. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **产物体积膨胀** | 46 个节点每个挂 file+start+end，coreJson 可能显著变大；策略要落库、要走网络 | Spike 阶段实测体积差；必要时 anchor 只保留 `start`，或用紧凑编码（`L21C5`）而非嵌套对象 |
| **PE 性能回退** | trace 在执行热路径上 | anchor 在节点构造期算好；`ENABLED=false` 时 PE 仍须折叠为 no-op。需跑 JMH 对比 |
| **双引擎 parity** | TS 引擎侧也要有对应 origin，否则 IR parity 红 | 按 ADR 0016 处理；Spike 阶段先确认 TS 侧现状 |
| **锚点稳定性** | 策略源码改一行，下面所有 anchor 全变→跨版本统计断裂 | **接受**。跨策略版本的统计本就不该混算；Phase 1 已按 policyVersion 隔离。行号锚点在**同一版本内**稳定，这正是需要的粒度 |

---

## 6. 不做的事

- **不引入独立的节点 ID 体系**（如给每个 AST 节点发 UUID）。位置信息已经有了，再造一套是重复建设，且 UUID 跨编译不稳定
- **不回填历史数据**（见 §4.3）
- **不把 `result` 放进 anchor 相关结构**。`TraceSkeleton` 的 PII 边界是**结构性保证**（没有 `result` 字段），本 ADR 不触碰这条线

---

## 7. 待拍板

1. Spike 先跑（确认情况 A / B）——**建议无条件先做**，成本极低，且决定后续所有估算
2. anchor 编码形态：紧凑字符串 `"L21C5-L21C22"` vs 结构化对象。倾向紧凑字符串——体积小，直接可做 Map key
3. S1 是只补 trace 用到的 4 类节点，还是全量对齐 core 的 46 个节点。倾向**先补 4 类**（实用主义，按需扩展）
