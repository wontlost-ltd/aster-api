# Strategy Replay Java/API 专项交叉审查证据

**审查时间**：2026-08-05 23:52（Pacific/Auckland）  
**审查者**：Codex 子审查者（只读业务源码）  
**分支**：`feat/phase2-rule-conflicts`  
**输入 diff**：`/tmp/review-api.diff`（672 行）  
**结论**：**退回 / 发布阻断**。综合评分 **62/100**，品味评分 **需改进**。

## 1. 审查范围与方法

- 完整读取 diff 中 4 个新增文件：`ConditionInterval`、`RuleConflictAnalyzer` 及各自测试。
- 阅读完整源码与调用链：parser → AST → analyzer，以及 trace collector → `DecisionTrace` → `TraceSkeleton` → `EvaluationResponse`。
- 阅读 `replay/build.gradle`、根 `build.gradle`、`settings.gradle`，确认 Java 25、JUnit Platform 和 `:replay` 依赖关系。
- 对照至少 3 个既有模式：`TraceSkeleton` 的类型级 PII 投影、`ReplayMetadata` 的精度/诚实降级、`ReplayExecutionCoreTest` 的公开入口与错误路径验证。
- 仓库要求的 `sequential-thinking` 与 `code-index` 在本子代理工具集中未暴露。降级为显式五层结构化推理与 `rg`；没有联网、CI 或远程验证。
- 未修改任何业务源码；只创建本证据文件和 `/tmp` 探针文件。

## 2. 本地验证命令与结果

### 2.1 相关测试强制重跑

```bash
./gradlew :replay:test \
  --tests 'io.aster.policy.analysis.*' \
  --tests 'io.aster.policy.replay.TraceSkeletonTest' \
  --rerun-tasks --console=plain
```

结果：`BUILD SUCCESSFUL in 4s`，25 个 task 全部实际执行；`ConditionIntervalTest`、`RuleConflictAnalyzerTest`、`TraceSkeletonTest` 通过。编译仅出现既有 deprecated/unchecked 提示。

### 2.2 模块测试与 API 编译

```bash
./gradlew :replay:test :compileJava --console=plain
```

结果：`BUILD SUCCESSFUL in 1s`，`:replay:test` 与根 `:compileJava` 成功。

### 2.3 可执行反例

保留文件：

- `/tmp/ApiReviewRuleConflictProbe.java`
- `/tmp/review-api-classpath.gradle`
- `/tmp/api-review-classpath.txt`
- `/tmp/api-review-probe-output.txt`

执行命令：

```bash
./gradlew -q -I /tmp/review-api-classpath.gradle \
  :replay:printReviewRuntimeClasspath > /tmp/api-review-classpath.txt
API_REVIEW_CP="$(cat /tmp/api-review-classpath.txt)"
javac -encoding UTF-8 -cp "$API_REVIEW_CP" \
  -d /tmp/api-review-probe-classes /tmp/ApiReviewRuleConflictProbe.java
java -cp "/tmp/api-review-probe-classes:$API_REVIEW_CP" \
  io.aster.policy.analysis.ApiReviewRuleConflictProbe \
  2>&1 | tee /tmp/api-review-probe-output.txt
```

退出码 0。探针使用真实 `InProcessCnlParser` 和真实 `DynamicCnlExecutor`，没有 mock 核心逻辑。

## 3. 阻断问题

### P0-1：Long 在 2^53 以上产生已实证的 false positive，违反“宁可漏报不可误报”

`ConditionInterval` 把 `Long` 精确提升为 `BigDecimal`：

- `replay/src/main/java/io/aster/policy/analysis/ConditionInterval.java:105-110`
  ```java
  if (e instanceof Expr.Long l) return Optional.of(BigDecimal.valueOf(l.value()));
  if (e instanceof Expr.Double d) return Optional.of(BigDecimal.valueOf(d.value()));
  ```

区间判空也按精确十进制比较：

- `replay/src/main/java/io/aster/policy/analysis/ConditionInterval.java:146-150`
  ```java
  int cmp = lo.compareTo(hi);
  if (cmp > 0) return true;
  return cmp == 0 && !(loInclusive && hiInclusive);
  ```

但当前实际 Truffle 数值比较对非 Decimal 一律转 `double`；依赖源码证据为
`/Users/rpang/IdeaProjects/aster-lang-truffle/src/main/java/aster/truffle/runtime/Builtins.java:153-195`：

```java
if (isNumber(args[0]) && isNumber(args[1])) return toDouble(args[0]) == toDouble(args[1]);
return toDouble(args[0]) < toDouble(args[1]);
```

反例输入：

```text
Rule r given x as Long produce Number:
  If x equals to 9007199254740992L:
    If x equals to 9007199254740993L:
      Return 1.
```

- 输入值：`x = 9007199254740992L`
- 预期（按真实运行时）：两个 Long 转 double 后相等，内层可达，返回 `1`。
- 分析器实际：`Finding[kind=ALWAYS_FALSE, line=5, ...]`。
- 真实执行实际：`LONG > 2^53 runtime result=1`。

这不是理论风险，而是同一源码在分析器和生产执行器上的直接语义分叉。仓库现有 `ReplayMetadata` 已明确把 `2^53-1` 当作 safe integer 上限：

- `replay/src/main/java/io/aster/policy/replay/ReplayMetadata.java:251-253`
  ```java
  // 2^53 − 1 = 9007199254740991
  return bi.abs().compareTo(BigInteger.valueOf(9007199254740991L)) <= 0;
  ```

**修复要求**：分析域必须与运行时比较域完全一致。可保守跳过超出 safe-integer 的非 Decimal 条件，或统一运行时/分析器数值语义；未统一前不得发布告警。

### P0-2：忽略 `Set` 导致路径约束过期，真实可达分支被误报 ALWAYS_FALSE

遍历只处理 `Stmt.If`，明确跳过其它语句：

- `replay/src/main/java/io/aster/policy/analysis/RuleConflictAnalyzer.java:79-87`
  ```java
  for (Stmt s : block.statements()) {
      if (s instanceof Stmt.If ifs) {
          handleIf(ifs, constraints, fnName, out);
      }
      // 其它语句类型（Let/Return/Match/…）不影响区间约束，跳过。
  }
  ```

然而 AST 明确包含赋值语句，且真实执行路径接受并执行它。反例：

```text
Rule r given x as Number produce Number:
  Let y be x.
  If y is greater than 100:
    Set y to 0.
    If y is less than 50:
      Return 1.
```

- 输入：`x = 101`。
- 预期：进入外层后 `Set y to 0`，内层为真，返回 `1`。
- 分析器实际：`Finding[kind=ALWAYS_FALSE, line=7, ... 外层已限定 100 < y]`。
- 真实执行实际：`mutation runtime result=1`。

真实执行路径在 lower 后直接序列化并运行，没有在此路径调用 typechecker：

- `replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java:539-559`
  ```java
  CoreLowering lowering = new CoreLowering();
  CoreModel.Module coreModule = lowering.lowerModule(astModule);
  String coreJson = MAPPER.writeValueAsString(coreModule);
  ```

所以不能以“Set 可能被类型检查拒绝”消除该反例；被审分析器接收的 AST 和实际执行器接收的 AST 在当前路径相同。

**修复要求**：遇到 `Set name` 至少清除该变量约束；遇到无法建模的写效果应清空受影响约束或停止该路径分析。保守失效约束即可守住“不误报”，无需复杂数据流求解。

### P1：新增分析器没有生产调用点，功能当前不可达

仓库内执行：

```bash
rg -n 'RuleConflictAnalyzer\.analyze|ConditionInterval' \
  /Users/rpang/IdeaProjects/aster-api --glob '*.java'
```

除新增实现和两份测试外无调用者。入口方法位于：

- `replay/src/main/java/io/aster/policy/analysis/RuleConflictAnalyzer.java:60-70`
  ```java
  public static List<Finding> analyze(Module module) { ... }
  ```

但 parser、compiler、REST response 均未消费 `Finding`。若 Phase 2 验收目标是对用户产生冲突/死规则提示，则当前交付不可观察；如果本 diff 仅定义内部地基，则此项需由上层验收明确降级，而不能声称功能完成。

## 4. Phase 2 逐项证据

### 4.1 `loInclusive` / `hiInclusive`、`intersect` / `isEmpty`

关键实现：

- 更紧下界：`ConditionInterval.java:121-127`；同值时任一开边界会替换为开边界。
- 更紧上界：`ConditionInterval.java:129-135`；规则与下界对称。
- 空区间：`ConditionInterval.java:146-150`；`lo > hi` 或同点非双闭即空。

实测：

- `[5,+∞) ∩ (-∞,5]` → `empty=false`。
- `[5,+∞) ∩ (-∞,5)` → `empty=true`。
- `x == 5` 外层、`x < 5` 内层 → 1 条 `ALWAYS_FALSE`。
- 高精度 Decimal `0.1000000000000000000000000001m` 的闭下界与同值开上界 → 1 条 `ALWAYS_FALSE`，文本精度保留。

**结论**：对纯 BigDecimal 区间，开闭边界与求交逻辑正确；问题在于 Long/Double 的分析数值域未与实际运行时对齐，而非区间公式本身。

`impliedBy` 的端点逻辑也与集合包含关系一致：

- `RuleConflictAnalyzer.java:135-150`
  ```java
  if (c == 0 && !inner.loInclusive() && outer.loInclusive()) return false;
  if (c == 0 && !inner.hiInclusive() && outer.hiInclusive()) return false;
  ```

例如外层 `x>100` 蕴含内层 `x>50`，而不蕴含更窄的 `x>200`。现有测试覆盖这两条，但缺上界/等值开闭的冗余矩阵。

### 4.2 else、AND / OR / NOT、多条件、不同变量/类型

- else 调用使用原 `constraints` 而非 `thenConstraints`：
  `RuleConflictAnalyzer.java:124-127`
  ```java
  walk(ifs.thenBlock(), thenConstraints, fnName, out);
  walk(ifs.elseBlock(), constraints, fnName, out);
  ```
  实测 `x>100` 的 else 内 `x<50`：无 finding，未错误继承 then 约束。通过。
- AND / OR / NOT 的 AST 顶层 operator 分别为 `and` / `or` / `not`，`fromComparison` 的 `build` 不识别后返回 empty（`ConditionInterval.java:88-98`）。反例探针均无 finding；这是保守漏报，不是误报，符合当前声明，但功能覆盖很窄。
- 不同变量：`x>100` 内嵌 `y<50` 实测无 finding；map 按变量分组（`RuleConflictAnalyzer.java:98-120`）。通过。
- 不同数值类型：Decimal 精度实测通过；Long 实测存在 P0 false positive；Int/Double 组合没有单独执行矩阵，记为**未验证**。源码虽声称支持 Int/Long/Double/Decimal（`ConditionInterval.java:105-111`），但分析器没有读取 `Decl.Func.params()` 类型，不能实施类型域约束。
- 矛盾区间：`x>100 ∧ x<50` 实测 1 条 ALWAYS_FALSE；相等边界双闭不空、任一开为空。通过。

### 4.3 错误恢复

- `analyze(null)` 与空函数由现有测试覆盖，源码在 `RuleConflictAnalyzer.java:61-67` 防御 module/body null。
- 手工合法 Java AST 中放入 `Expr.Double(Double.NaN, null)`，`fromComparison` 实际抛：
  `NumberFormatException: Infinite or NaN`。来源是 `ConditionInterval.java:109` 的 `BigDecimal.valueOf`。
- CNL 正常语法不会直接产生 NaN 字面量，因此不定为生产 P0；但 public AST API 的“无法规约返回 empty”并未覆盖非有限 Double。应补错误恢复测试并保守返回 empty。

## 5. TraceSkeleton PII 铁律

### 5.1 结构与投影：通过

`SkeletonStep` 只有四字段：

- `replay/src/main/java/io/aster/policy/replay/TraceSkeleton.java:44-57`
  ```java
  public record SkeletonStep(
      String stepId, String expression, boolean matched, int depth
  ) {}
  ```

投影仅复制 expression/matched，未复制 result/finalResult：

- `TraceSkeleton.java:81-95`
  ```java
  out.add(new SkeletonStep(
      depth + "." + s.sequence(), s.expression(), s.matched(), depth));
  ```

上游 `DecisionTrace.TraceStep` 的确含 `Object result`（`replay/src/main/java/io/aster/policy/api/model/DecisionTrace.java:25-35`），但在该投影处被结构性丢弃。

完整 API 调用路径：

1. `ReplayExecutionCore.java:193-198` 从 raw step 构建含 result 的 `DecisionTrace.TraceStep`。
2. `PolicyEvaluationResource.java:559-571` 只在 `trace=true` 时把完整 DecisionTrace 放进响应。
3. `PolicyEvaluationResource.java:605-609` 单独调用 `TraceSkeleton.from(decisionTrace)` 并附加骨架。
4. `EvaluationResponse.java:43-52` 以独立 `traceSkeleton` 字段序列化。

测试不是只断 null：

- `TraceSkeletonTest.java:39-50` 反射钉死字段集合，明确禁止 `result`/`value`。
- `TraceSkeletonTest.java:52-60` 序列化敏感值 `680`、`张三`、`12345.67`、`APPROVED` 并断言全部不存在。

**结论**：diff 没有修改 TraceSkeleton；完整当前调用路径未突破 PII 边界。骨架顶层另含 schemaVersion/moduleName/functionName，它们是策略元数据，不是步骤业务值。

### 5.2 已知 ADR 0032 残留：不列新问题

已取证的上游 expression 是五类固定描述：`if condition`、`inline if condition`、`return value`、`match arm[n]`、`match no-arm`；depth 当前传 0。它们不包含运行时 result，因此不会造成 PII 泄漏。

影响仅为分析质量：expression 不能还原真实条件，depth 不能还原真实层级；漏斗解释力和层级 UI 受限。按任务约束，这属于 ADR 0032 已知残留，不作为本次新增缺陷。

## 6. 测试覆盖力评估

### 优点

- 新测试走真实 `InProcessCnlParser`，没有手工 mock 比较 AST 核心路径：
  `ConditionIntervalTest.java:27-45`、`RuleConflictAnalyzerTest.java:25-28`。
- 正向、负向均有严格数量/Kind 断言；例如矛盾恰好 1 条（`RuleConflictAnalyzerTest.java:30-41`），正常收窄必须为空（`:58-80`）。
- 覆盖 else 不继承、不同变量、相等开闭边界、矛盾后停止噪音、行号。

### 缺口

- 未覆盖 Long >2^53，与现有仓库 safe-integer 约束脱节；因此全绿仍漏掉 P0-1。
- 未覆盖 Let/Set/写效果，导致 P0-2。
- 未覆盖 AND / OR / NOT 的明确“保守跳过”契约；实现退化或未来误解析时无回归门。
- `numberOf` 声称四种数值字面量，但测试只有普通整数和本审查补充的 Decimal 探针；Long/Double/镜像 `100 < x` 未纳入正式 suite。
- `impliedBy` 缺少上下界、相等开闭、单点区间的系统矩阵。
- 错误恢复只测 null module/空 body；非有限 Double/损坏 AST 会抛异常。
- `describe` 只断 contains 三个 token（`ConditionIntervalTest.java:134-140`），无法钉死开闭符号方向，断言偏弱。
- 无生产入口集成测试，因为生产入口尚未接线。

## 7. 三个既有模式对比

1. **类型级 PII 投影模式 — TraceSkeleton**  
   `TraceSkeleton.java:44-57,81-95` 用专门 record 从结构上排除 result；`TraceSkeletonTest.java:39-60` 同时做反射和敏感值序列化。新 analyzer 的数据结构同样简洁，但没有用类型/语义域标识数值种类。
2. **精度边界与诚实降级模式 — ReplayMetadata**  
   `ReplayMetadata.java:42-53` 明确 Decimal 与 fail-loud 语义，`:251-253` 显式钉 2^53−1；`ReplayMetadataTest.java:160-201` 覆盖 Decimal scale、敏感性和输出。`ConditionInterval` 未复用同一 safe-integer 约束，形成 P0 精度分叉。
3. **公开入口、真实 shape、错误路径模式 — ReplayExecutionCoreTest**  
   `ReplayExecutionCoreTest.java:69-120` 验证异常透传/清理，`:159-193` 经公开入口验证嵌套 raw map 到模型的转换。新 analyzer 测试虽使用真实 parser，但没有经任何生产服务/REST 入口，因此没有发现“实现无调用者”。

## 8. 审查五层法

### 第一层：数据结构（25%）— 12/25

- `Map<String, ConditionInterval>` 对不可变、同名、单变量凸区间足够简单。
- 缺失“数值语义域”（Decimal 精确 vs 非 Decimal double）和“约束版本/写失效”，导致两条 P0 false positive。
- 每层复制 map（`RuleConflictAnalyzer.java:95`）在典型浅层规则可接受，所有权清晰。

### 第二层：特殊情况（20%）— 10/20

- else 不猜补集、AND/OR/NOT 跳过，是符合原则的保守处理。
- 把 Set 归入“其它语句不影响约束”不是业务特殊情况，而是错误的数据流假设；应通过统一的“写则失效”规则消除。
- 非有限 Double 未保守返回 empty。

### 第三层：复杂度（25%）— 20/25

- 本质可概括为“沿 then 路径维护单变量区间”；函数短，主要缩进不超过 3 层，概念数量低。
- 不需要引入 SMT；增加 safe-integer guard 与 Set kill-set 即可修复主要问题，复杂度匹配。

### 第四层：破坏性（15%）— 7/15

- 新类没有修改既有 API，编译/现有测试通过。
- 但一旦接入用户可见告警，两条 false positive 会直接破坏信任；当前无调用者又使“已交付功能”不成立。
- TraceSkeleton 兼容与 PII 边界保持不变。

### 第五层：可行性（15%）— 8/15

- 嵌套数值条件冲突是真问题，区间方案实用。
- 当前仅支持直接比较且没有接线，实际价值有限；先保证与运行时数值/赋值语义一致，再扩大覆盖。

## 9. 技术与战略评分

| 维度 | 分数 | 依据 |
|---|---:|---|
| 代码质量 | 58 | 区间公式简洁，但数值域与写失效有根本错误 |
| 测试覆盖 | 62 | 真实 parser、正负断言较好，但漏两条可执行 P0 |
| 规范遵循 | 72 | 中文注释、简单性较好；不误报原则未守住 |
| 技术平均 | 64 | — |
| 需求匹配 | 50 | 两条误报且无生产接线 |
| 架构一致 | 60 | 未复用既有 safe-integer 语义 |
| 风险评估 | 65 | 文档识别复杂条件，却未识别 mutation/Long 精度 |
| 战略平均 | 58 | — |
| **综合评分** | **62** | **退回** |

## 10. 最终决策与最小修复顺序

**审查建议：退回。存在致命问题：是。**

1. 对非 Decimal 数值采用与运行时一致的语义；最低风险做法是超出 ±(2^53−1) 的 Int/Long 条件直接跳过，补上本报告反例。
2. 在遍历中处理写失效：`Set name` 后移除 `constraints[name]`；无法确定写集合的语句保守清空相关约束。
3. 明确 analyzer 的生产接入点与 Finding 输出协议，并增加端到端可观察性测试。
4. 补齐 Long/Double/Decimal、镜像运算、AND/OR/NOT skip、上下界蕴含矩阵、NaN AST 错误恢复。
5. 修复后必须由独立审查者重跑两条真实执行反例；只有分析器不再产生 false positive 且生产入口可达，方可通过。

**未验证项**：Int/Double 混合的完整运行矩阵、分析结果最终 Cloud 消费契约（本专项输入仅 API diff/源码）。
