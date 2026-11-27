# ADR 0051 修订 2：类型别名在 R2 中展开为目标类型（推翻 R20 中「别名视为 UNKNOWN」）

- 状态：**PROPOSED**（2026-11-27，待产品负责人采纳）
- 日期：2026-11-27
- 修订对象：[ADR 0051](0051-position-aware-vocabulary-resolution.md) §16 **R20**「别名、包装类型（Option/Maybe/List/TypeApp）与未声明类型名视为 UNKNOWN」中的「别名」
- 相关：aster-cloud/aster-lang-core#313；实现 aster-lang-core#324、aster-lang-ts#408、aster-lang-test#266（均已合并，2026-11-27，随 eco-1.0.57 发布）；ADR 0056 修订 A7 D1（core#313 列入 1.0.56，可选）；[修订 1](0051-amendment-1-type-alias-rhs-e800.md)（别名右侧按 TYPE 解析）

---

## 1. 现状与问题

类型别名不产出 Core 声明，R20 因此把别名类型归入 UNKNOWN，点号尾段走「全局唯一候选 + W801」规则。两引擎的结果都与「直接写目标类型」不一致：

- 别名接收者上的字段访问一律 W801，即使字段名在目标类型里能唯一确定；
- 别名目标没有、但别的 Data 恰好有的字段（`x.y`）被静默接受，解析到另一个类型的字段上；
- Match 主语是枚举别名时，裸名 `When Hoch,` 被当作**绑定**，第一臂吞掉所有输入；
- 期望类型是枚举别名（返回类型、实参）时，本地化成员名报 E802。

## 2. 裁决

1. R2（类型化引用解析）在每次类型判定（类型名、不透明、类型变量）之前，把类型别名**逐层展开**为其目标类型。别名表由 R1 输出的别名声明降级得到：「别名规范名 → 目标类型」。别名右侧按[修订 1](0051-amendment-1-type-alias-rhs-e800.md) 解析后的规范类型参与展开。
2. 展开后按目标类型适用 R20 其余规则：目标是 Data / 枚举时按声明类型解析；目标是 Map / Json / Any / 内建标量 / TypeVar 时按不透明处理（点分段原样保留）。
3. **不变**：别名之间成环时停在别名名上，照旧视为 UNKNOWN（W801）；别名目标是包装类型（`T?`、`Option of`、`Maybe`、`List`、TypeApp）时照旧视为 UNKNOWN；未声明的类型名照旧视为 UNKNOWN。
4. 两引擎同一判定，由共享夹具 `alias_receiver_parity.json`（18 例，core `src/test/resources/diagnostics/` 与 ts `shared/` 逐字节相同）锁定。

R20 修订后的文字：「点号尾段的“不透明”判定为白名单：dynamic/未类型化、TypeVar、内建标量、Map/Json/Any；**类型别名先展开为目标类型再判定（成环时视为 UNKNOWN，修订 2）**；包装类型（Option/Maybe/List/TypeApp）与未声明类型名视为 UNKNOWN（走全局候选规则）。」

## 3. 编译结果的变化

| 写法 | 修订前 | 修订后 |
|---|---|---|
| 别名接收者、本地化 / 规范字段名 | W801，IR 不变 | 无告警，IR 不变 |
| 别名目标没有、别的 Data 有的字段（`x.y`） | 接受 | **E801**（收紧） |
| 别名目标是 Map / 标量，`m.alter` | 经词汇改写为 `m.age` + W801，或 E801 | 点分段原样保留（IR 变，放宽） |
| Match 主语为枚举别名，裸名 `When Hoch,` | 绑定（第一臂吞掉所有输入） | 枚举成员（**IR 与运行结果都变**） |
| 期望类型为枚举别名，本地化成员名 | E802 | 成员（放宽） |
| 别名成环、别名目标是包装类型 | W801 | 不变 |

## 4. 门禁

既有生产策略可能因此收紧或改变运行结果。core#324 的 PR 正文写于扫描之前（「未执行」）；合并前由 controller 补跑了生产门禁扫描（2026-11-27，只读导出，扫后删除），结果记录在 [core#324 的评论](https://github.com/aster-cloud/aster-lang-core/pull/324#issuecomment-7208635589)：旧 = core / ts main，新 = core#324 / ts#408 分支；115 份源（api-policy-version 3、cloud-policy 41、cloud-policy-version 71）。

- 预筛：别名声明规则（沿用 core#308）0 命中——生产中没有任何策略声明类型别名；
- java-gate（含 `--aliases`）PASS，0 项待审，sanity java 111 vs ts 111；ts scan PASS；ts ir-diff OK；
- 自测：测试语料 + 上述 18 例夹具全部检出（java-gate newly-failing 4、ir-changed 8，ts 两件工具亦检出）。

因此本修订对现存生产策略无影响；之后新写的别名按本修订解析。

## 5. 落地

引擎实现已合并（core#324 `a780a6e5`、ts#408 `f50f4d1b`、test#266 `9dedfef1`），随 eco-1.0.57 发布；本修订采纳后，ADR 0051 §16 R20 的原文按 §2 末段替换。
