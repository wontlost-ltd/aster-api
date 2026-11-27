# ADR 0061：Guard 对账治理页——孤儿动作的可见性、确认权限与查询扩展

- 状态：**ACCEPTED**（2026-11-20，产品负责人采纳，§5 的 Q1–Q7 全部按推荐值裁决）
- 提出：2026-11-20；来源 wontlost-ltd/aster-cloud#769（aster-api#465 的 cloud 跟进）
- 相关：ADR 0054 §10（对账契约，R65）、ADR 0042 §4.1（内部 HMAC v3）、ADR 0040 §11 D3（签发方的租户权限先例）、ADR 0041 §2.4（链收据查询）
- 修订：本 ADR **扩展 ADR 0054 §10.2 的查询端点**（加 `severity` 过滤、列表项加 `ackComment`，均为向后兼容的新增）；§10.2 的其余契约、§10.3 的类别与状态机不变
- 实现仓：`aster-api`（查询扩展，小）、`aster-cloud`（主：BFF 路由、治理页、文案）
- 实施计划：`docs/superpowers/plans/2026-11-20-guard-orphan-governance-ui.md`
- 后续：[ADR 0065](0065-guard-orphan-tenant-notifications.md)（PROPOSED，2026-11-28）：Q5 延期的 critical 孤儿租户通知（aster-cloud#843）

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| 为什么要 ADR | cloud 今天对 aster-api 的 HMAC 调用**一律签 `member`**，而确认端点要求 `ADMIN`——不改授权映射，cloud 根本无法确认孤儿。这是「谁能看、谁能关」的授权决定，外加一处对外查询契约的扩展 |
| 谁能看 | 该租户中 cloud 团队角色 ≥ `member` 的人；个人租户只有本人。`viewer` **不能看**（与 aster-api 类级 `MEMBER` 一致） |
| 谁能确认 | 团队 `admin` / `owner`；个人租户本人 |
| 角色怎样到 aster-api | 对账三个调用签名时 `X-User-Role` = 该用户在该租户的**当前**团队角色（个人租户为 `owner`），服务端每次现查、不信客户端；aster-api 的 `Role` 与 cloud `TeamRole` 同名同序，原样下发。其他调用路径不变（仍签 `member`） |
| 破坏性动作 | 只有「确认」（`OPEN → ACKNOWLEDGED`，不可撤销，入链）。**不做**删除、重新绑定决策、批量确认、撤销确认 |
| 查询扩展 | `GET …/orphans` 加 `severity=critical|warning|info`（可逗号多选）；列表项加 `ackComment`。无迁移 |
| 关联决策与收据 | cloud BFF 用既有 `GET /api/v1/guard/decisions/{id}` 与 `GET /api/v1/audit/receipts?decisionIds=`，不新增 aster-api 端点；决策 404 只说明「本工作区没有这条决策行」，链收据另行展示（`orphan_receipt` 正是「有链事件、无决策行」） |
| 页面 | `/governance` 加一张跨租户「Guard 对账」摘要卡（客户端加载，不阻塞 SSR）；明细在新页 `/governance/reconciliation`，按租户 |

---

## 1. 现状实证（aster-api `f34e8584`、aster-cloud `6860eaac`、aster-guard `3d48f86`，均为 `origin/main`）

| 事实 | 出处 |
|---|---|
| 对账端点类级 `@RequireRole(Role.MEMBER)`，确认方法级 `@RequireRole(Role.ADMIN)`；跨租户 / 不存在 / 非数字 id 一律 404 | aster-api `src/main/java/io/aster/guard/api/GuardReconciliationResource.java:44,96` |
| 列表只支持 `status / kind / limit / before`；JAX-RS 对未知查询参数**静默忽略**（旧 api 收到 `severity` 会返回未过滤的结果） | 同上 `:78-93,122-152` |
| 列表项不含 `ack_comment`（列存在，确认时写入） | `GuardOrphanAdminService.java:115-133`；`GuardOrphanActionEntity.java:51` |
| `severity` 由 kind 决定并落列；`OrphanKind` 是唯一来源 | `OrphanKind.java`；迁移 `V6.38.0__guard_reconciliation.sql`（`severity VARCHAR(16) NOT NULL`） |
| 上报类孤儿的 `decision_id` 取**上报方声称**的值，可能不存在或属于他租户 | `GuardOrphanDetector.java:211`；ADR 0054 §10.3 第 1 行 |
| 孤儿 `detail` 复制上报原文（`effectId / source / actionName / resourceType / resourceId / …`），上报方是本租户任一 `MEMBER` key | `GuardOrphanDetector.java:255-266`；ADR 0054 §10.2「信任边界」 |
| 确认事件 `GUARD_ORPHAN_ACKNOWLEDGED` 的 `performedBy` 是签名内的 `X-User-Id`（cloud 用户 id） | `GuardOrphanAdminService.java:96-113`；`RequestIdentityResolver.java:122-134` |
| HMAC v3 规范串签入 `X-User-Role` | cloud `src/lib/api-signing.ts:150-163`；aster-api `InternalCallerFilter.java:465-476` |
| aster-api `Role` = `OWNER > ADMIN > MEMBER > VIEWER`，注释写明与 cloud `TeamRole` 对齐 | aster-api `policy/security/rbac/Role.java`；cloud `src/lib/team-permission-matrix.ts:42` |
| cloud 所有 guard 调用的 `userRole` 缺省 `member`，没有任何调用点传别的值 | cloud `src/services/policy/policy-api.ts:572,592,723`；`src/lib/policy-api-identity.ts:12-19`；`src/lib/approvals-inbox.ts:59` |
| cloud 的租户 = 个人租户（`tenantId = userId`）∪ 所在团队；签发方面板以同法判权 | `approvals-inbox.ts:48-55`；`src/lib/fact-issuer-access.ts` |
| cloud 现查团队角色走 `freshDb`（绕过 Hyperdrive 缓存） | `src/lib/team-permissions.ts:62-68` |
| `/governance` 是跨租户的控制注册表视图，按用户可见策略聚合，SSR | `src/app/[locale]/(dashboard)/governance/page.tsx:14-23` |
| 链收据批量查询（决策事件、consume 尝试、结果、审批）已有客户端 | `src/services/evidence/receipts-client.ts:85-115` |
| 执行日志对带审批的决策链到 `/approvals?decisionId=` | `src/app/[locale]/(dashboard)/policies/[id]/logs/logs-content.tsx:377-380` |
| `executions.metadata.guardDecisionId` 有部分索引 | cloud `src/db/schema.ts:1103-1105` |
| aster-guard 只是上报方 / 执行方（`reportSideEffects`），不消费对账查询 | aster-guard `README.md:59`、`src/client.ts:41` |

---

## 2. 目标与非目标

**目标**：cloud#769 的四条——按租户列 OPEN 孤儿并按 kind / severity 过滤；管理员带说明确认（写 `GUARD_ORPHAN_ACKNOWLEDGED`）；每条链到它的决策与收据；en / zh / de / hi 文案。

**非目标**：删除孤儿、重新绑定 decisionId、撤销确认、批量确认；租户内通知（邮件 / 站内，延期到 aster-cloud#843）；cloud 作为上报方（ADR 0054 §10.5 / ADR 0040 §12.4 ④）；改对账判定或告警；`viewer` 可见。

---

## 3. 决定

### 3.1 可见性与确认权限

| 操作 | cloud 判定（UI 与 BFF） | aster-api 判定（不变） |
|---|---|---|
| 摘要、列表、决策上下文 | 个人租户本人；或团队角色 ≥ `member` | 类级 `MEMBER` |
| 确认 | 个人租户本人；或团队角色 ≥ `admin` | 方法级 `ADMIN` |

- 判定只看**角色等级**（`getRoleLevel`），不新增 `TeamPermission` 常量：「看孤儿」与「执行策略」都是成员级，「关闭一条绕过嫌疑」与「改成员角色 / 吊销签发方」同为管理级（ADR 0040 §11 D3 先例）。
- 非成员、他人的个人租户：cloud 403，**不**调用 aster-api。

### 3.2 角色怎样签给 aster-api

对账专用的客户端工厂 `createReconciliationClient(tenantId, userId)`，**每个请求在路由处理函数内调用一次**，结果不缓存、不跨请求复用：

1. 现查 `getTeamMemberRole(userId, tenantId)`（`freshDb`）；个人租户取 `SOLO_TENANT_ROLE`（`owner`）；查不到 → 不建客户端（403）。
2. 以该角色作 `userRole` 构造 `PolicyApiClient`；签名把它签进 v3 规范串。
3. 角色**只**由服务端会话 + 现查得出，请求体 / 查询串里的任何角色字段一律忽略。
4. 工厂**不返回** `PolicyApiClient`，只返回窄接口 `ReconciliationApi { summary, listOrphans, ackOrphan, getDecision }`：带 `admin` / `owner` 签名的客户端被闭包在内，调用方拿不到其他方法（aster-api 还有别的 `ADMIN` 端点，如 `AuditLogResource`、`PolicyAnalyticsResource`，窄接口使它们结构上不可达）。源码扫描测试钉住「全仓只有 `access.ts` 以非缺省角色调用 `createPolicyApiClientForUser`」。

由此 aster-api 的 `@RequireRole` 成为第二道真边界：即使 cloud 的 UI 判定有漏洞，`viewer` 列表、`member` 确认都会在 aster-api 403。其他 cloud→aster-api 路径保持签 `member`，不在本 ADR 范围（扩大到其他路径需各自评估）。

被否决：(a) 全局把 cloud 所有调用改签真实角色——影响面不可控（审批、证据导出等都依赖 `member` 的现行为）；(b) 确认改由 cloud 记账、aster-api 不判角色——确认事件必须与孤儿行同事务入链（ADR 0054 §10.2），只能在 aster-api 做；(c) 新增 `GUARD_ORPHAN_ACK` 权限常量——三个等级已足以表达，YAGNI。

### 3.3 破坏性边界

确认是唯一写操作：`OPEN → ACKNOWLEDGED`，一次性、不可撤销、在同一事务内入链（现行为）。UI 要求非空说明（1–1000 字符，与 aster-api 校验一致）并二次确认。已自动解除（`RESOLVED`）的行不能确认（aster-api 409 `not_open`，UI 刷新该行）。可自动解除的类别（`consumed_without_result`、`allow_unconsumed`、`orphan_receipt`）**允许**确认（例如已知的旧版 SDK 0.1.0 产生的 `allow_unconsumed`），UI 提示「会自动解除」。

### 3.4 查询扩展（ADR 0054 §10.2 的增补）

- `GET /api/v1/guard/reconciliation/orphans` 新增 `severity`：取值 `critical | warning | info`，可用逗号给多个（不得重复、不得为空项）；与 `kind` 同时给时取交集（可能为空页）。非法 → 400 `invalid_query`（与其他参数一致）。
- 列表项（及确认的 200 响应）新增可选字段 `ackComment`（仅 `ACKNOWLEDGED` 行有）。可见范围与 `acknowledgedBy` 相同；它本来就在链事件 metadata 里（`GuardReceiptBuilder.orphanAcknowledgedEvent`）。
- 不加摘要字段：cloud 从 `open`（按 kind）与自己的 kind 表算出各 severity 计数；kind 表与 `OrphanKind` 由两侧测试同时钉住（8 个字面量与 severity）。
- 无迁移：`severity` 列已存在；单租户行数小，按 `(tenant_id, status)` + `id desc` 的现有查询加一个等值条件即可。

**新旧兼容**：旧 api 会忽略 `severity` 并返回全部 severity。cloud 因此**总是在 BFF 再按 severity 过滤一次**（防御），且使用 `severity` 的 cloud PR 只在带本扩展的 aster-api 已部署生产后合并。

### 3.5 关联决策与收据

cloud BFF 按 `(tenantId, decisionId)` 组装「决策上下文」，三个来源并发、各自降级：

| 来源 | 给出 | 404 / 失败 |
|---|---|---|
| `GET /api/v1/guard/decisions/{id}` | outcome、原因码、策略地址、`consumedBy`、`result` | 404 → `not_found`，按 kind 解释：`orphan_receipt` =「链上有决策事件、本工作区无决策行」（同时展示找到的链事件）；`side_effect_without_decision` =「上报方声称的决策 id 在本工作区不存在」（**不**断言它在别处不存在）；其他 kind 出现 404 属异常，如实显示；失败 → `unavailable` |
| `GET /api/v1/audit/receipts?decisionIds=` | 链上决策事件、consume 尝试（含权威那条）、结果、审批（按租户查 `audit_logs`，不依赖决策行） | missing → `not_found`；请求失败 → `unavailable`；不影响其他两项 |
| cloud `Execution`（`metadata->>'guardDecisionId'`，且执行所属策略的租户 = 本租户） | 链到 `/policies/{policyId}/logs` | 无匹配则不显示 |

孤儿行的 `decisionId` 来自 UUID 列（`guard_orphan_actions.decision_id`），要么是合法 UUID 要么缺省；无 `decisionId` 的行不查上下文，显示「上报未带决策 id」。有审批时另链 `/approvals?status=<审批状态>&decisionId=<id>`。**只在当前租户内查**，不尝试其他租户（不泄露他租户决策是否存在，ADR 0054 §10.3）。

### 3.6 不可信文本

`detail` 的全部值、`executorId`、`source`、`actionName`、`resource*` 都是上报方（任一 `MEMBER` key）可控的文本：一律按纯文本渲染（React 文本节点），不转链接、不进 `dangerouslySetInnerHTML`、不进 `title` 以外的属性；超过 256 字符截断显示并可展开。

### 3.7 页面形态

- `/governance`：新增「Guard 对账」卡，客户端调 `GET /api/guard-reconciliation`（跨租户摘要，并发 4、单租户 8 s，单租户失败记入 `unavailableTenants`，同审批收件箱）；每个可见租户一行：critical / warning / info 的 OPEN 计数、最近对账时间、「查看」链接。没有任何可见租户时整卡不渲染。
- `/governance/reconciliation?tenant=&status=&severity=&kind=`：租户选择（只列 ≥ `member` 的租户）、状态标签 OPEN / ACKNOWLEDGED / RESOLVED、severity 多选（缺省 `critical,warning`）、kind 下拉、`before` 游标「加载更多」；行展开显示 detail 与决策上下文；管理员行内「确认」。

---

## 4. 后果

- cloud 第一次对 aster-api 签非 `member` 的角色；范围限定在对账客户端工厂，并有测试钉住「其他路径仍签 `member`」。
- `viewer` 看不到孤儿；如需开放，须同时放宽 aster-api 类级 `MEMBER`（另立决定）。
- 新增文案键约 60 个 × 4 种语言。
- cloud 维护一份 kind → severity / 是否自动解除 的表；aster-api 新增 kind 时 cloud 测试会失败（有意为之）。

## 5. 产品决策（2026-11-20，产品负责人按推荐值采纳）

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | `viewer` 能否看孤儿 | 否（与 aster-api `MEMBER` 一致；detail 含主机名、资源 id） |
| Q2 | 谁能确认 | 团队 admin / owner、个人租户本人 |
| Q3 | 列表缺省过滤 | OPEN + `critical,warning`（cloud from-evidence 的 ALLOW 在 ④ 上线前都会成为 info 类 `allow_unconsumed`，不过滤会淹没 critical） |
| Q4 | 是否要批量确认 | 否（critical 必须逐条核实；info 类会自动解除） |
| Q5 | 是否在本期给租户发通知（critical 孤儿） | 否，延期到 wontlost-ltd/aster-cloud#843；运维告警已由 `AsterGuardBypassSuspected` 覆盖 |
| Q6 | 确认是否可撤销 | 否（入链；同一事实 `(tenant, kind, subject)` 永远只有一行，误确认无法重开——因此 UI 二次确认并要求说明） |
| Q7 | 可自动解除的类别是否允许确认 | 允许，并提示会自动解除 |
