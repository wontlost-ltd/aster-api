# ADR 0058 修订 1：阅读视图先显示排版，编辑器缺省打开并排视图

- 状态：**ACCEPTED**（2026-11-20，产品负责人决定）
- 日期：2026-11-20
- 修订对象：[ADR 0058](0058-structured-authoring-capability-and-policy-layout.md) §7.7 第 1 条在阅读视图上的适用范围；阶段 2 实施计划（`docs/superpowers/plans/2026-11-19-adr0058-phase2.md`）裁决 R16，并补充 R11 的并排视图默认状态
- 实现：wontlost-ltd/aster-cloud `feat/layout-display-first`（依据 cloud `20d23c94`，含 C9 #825、C10 #827、#829、#837）
- 来源：产品负责人 2026-11-20 的两句话——「如果有显示排版，优先显示」「编辑时，默认打开并排视图」；并明确**审批与评审视图不变**

本修订只改两处默认值，其余不变。

## 裁决

| 编号 | 裁决 | 理由 |
|---|---|---|
| A1 | **取代 R16（只对阅读视图）**：策略详情页源码卡片在活跃版本带一致的排版时**默认选中「显示排版」**。`LayoutReadTabs` 增加 `defaultTab: 'canonical' \| 'display'`，缺省 `canonical`；只有阅读视图传 `display`。核对 cloud 全部 `LayoutReadTabs` 调用处：阅读视图只有策略详情页一处（改）；`VersionDetailPanel` 是审批者读源码的地方（R15，不改）。`PolicyReviewPanel` 只渲染 canonical 片段与说明、`VersionComparePanel` 只比 canonical（§7.7 第 2 条），都不渲染 `LayoutReadTabs`，不改 | 产品负责人决定；详情页面向读者。审批者批准的是执行的源码，所以审批视图保持 canonical 优先 |
| A2 | **R11 的并排视图默认状态**：编辑器加载时带着一致的排版（作者有 `structured_authoring`、活跃版本有排版且与 `Policy.content` 逐字对应）→ 并排视图**缺省打开**。作者显式关闭写入浏览器偏好 `aster.editor.layoutSideBySide = false`，此后带排版的策略也缺省关闭，直到作者再次显式打开（写回 `true`）。优先级：本次编辑内的显式操作 > 浏览器偏好 > 缺省（打开）。套用带排版的模板（#829 / #837）照旧打开本次，但不写偏好。加载时没有排版、排版不一致或无能力 → 与改前相同（关闭，DOM 不变） | 原实现不持久化开关（`useState(false)`）。显式关闭是用户表达的偏好，应跨策略保留；模板是一次操作，不应覆盖它。偏好按浏览器存（与「AI 行内补全」开关同一机制），不进 `PolicyDraftFields`（R13 不变） |
| A3 | **无障碍**：标签顺序保持 canonical、display，不随默认值重排；只改默认选中项（`aria-selected` 与 roving `tabIndex` 跟随选中项）。 | 各视图的标签序列一致，读屏与键盘用户在详情页和版本详情遇到同一顺序；WAI-ARIA 标签页不要求选中项排第一 |

## 不变

- R15：审批 / 评审（`VersionDetailPanel`）canonical 默认、display 次级；`PolicyReviewPanel` 的说明与链接；版本对比只比 canonical。
- §7.7 第 6 条与 R16 的「不一致可见」：复验不一致的排版在任何视图都只显示 canonical 与提示，绝不显示可能过期的排版，与 `defaultTab` 无关。
- 无排版时 DOM 与引入 layout 之前一致；无能力用户的编辑器 DOM 不变（既有快照不改）。
- 执行、证据、导出、What-If、回放、Guard 仍只读 `content`（§7.7 第 5 条）。
