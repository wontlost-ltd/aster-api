# ADR 0036：运行期变体标签统一为 `__type`

- 状态：**ACCEPTED**（2026-08-24）
- 决策者：用户（2026-08-24 拍板 Truffle 对齐 `__type`）
- 相关：aster-lang-ts#137（分叉本体）、aster-lang-test#100（被卡住的语料补全）、
  ADR 0016（双引擎 parity）、ADR 0035（Map.get 返回 Maybe，放大了变体的使用面）

---

## 1. 背景：同一条规则，两引擎返回不同形状

```aster
Rule probe produce:
  Return Ok of 7.
```

| 引擎 | 返回值 |
|---|---|
| TypeScript | `{"__type":"Ok","value":7}` |
| Truffle (JVM) | `{_type=Ok, value=7}` |

`Some` / `None` / `Err` 同样如此；**结构体类型名**也走同一套标签
（TS 的 `evalConstruct` 写 `{ __type: expr.typeName }`，
Truffle 的 `AsterDataValue` 写 `_type`）。

这不是内部实现细节——它是**规则的返回值**，会直接进入宿主。

## 2. 为什么必须统一（而非"两边都认"）

三条实证依据：

1. **等价性契约事实上已以 `__type` 为准**。`corpus/tier1-equivalence` 的 cases
   里 `__type` 出现 **39 处**，既作输入也作期望输出；`_type` **零处**。
   即 Truffle 是那个偏离方。
2. **宿主拿到的 JSON 键名不同**。等价性比对按值比较会直接判为不等。
3. **已实际卡住语料补全**。补 tier1 覆盖时，`g3-apply-call` 的 `wrap`
   与 `stdlib_collections` 的 `head` 因两侧表示不同而**无法定基线**——
   写进 cases 就等于替引擎选一侧当"正确答案"。

第 3 条是决定性的：分叉不再只是理论问题，它在**阻碍质量工作本身**。

## 3. 决策

**运行期变体标签与结构体类型标签，统一为 `__type`（双下划线）。**

- Truffle 侧 52 处 `"_type"` 改为 `"__type"`（6 个生产文件 + 6 个测试/fixture）
- TS 侧不动（本就是 `__type`）
- 语料不动（本就是 `__type`）

### 为什么不是反方向（TS 改用 `_type`）

- 语料 39 处 cases 都得改，且那是**已发布的等价性基线**——动基线的风险高于动引擎
- TS 内部多处注释与逻辑依赖"`None` 的运行期表示就是 `null`"，牵连面更大

## 4. 下游影响与合入顺序（**易踩**）

Truffle 的输出标签是**对外契约**。改它会打破两个真实消费者：

| 仓库 | 位置 | 若不先改的后果 |
|---|---|---|
| `aster-lang-runtime` | `StdResult.mapOk/mapErr` 读 `_type` 判定 Result | 静默落到"不是 Result"分支，抛**误导性**的 `expected Result, got Map` |
| `aster-api` | `HealthcareConverter` 读 `_type` 判 `claim`/`eligibility` | 静默退化成通用转换，**不报错、结果却不对** |

两处都是**静默降级**而非响亮失败——排查会指向错误方向。

★因此合入顺序是硬性的：

```
第 1 批（下游容忍读）  aster-lang-runtime、aster-api
        ↓
第 2 批（上游改输出）  aster-lang-truffle
```

反过来会出现中间态直接炸掉。

## 5. 兼容策略：容忍读、规范写

下游读取侧**同时认两种标签**（先查 `__type`、回落 `_type`），写入侧统一 `__type`。

这不是权宜之计：下游会消费**不同版本引擎**产出的值（含缓存的旧结果），
只认新标签会让旧值静默降级——正是本 ADR 要消灭的失败模式。

## 6. 如何防止再次漂移

`aster-lang-truffle` 的 `VariantTagParityTest`：fixture 由 TS 编译器产出，
断言"同一份 IR 在两引擎上产出同样的标签"（Ok/Err/Some 三例）。

★这条测试的价值在于它**不是各写一份等价源码**——那样验不出形状差异。
必须是同一份 IR 喂两个引擎，才能锁住"输出形状一致"这件事。

变异验证：把 `ResultNodes` 改回 `_type` → 该测试转红。

## 7. 未决

`None` 在 TS 的运行期表示是**裸 `null`**、在 Truffle 是 `{__type:"None"}`。
本 ADR 只统一了**标签名**，没有统一 `None` 的**表示形式**——
`Rule head produce: Return none.` 两引擎仍返回不同的东西。

那是独立议题（涉及 TS 侧"None 就是 null"的大量既有假设），
留在 aster-lang-ts#137 继续跟进。
