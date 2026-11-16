# ADR 0058：Cloud 结构化编写 —— 用户 / 团队能力授权模型与策略的 LayoutMap

- 状态：**PROPOSED**（2026-11-16 起草，待 Codex 审核与用户确认）
- 提出：2026-11-16；来源 wontlost-ltd/aster-api#550（本 ADR 的 issue）与其「产品决策」评论，伞形 issue wontlost-ltd/aster-cloud#791
- 决策者：产品负责人已于 2026-11-16 拍板六项（§3）；本 ADR 在其上补齐数据模型、执法点、LayoutMap 存储与分期
- 相关：ADR 0014 / 0051（领域词汇；0051 R57 规定类型别名与导入别名的命名空间）、ADR 0022（关键词别名与 `StructuralAliasGrant`）、ADR 0049（AI 工作台准入 `User.aiWorkspaceEnabled`）、ADR 0015 §5 与 ADR 0017 §3（团队资源按团队 owner 的套餐计）、ADR 0028（显式块定界，互补）、ADR 0037 §10 与 ADR 0038（SourceIR 与 LayoutMap 正交；transform 层 DEFERRED）、ADR 0047（Cedar 导出）
- 子 issue：wontlost-ltd/aster-cloud#792（授权模型与管理页）、aster-cloud/aster-lang-ts#333（LSP 与 LayoutMap 包导出）、wontlost-ltd/aster-cloud#793（编辑器标签页）、#794（LayoutMap 视图与存储）、#795（文档）
- 实现仓：`wontlost-ltd/aster-cloud`（主）、`aster-cloud/aster-lang-ts`（LSP、LayoutMap 包导出）、`aster-dev`（改用包导出）。**aster-api 无代码改动**（理由见 §5.3、§7.2）
- 范围外：引导流程（cloud#796）、关键词别名面板（ADR 0022 已有）、ADR 0051 的解析语义、让 parser 接受任意排版（ADR 0028）、任意人类文档到 canonical 的变换（ADR 0038）

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| 授权模型 | 一张通用表 `CapabilityGrant`（主体为用户或团队，二选一）加一张团队限额表 `CapabilityTeamAllotment`；能力本身登记在代码里的注册表中，第一个能力键是 `structured_authoring` |
| 生效顺序 | 全局开关 → 套餐（Pro 及以上，按资源的套餐持有人判定）→ 用户授权 **或** 所在团队的团队授权 **或** 团队 owner 在限额内的委派授权；任一层不满足即无权 |
| 团队 owner | 平台管理员按团队设座位上限并可关掉委派（全局或单个团队）；owner 只能在座位内授权给本团队非 viewer 成员；成员离队即失效 |
| 缓存 | 授权与成员关系每次请求用 `freshDb` 现读（与 `StructuralAliasGrant` 相同）；全局开关沿用 `PlatformSetting` 的 60 秒 isolate 缓存；同一请求内只算一次 |
| 执法 | 服务端：授权管理 API、策略保存时的 layout 写入、新增的编辑器后端接口。语言特性（`Define`、`type X as`、`Use … as X`、词汇）**不设门**，任何人都能写、能编译、能执行 |
| 撤销后 | 已有策略照常有效、可编译、可执行；已存的 layout 仍可只读查看；只关掉编写界面与 layout 写入（对照代码见 §6） |
| LayoutMap 存储 | 新表 `PolicyVersionLayout`，每个版本至多一行，只插入不更新；**不进** `computeSourceEnvelope`，cloud 与 Java `PolicyVersion` 的 envelope 逐字节不变 |
| 保存校验 | `toCanonical(layout) === source` 硬校验；结构 span 的 canonical 只能含关键词、标点、空白（数字、字符串、标识符必须在内容 span 里，显示层改不了）；规模上限：源码 ≤ 12 KiB 且 ≤ 200 行、≤ 400 个 span |
| 防显示欺骗 | 版本评审、审批、对比一律先显示 canonical，display 是次级标签页；display 中只属于显示层的文字始终带标记；执行、证据、Cedar、What-If 永远不读 layout |
| 代码归属 | LayoutMap 收进 aster-lang-ts 新子路径 `@aster-cloud/aster-lang-ts/layout`（并从 `./browser` 再导出）；删除 cloud 与 aster-dev 的副本，示例改为引用包 |
| LSP | ts#333：声明类型、类型别名、导入别名、词汇术语进入补全；别名的 hover 与文档符号；新请求 `aster/canonicalPreview`。这些是语言服务，**不设门** |
| 分期 | 阶段 1：授权 + 编辑器；阶段 2：LayoutMap；阶段 3：把 `StructuralAliasGrant` 与 `aiWorkspaceEnabled` 迁到新模型（扩张 → 回填 → 切读 → 收缩）。cloud 每次加表都先单独提交迁移 PR |

---

## 1. 现状实证（已核对代码，aster-cloud `d24f4b2f`、aster-lang-ts `origin/main`、aster-api `a7a07513`）

### 1.1 三种互不相同的开关，没有一种支持团队

| 模式 | 位置 | 行为 | 缺口 |
|---|---|---|---|
| 全局开关 | `PlatformSetting`（`src/db/schema.ts:1386`），读写在 `src/lib/platform-settings.ts`；管理页 `FeatureFlagsCard` | 白名单键；读缺行或读库失败时取默认值（新功能默认 OFF）；每个 Worker isolate 缓存 60 秒，写入只清本 isolate | 只有全局；`POST /api/admin/platform-settings` 只对 `auth.dbEpoch` 写审计，其余键只记 `updatedBy` |
| 用户布尔列 | `User.aiWorkspaceEnabled`（`schema.ts:462`），`/api/admin/ai-workspace-access`（审计 `ai_workspace.access.grant/revoke`） | 准入 `isAiWorkspaceEnabledFor`（`src/lib/ai-workspace.ts:29`）：仅 SaaS 且配置了 `AI_WORKSPACE_URL`；`isAdmin` 或列为 true 即准入；环境名单 `AI_WORKSPACE_ENABLED_FOR` 作迁移期回退 | 没有团队，没有授予历史 |
| 用户授权表 | `StructuralAliasGrant`（`schema.ts:523`），partial UNIQUE `(userId) WHERE revokedAt IS NULL`；读用 `freshDb`（`src/lib/structural-alias-grants.ts:11`，#614：权限状态必须立即生效） | 保存新版本时服务端判定（`src/app/api/policies/route.ts:279`、`src/app/api/policies/[id]/route.ts:261`、`src/app/api/v1/policies/[id]/versions/route.ts:159`），未授权却提交结构词别名返回稳定错误码 | 没有团队；管理 API `src/app/api/admin/structural-alias-grants/route.ts` **不写审计** |

套餐门禁另有一套：领域词汇管理页靠 `getLexiconQuotaWithContext`（`src/lib/usage.ts:277`，`customLexicon` 能力，trial 过期自动降级写回）与 `pro-gate.tsx`；团队资源按团队 owner 的套餐计（`src/lib/team-plan.ts` `planHolderForTeam`）。注意 `src/lib/plans.ts:357` 已有一个 `hasCapability(plan, …)`（套餐能力），新解析函数不能重名（§4.4 用 `resolveEntitlement`）。

### 1.2 LayoutMap 只在演示里

- 实现有三份：aster-lang-ts `examples/jingyesi/layout-map.mjs`（73 行，含测试）、`aster-dev/src/lib/layout-map.ts`、`aster-cloud/src/lib/layout-map.ts`（`toCanonical` / `toDisplay` / `verifyContentParity`）。
- 使用方只有 cloud 的 `poem`（`src/config/poem-demo.ts`）与 `guyong`（`src/config/guyong-demo.ts`）演示及其编译测试。示例规模：《静夜思》8 个 span、canonical 81 字节；《斑点带子案》26 个 span、450 字节。
- 文件头已写明：`verifyContentParity` 只是 lint，防「看到的 ≠ 运行的」的真正防线是展示 canonical。`SourceIr.java` 的注释写明「20 字的诗可以，50 页 Policy 不行」。
- 策略版本没有 layout 存储；编辑器没有 display 视图。

### 1.3 编辑器与 LSP

- 编辑器：`src/components/policy/monaco-policy-editor.tsx`（Monaco + `useAsterLSP`，功能选择在 `src/lib/lsp-features.ts`），侧栏标签 settings / aliases / problems / ai / syntax / templates。
- LSP 进程由 cloud 的 `lsp-server.mjs` 网关经 stdio 拉起；浏览器用 `POST /api/lsp/ticket` 签发的一次性 ticket（`{v:1, sub, exp, nonce}`）握手。
- aster-lang-ts：通用补全的类型列表写死（`src/lsp/completion.ts:396`）；已有 `Driver with |` 的构造字段补全与 `x.` 的成员补全；词汇 hover 显示 `written → canonical`；没有返回整篇词汇解析结果的请求。实验能力挂在 `experimental.aster`（`src/lsp/server.ts:234`）。

### 1.4 编译与执行路径不看任何编写授权

- 保存：`assertCompilable(makeCompileValidator(userId, vocabulary), {source, locale, aliasSet, allowStructural})` 的唯一授权输入是结构词别名的 `allowStructural`；`Define`、`type X as`、`Use … as X`、词汇都不经过任何开关。
- 执行：`src/app/api/policies/[id]/execute/route.ts:360` 与 `src/app/api/v1/policies/[id]/execute/route.ts:311` 写明「冻结即信任」——冻结版本的别名在创建时已校验并进 envelope，执行时以 `allowStructural=true` 应用，**不重查授权**。

---

## 2. 目标与非目标

**目标**

1. 平台管理员能整体打开或关闭「结构化编写」，并能按用户、按团队授予；团队 owner 能在平台给定的限额内授予本团队成员。
2. 授权在服务端判定，不只是隐藏界面。
3. 编辑器为有权用户提供模型标签页（结构体、类型别名、导入别名）、词汇标签页与词汇规范预览。
4. 小型策略与展示性文本可以附带 LayoutMap，且「编译的就是评审者看到的 canonical」这一点有机械保证。
5. 现有两种开关（AI 工作台、结构词别名）有一条确定的迁移路径，最终都落在同一模型上。

**非目标**：给语言特性设门（§5.2）；改 ADR 0051 的解析语义；长策略的 LayoutMap；任意文本的自动变换（ADR 0038）；引导流程（cloud#796）。

---

## 3. 已定产品决策（2026-11-16，见 aster-api#550 评论）

| # | 决策 | 本 ADR 的落点 |
|---|---|---|
| D1 | **Pro 套餐与管理员授权同时满足**才可用 | §4.5 |
| D2 | `User.aiWorkspaceEnabled` 与 `StructuralAliasGrant` **后续阶段**迁到新的用户 / 团队授权模型；本 ADR 定义该阶段与迁移，首期不实施 | §9.3 |
| D3 | LayoutMap 只用于**展示性文本与小型策略**，有规模上限，等出现需求再放宽 | §7.6 |
| D4 | 「别名」**只指类型别名与导入别名**；关键词别名面板不在范围内 | §8 |
| D5 | **团队 owner 可在平台管理员设定的限额内授予** | §4.6、§4.7 |
| D6 | 引导（cloud#796）不在本 ADR 范围 | — |

---

## 4. 能力授权模型

### 4.1 能力注册表（代码，不进库）

能力的元数据写在 cloud `src/lib/capabilities/registry.ts`，库里只存授权事实。新增能力 = 加一条注册项，不改表结构。

```ts
type CapabilityKey = 'structured_authoring' | 'structural_alias' | 'ai_workspace';

interface CapabilityDescriptor {
  /** 全局开关的 PlatformSetting 键与缺行默认值。 */
  settingKey: string;
  settingDefault: boolean;
  /** 需要的套餐能力（PlanCapabilities 的键）；null = 不看套餐。 */
  planCapability: keyof PlanCapabilities | null;
  /** 是否允许团队 owner 在限额内委派授予。 */
  teamDelegation: boolean;
  /** 平台管理员是否隐式拥有（仅为保持 ai_workspace 的现有行为）。 */
  implicitForPlatformAdmin: boolean;
  /** 部署前提（如 ai_workspace 要求 SaaS + AI_WORKSPACE_URL）；不满足视为全局关闭。 */
  precondition?: () => boolean;
}
```

| 键 | 引入阶段 | `settingKey`（默认） | 套餐 | 团队委派 | 管理员隐式 |
|---|---|---|---|---|---|
| `structured_authoring` | 1 | `capability.structured_authoring.enabled`（**false**） | `structuredAuthoring` | 是 | 否 |
| `structural_alias` | 3 | `capability.structural_alias.enabled`（**true**，保持现状） | 无 | 否 | 否 |
| `ai_workspace` | 3 | `capability.ai_workspace.enabled`（**true**，保持现状） | 无 | 否 | 是（现状如此） |

`PlanCapabilities` 新增 `structuredAuthoring: boolean`：free = false；trial、pro、team（遗留）、enterprise = true。**本 ADR 的决定**：trial 与 `customLexicon` 一样视同 Pro（trial 过期由 `resolvePlan` 自动降级）。理由：D1 要求的是「付费档位的能力」，trial 是付费档位的试用；与词汇门禁口径一致，避免同一套餐在两个功能上表现不同。

### 4.2 数据模型

**`CapabilityGrant`**——一行一条授权事实，只软撤销，不物理删除（用户被硬删除时随 FK 级联）。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | text PK | UUID |
| `capability` | text not null | 注册表键；写入时校验存在于注册表 |
| `userId` | text null，FK `User.id` ON DELETE CASCADE | 用户主体 |
| `teamId` | text null | 团队主体（平台管理员授予整个团队） |
| `delegatingTeamId` | text null | 仅团队 owner 委派的用户授权：授予所依据的团队 |
| `source` | text not null | `platform_admin` / `team_owner` / `migration` |
| `grantedBy` | text null，FK `User.id` ON DELETE SET NULL | 授予者；`migration` 来源允许为空 |
| `grantedAt` | timestamp not null default now | |
| `revokedAt` | timestamp null | 非空即失效 |
| `revokedBy` | text null | |
| `revokeReason` | text null | `manual` / `member_removed` / `team_deleted` / `migration`。套餐降级与开关关闭不写撤销（§4.7） |
| `note` | text null | 管理员备注，≤ 500 字符 |

约束与索引：

- `CHECK (num_nonnulls("userId", "teamId") = 1)`——主体二选一。
- `CHECK (("source" = 'team_owner') = ("delegatingTeamId" IS NOT NULL))`，且 `delegatingTeamId` 非空时 `userId` 必须非空（团队 owner 只能授予个人）。
- 活跃唯一：`UNIQUE (capability, userId, COALESCE(delegatingTeamId, '')) WHERE revokedAt IS NULL AND userId IS NOT NULL`；`UNIQUE (capability, teamId) WHERE revokedAt IS NULL AND teamId IS NOT NULL`。与 `StructuralAliasGrant` 一样由 DB 兜住并发重复授予（插入撞唯一约束即视为幂等成功）。同一用户可以同时有平台授权与若干团队委派授权，它们是不同来源，各自可撤。
- 查询索引：`(capability, userId) WHERE revokedAt IS NULL`、`(capability, teamId) WHERE revokedAt IS NULL`、`(delegatingTeamId) WHERE revokedAt IS NULL`。
- `teamId` / `delegatingTeamId` 不设 FK，与现有 `TeamMember.teamId` 一致；团队删除时由删除事务显式撤销（§4.7），而且解析总是与 `TeamMember` 联查（§4.4），残留行不会授出任何权限。

**`CapabilityTeamAllotment`**——平台管理员给团队 owner 的委派额度。

| 列 | 类型 | 说明 |
|---|---|---|
| `capability` + `teamId` | 复合 PK | |
| `seatCap` | integer not null，`CHECK (seatCap BETWEEN 0 AND 10000)` | 该团队 owner 最多可委派的活跃授权数 |
| `ownerGrantingEnabled` | boolean not null default true | 单个团队的委派开关 |
| `updatedBy` / `updatedAt` | | |

没有行 = 该团队不能委派（等价 `seatCap = 0`）。

**为什么是通用表**：每加一个能力不再新建一张表、一个管理页、一个解析函数；团队主体与委派只需实现一次。按能力分表（方案 b）与加列（方案 c）的比较见 §10。

### 4.3 全局开关

- 每个能力一个 `PlatformSetting` 布尔键（§4.1 表），加入 `PLATFORM_SETTING_KEYS` 白名单与 `DEFAULTS`，在 `FeatureFlagsCard` 显示。`structured_authoring` 默认 OFF，管理员显式打开。
- 团队委派另有一个全局键 `capability.structured_authoring.team_delegation.enabled`（默认 true）：平台管理员一键关掉所有团队 owner 的委派，而不必逐个团队改额度。
- 读失败按现有约定 fail-OFF（取默认值；`structured_authoring` 的默认值是 OFF）。
- **新增**：`POST /api/admin/platform-settings` 对 `capability.*` 键做布尔类型校验，并写审计 `platform_setting.update`（§4.9，metadata 含键、旧值、新值）。其余既有键的写入行为不变（今天只有 `auth.dbEpoch` 写审计）。

开关只决定「现在是否可用」，不触碰授权行：关掉再打开，所有授权原样恢复。

### 4.4 生效解析

函数签名：`resolveEntitlement(userId, capability, { teamId }): Promise<{ allowed: boolean; denial?: Denial; sources: GrantSource[] }>`，其中 `teamId` 是**当前资源所属团队**（个人资源为 null）。

按顺序判定，任一步不满足立即返回无权，并给出第一处失败的原因（供界面解释「为什么不可用」）：

1. **部署前提**：注册项有 `precondition` 且不满足 → `denial = 'unavailable'`。
2. **全局开关**：`getSetting(settingKey)` 为 false → `denial = 'disabled'`。
3. **套餐**：`planCapability` 非空时，套餐持有人 = `planHolderForTeam(userId, teamId)`（个人资源是本人；用户是该团队成员时为团队 owner），`hasFeatureAccess(holder, planCapability)` 为 false → `denial = 'plan_required'`。
4. **授权来源**（任一即可）：
   - a. 平台管理员隐式（仅 `implicitForPlatformAdmin` 的能力）且 `User.isAdmin`；
   - b. 活跃的用户授权（`source ∈ {platform_admin, migration}`，`userId = 本人`）；
   - c. 活跃的团队授权，且本人是该团队的**非 viewer** 成员；
   - d. 活跃的团队 owner 委派授权，且本人仍是 `delegatingTeamId` 的非 viewer 成员，且该团队的额度行存在、`ownerGrantingEnabled = true`，且全局委派键为 true；
   - 都没有 → `denial = 'not_granted'`。

**参数的分工**：`teamId` **只用于第 3 步**（决定按谁的套餐判定）；第 4 步的授权匹配**与当前资源无关**，查的是该用户名下的全部授权与全部团队成员关系。第 4 步是一条 SQL：

```sql
SELECT g.id, g."userId", g."teamId", g."delegatingTeamId"
FROM "CapabilityGrant" g
LEFT JOIN "TeamMember" mt ON mt."teamId" = g."teamId"           AND mt."userId" = $user AND mt.role <> 'viewer'
LEFT JOIN "TeamMember" md ON md."teamId" = g."delegatingTeamId" AND md."userId" = $user AND md.role <> 'viewer'
LEFT JOIN "CapabilityTeamAllotment" a ON a.capability = g.capability AND a."teamId" = g."delegatingTeamId"
WHERE g.capability = $cap AND g."revokedAt" IS NULL
  AND (  (g."userId" = $user AND g."delegatingTeamId" IS NULL)                        -- b
      OR (g."teamId" IS NOT NULL AND mt.id IS NOT NULL)                               -- c
      OR (g."userId" = $user AND md.id IS NOT NULL AND a."ownerGrantingEnabled"
          AND $delegationSwitchOn) )                                                  -- d
```

返回全部命中的来源（`direct` / `team:<id>` / `delegated:<id>`，加第 4a 步的 `admin`），管理页和编辑器据此显示「通过哪个来源获得」。来源有多条时任一即可，彼此之间没有优先级；套餐只判一次（第 3 步），不按来源分别判定。

**团队授权的作用范围**：团队授权与委派授权让成员**本人**获得能力，不限于该团队的策略；但第 3 步的套餐按当前资源判定。因此 Pro 团队里的 Free 成员在团队策略上可用，在自己的个人策略上不可用——这与 ADR 0015 §5 / `team-plan.ts` 的「团队资源看 owner 套餐，个人资源看本人套餐」一致，不另造规则。

**viewer 不计**：viewer 不能编辑，授予它没有意义；委派 API 拒绝 viewer 目标，团队授权对 viewer 不生效。

### 4.5 套餐（D1）

套餐是第 3 步，与授权是「且」的关系：授权不能绕过套餐，套餐也不能代替授权。降级（含 trial 到期）后能力自动失效、授权行保留，重新升级即恢复；降级不写撤销记录。on-prem 沿用现有 `plan` 口径（与词汇 Pro 门禁一致），不另引入 license 判定。

### 4.6 团队 owner 的委派限额（D5）

| 限额 | 决定 |
|---|---|
| 谁可以委派 | `Team.ownerId` 本人（与 `team-plan.ts` 的 owner 定义一致）。团队 `admin` 角色**不能**（见 §14 Q1） |
| 能委派给谁 | 本团队的非 viewer 成员；只能是个人，不能是别的团队 |
| 座位上限 | `CapabilityTeamAllotment.seatCap`，由平台管理员按团队设定；没有额度行 = 0。占座 = 该团队「`revokedAt IS NULL` 且对象仍是该团队成员（任何角色）」的委派行。被降为 viewer 而暂停的委派**仍占座**（行仍活跃，升回即恢复，不会出现恢复时无座的情况）；owner 想释放座位就撤销它 |
| 并发 | 委派写入在事务内先 `lockTeamRow(tx, teamId)`（`team-plan.ts` 现有的 `SELECT … FOR UPDATE`），再计数、再插入，与转让所有权、接受邀请串行化，不会超座 |
| 平台关闭委派 | 两级：全局键 `…team_delegation.enabled`，以及单个团队的 `ownerGrantingEnabled`。关闭后委派授权在解析中**暂停**（§4.4 第 4d 步不再命中），行不撤销；重新打开即恢复。关闭期间 owner 不能新增委派，可以撤销 |
| 下调座位 | 新上限低于当前活跃委派数时返回 409 `seat_cap_below_active`（附当前数），由管理员或 owner 先撤销再下调；不自动挑人撤销 |
| owner 能看到什么 | 本团队的额度、已用座位、委派名单；**看不到**平台管理员给成员的直接授权（那不是团队的事） |
| 套餐 | owner 委派不改变 §4.5：被委派成员在团队策略上用 owner 的套餐判定 |

### 4.7 撤销与级联

| 事件 | 效果 | 实现 |
|---|---|---|
| 管理员撤销用户授权 / 团队授权 | 该来源立即失效（下一次请求） | `revokedAt = now()`，`revokeReason = 'manual'` |
| owner 撤销委派 | 同上，释放一个座位 | 同上 |
| 成员被移出或离开团队 | 该团队的团队授权对其失效；其在该团队的委派授权失效 | 解析与 `TeamMember` 联查，**删除成员行即生效**；另在成员移除路由（`src/app/api/teams/[teamId]/members/[memberId]/route.ts`，与现有 `revokeTeamKeys` 同处）撤销其委派行，`revokeReason = 'member_removed'`，失败只告警（安全性不依赖这一步，只为座位统计与审计清晰） |
| 成员降为 viewer / 升回 | 团队授权与委派对其暂停 / 恢复；委派仍占座（§4.6） | 只靠解析时的角色过滤，角色变更路由（`members/[memberId]/route.ts:104`、`transfer/route.ts:66–72`）**无需改动** |
| 团队删除 | 团队授权与该团队全部委派撤销，额度行删除；该团队的策略按现有逻辑转为 owner 个人策略（`teamId = null`），此后对这些策略的判定按 owner 本人套餐与授权；已附的 layout 不受影响（只读查看不设门） | 在团队删除事务（`src/app/api/teams/[teamId]/route.ts:175–194`，先删成员、再把策略 `teamId` 置空、最后删团队）内同事务执行撤销，`revokeReason = 'team_deleted'` |
| 团队转让所有权 | 委派保持；新 owner 接管管理 | 委派依据的是团队，不是前 owner 个人；`grantedBy` 保留历史 |
| 平台或团队关闭委派 | 委派暂停，不撤销 | §4.6 |
| 全局开关关闭、套餐降级 | 所有人失效，授权行不动 | §4.3、§4.5 |
| 用户硬删除 | 用户主体行随 FK 级联删除；审计日志保留 | FK `ON DELETE CASCADE` |

撤销只影响**今后的编写**，不影响已有内容（§6）。

**需要改动的团队生命周期写路径（完整清单）**：成员移除与自行离开（同一路由 `members/[memberId]/route.ts:201`，事务外、与 `revokeTeamKeys` 同处，失败只告警）、团队删除（同事务）。角色变更与所有权转让不需要改动。所有路径的正确性都不依赖这些清理成功：解析总是联查 `TeamMember` 现读；计座（委派写入时的检查与 `GET /api/teams/[teamId]/capabilities/…/grants`）同样联查成员关系，所以清理失败既不会让离队者保留能力，也不会多占座位，只是留下一条未写撤销原因的历史行；不需要后台补偿任务。

### 4.8 缓存与失效

| 读取 | 方式 | 生效延迟 |
|---|---|---|
| 授权行、团队成员、额度行 | `freshDb`（绕过 Hyperdrive 查询缓存，#614 / #624），每次请求现读，不跨请求缓存 | 下一次请求 |
| 全局开关 | `getSetting`，每个 isolate 60 秒 TTL（现有预算） | ≤ 60 秒 |
| 套餐 | `hasFeatureAccess`（现有路径） | 与词汇 Pro 门禁相同 |
| 同一请求内 | RSC 页面用 React `cache()` 包一层，API 路由在处理函数内只调一次 | — |

已经打开的编辑器在刷新前保留它加载时的界面；所有写入（layout 保存、新增后端接口）在服务端重新判定，所以撤销后的第一次保存就会按新状态处理。不引入 KV 或跨 isolate 失效通道：判定是一条带索引的查询，只在页面加载与保存时发生。

### 4.9 审计事件

全部写 `AuditLog`（`userId` = 操作者；`teamId` 填相关团队，使团队审计视图可见）：

| `action` | `resource` / `resourceId` | `metadata` |
|---|---|---|
| `capability.grant` | `capability_grant` / 授权 id | `capability, subjectType, subjectId, source, delegatingTeamId?, note?` |
| `capability.revoke` | `capability_grant` / 授权 id | 同上 + `reason` |
| `capability.allotment.update` | `team` / teamId | `capability, seatCap {old,new}, ownerGrantingEnabled {old,new}` |
| `platform_setting.update` | `platform-setting` / 键 | `old, new`（只对 `capability.*` 键；其余键行为不变） |
| `policy.version.create`（既有，扩展） | 既有 | 增加 `layoutSha256`（有 layout 时）与 `layoutDropped`（§7.5） |

成员移除、团队删除引起的级联撤销各写一条 `capability.revoke`（`reason` 区分）。拒绝访问不写审计（只是 403），只计指标。

### 4.10 接口与页面

| 接口 | 权限 | 说明 |
|---|---|---|
| `GET /api/admin/capabilities/[capability]/grants?q=&subject=user\|team` | `requireAdmin` | 搜用户与团队；每个用户返回解析后的来源列表 |
| `POST` / `DELETE` 同上 | `requireAdmin` + `requireLicenseWriteOk` | 授予 / 撤销用户或团队授权；400 非法载荷、404 能力或主体不存在 |
| `PUT /api/admin/capabilities/[capability]/teams/[teamId]/allotment` | 同上 | 设 `seatCap`、`ownerGrantingEnabled`；409 `seat_cap_below_active` |
| `GET` / `POST` / `DELETE /api/teams/[teamId]/capabilities/[capability]/grants` | 团队 owner + `requireLicenseWriteOk` | 委派；403 `team_delegation_disabled`、409 `seat_cap_reached`、400 `not_team_member` / `viewer_not_eligible` |

页面：平台 `/admin/capabilities/structured-authoring`（仿 `/admin/ai-workspace-access`：用户 / 团队搜索、授予、撤销、来源列、团队额度编辑）；团队设置页新增「结构化编写座位」小节（仅 owner 可见）。编辑器页（`policies/new/page.tsx`、`policies/[id]/edit/page.tsx`）在服务端解析后以 `structuredAuthoring: { allowed, denial, sources }` 传入，与今天的 `allowStructuralAliases` 同路。i18n：en / zh / de。

---

## 5. 执法点

### 5.1 服务端判定的位置

| 位置 | 判定 |
|---|---|
| §4.10 全部写接口 | 平台管理员或团队 owner 身份；`requireLicenseWriteOk` |
| 策略保存的三条建版本路径（`POST /api/policies`、`PUT /api/policies/[id]`、`POST /api/v1/policies/[id]/versions`） | **新增的 layout 门禁**：路由在事务外调用 `resolveEntitlement(userId, 'structured_authoring', {teamId: policy.teamId})`（与今天在事务外读 `getStructuralAliasGrant` 同一位置与理由），把结果作为 `allowLayout` 传入 `createVersion`；`createVersion` 是三态规则与校验（§7.5）的唯一实现点，`allowLayout` 为假而请求带 layout 时抛出，路由转 403 `structured_authoring_required`。**既有的结构词别名门禁**（`allowStructural`）位置与语义不变，两者互不影响 |
| 阶段 1 若为编辑器标签新增后端接口（例如按角色列出作用域内词汇的接口） | 每个接口入口判定同一能力；现有词汇管理接口不变（仍只看套餐） |
| 编辑器页的服务端渲染 | 无权时不下发标签页数据与 props，界面与今天逐字节一致（cloud#793 验收） |

### 5.2 刻意不设门的部分

| 部分 | 不设门的理由 |
|---|---|
| 语言特性本身：`Define X with …`、`type X as …`、`Use … as X`、词汇解析 | 它们是 Aster 语言的一部分，今天任何人都能写、能编译、能执行（§1.4）。设门等于让已有策略在某些人手里不可保存，破坏现有用户；能力卖的是「编写辅助」，不是语言 |
| LSP 补全、hover、文档符号、`aster/canonicalPreview` | 结果只来自用户自己能读的文档与自己的词汇，没有可保护的东西；写死的类型列表本来就是缺陷。若在 LSP 网关按 ticket 设门，需要 ticket v2 与网关改写 `initialize`，复杂度换不来任何保护。界面上的入口（预览窗格、标签页）仍按能力显示 |
| 已存 layout 的只读查看 | 评审者、审批者往往不是作者，也未必有权；他们需要看到 display 才能判断有无误导（§7.7）。只读查看不是编写 |
| 领域词汇管理页 | 现状只看套餐，保持不变；本能力只管编辑器里的新入口 |

### 5.3 aster-api

不涉及。aster-api 不知道 cloud 的用户授权，也不需要知道：layout 不进 envelope（§7.2），语言特性不设门，结构词别名的 `allowStructural` 仍由 cloud 判定后经已有的 HMAC 签名请求下传（阶段 3 只换 cloud 侧的判定函数，下传协议不变）。

---

## 6. 撤销后的既有内容（对照代码）

下表按能力分开：撤销 `structured_authoring` 只关掉编辑器辅助与 layout 写入；撤销 `structural_alias`（阶段 3 后）沿用今天的规则，只阻断「含结构词别名的新版本」；撤销 `ai_workspace` 只关掉工作台入口。三者互不连带。

| 内容 | 撤销、降级或全局关闭之后 | 依据 |
|---|---|---|
| 撤销 `structured_authoring`：用了结构体、类型别名、导入别名、词汇的策略 | 照常有效、可编译、可执行；可以继续编辑与保存新版本（只是没有编写辅助） | 编译与执行路径不读 `structured_authoring`（§1.4）；本 ADR 不新增 |
| 已附 layout 的版本 | 版本不变，layout 仍可只读查看；执行、证据、Cedar 导出本来就不读 layout | §7.2、§7.7 |
| 无权用户在有 layout 的策略上建新版本 | 新版本不带 layout，响应带 `layoutDropped: true`，编辑器保存前提示；不拒绝保存 | §7.5 三态规则。拒绝保存会让无权队友改不了团队策略，比丢掉显示层更糟 |
| 结构词别名（阶段 3 后） | 行为不变：已冻结版本执行时「冻结即信任」；撤销后不能再基于结构词别名产出新版本 | `execute/route.ts:360`、`[id]/route.ts:257–266` 的现有规则，阶段 3 只替换判定函数 |
| AI 工作台（阶段 3 后） | 行为不变：失去准入后交接路由返回 404，已有会话仍在工作台库中 | `ai-workspace/handoff/route.ts` 现有行为 |

---

## 7. 策略的 LayoutMap

### 7.1 定位

LayoutMap 是**同一份 canonical 源码的第二种排版**，只给人看：

- `PolicyVersion.content` 仍是唯一的编译输入；layout 不参与编译、执行、证据、哈希、Cedar 导出、What-If、回放。
- 一个 layout 属于一个版本；源码一变，旧 layout 自然失效（§7.5 的往返校验会拒绝它）。
- 「canonical」在本节指**版本存储的编译源码**（可能是用词汇写的）。§8.2 的「词汇规范预览」是另一回事：它把词汇写法换成规范名，只是评审辅助。

### 7.2 存储：版本外的独立表，不进 envelope

**`PolicyVersionLayout`**

| 列 | 说明 |
|---|---|
| `policyVersionId` | PK，FK `PolicyVersion.id` ON DELETE CASCADE |
| `formatVersion` | integer，当前 1 |
| `layout` | text，§7.3 的确定性序列化（不用 jsonb：jsonb 会重排键，哈希无法复现） |
| `layoutSha256` | layout 文本的 SHA-256 |
| `sourceSha256` | 插入时 `toCanonical(layout)` 的 SHA-256（= 版本 `content` 的 SHA-256），读取时用来快速发现不一致 |
| `createdBy` / `createdAt` | |

- 与 `createVersion` 在同一事务插入；**只插入，不更新、不删除**，没有修改接口。想换 layout 就建新版本——与 `aliasSet`「不可变」的既有约定一致。
- 读取（显示 display 前）重新验证 `sha256(layout) = layoutSha256` 且 `toCanonical(parse(layout)) = content`；任一不符则不渲染 display，只显示 canonical 与「显示层数据不一致」提示，并记错误日志。这是**一致性检测**：能发现损坏、迁移错误与只改了一列的直接改库，但有库写权限的人同时改 layout 与哈希就能通过。本 ADR 不为显示层提供抗蓄意篡改的保证——不需要：layout 改不了执行内容，往返校验保证 display 的内容 span 就是 `content`，而 `content` 自身由 envelope 保护。

**为什么不进 `computeSourceEnvelope`**：envelope 的定义是「完整编译输入」（content、aliasSet、locale、toolchainId、scopesJson，ADR 0022 §11.5、ADR 0051 §7），layout 不是编译输入。放进去要改 cloud `src/lib/policy-alias.ts` 与 Java `PolicyVersion.computeSourceEnvelope`、改共享向量 `envelope-vectors.json`、给存量行重算 envelope，并让「改显示层」变成「改编译身份」，而它们本来无关。layout 与编译的关系已由往返校验机械保证。**结论：两侧 envelope 逐字节不变**，阶段 2 加一条测试：同一版本附与不附 layout，`sourceEnvelopeSha256` 相同。

**为什么不是 `PolicyVersion` 上的列**：版本列表与对比查询不必拖着最多 48 KiB 的文本；没有 layout 的版本（几乎全部）零开销；功能可以整体下线而不碰版本表。

### 7.3 格式与序列化

```ts
type LayoutSpan = { text: string } | { canonical: string; display: string };
interface PolicyLayout { v: 1; spans: LayoutSpan[] }
```

`serializeLayout` 输出固定形态：`{"v":1,"spans":[…]}`，内容 span 为 `{"text":…}`，结构 span 为 `{"canonical":…,"display":…}`，键序固定、无多余空白、字符串按 `JSON.stringify` 转义。相邻且同类的内容 span 在序列化前合并（同一排版只有一种表示，哈希才稳定）。`parseLayout` 拒绝未知键、空 span 列表、空 `text`、`canonical` 为空串的结构 span。

### 7.4 怎样创建

主路径是**从 canonical 派生**：

1. 初始 layout = 整篇源码一个内容 span（恒等排版）。
2. 作者在 canonical 上选一段，标为「结构」并填写 display（可为空串以隐藏）；或把结构 span 恢复为内容。
3. 每一步只是切分与改 display，`toCanonical` 构造上恒等于源码。

另支持**导入**手写的 span JSON（演示与高级用户），保存时照常全量校验。

不采用「编辑 display 文本、由系统对比 canonical 反推 span」：对齐有歧义（同一段文字在 canonical 中出现多次时，选哪一处由算法决定，作者看不出来），而歧义正是显示欺骗的来源。

### 7.5 保存规则

请求体的 `layout` 字段三态（沿用 `aliasSet` 的三态先例，`[id]/route.ts:163`）：

| 取值 | 新版本的 layout |
|---|---|
| 省略 | 上一活跃版本有 layout，且 `toCanonical(旧 layout) === 新源码`，且用户有权 → 沿用；否则不带，若上一版本有 layout 则响应 `layoutDropped: true` |
| `null` | 不带 |
| 对象 | 用户必须有权（否则 403 `structured_authoring_required`），且通过下列全部校验 |

校验（`@aster-cloud/aster-lang-ts/layout` 的 `validatePolicyLayout`，cloud 与编辑器共用同一实现；任一失败返回 400 与稳定码）：

| 码 | 规则 |
|---|---|
| `layout_invalid_format` | §7.3 的结构校验 |
| `layout_too_large` | §7.6 任一上限超出 |
| `layout_roundtrip_mismatch` | `toCanonical(layout) !== source`，报出第一个不一致的偏移 |
| `layout_structural_hides_value` | 用版本的 locale 词法表（含该版本冻结的关键词别名）对 source 分词，任一**非**关键词、标点、空白、注释的 token（标识符、数字、字符串、布尔以外的字面量等）与结构 span 重叠即拒绝。含义：会影响判定结果的值与名字只能出现在内容 span 里，而内容 span 在 display 中原样出现，显示层无法改写 |
| `layout_content_parity` | 现有 `verifyContentParity`（至少一个非空内容 span；结构 span 的 canonical 不含字符串字面量）。对策略从 lint 升为硬规则 |

《静夜思》的结构 span 只有标点、换行与空格，满足全部规则；guyong 演示的结构 span 是标点与关键词别名（「我曾问」「记着」「是」「答一句」），预期满足，阶段 2 以测试确认。《斑点带子案》的结构 span 含函数名「揪出真凶」，不满足 `layout_structural_hides_value`——它仍是语言仓的示例（只跑 lint），不能作为策略保存。这正是规则要拦的情形：函数名被藏进显示层后，读者看不到真实调用的是什么。

### 7.6 规模上限（D3）

常量 `LAYOUT_LIMITS` 定义在包里，编辑器与保存校验共用：

| 上限 | 值 | 理由 |
|---|---|---|
| 源码字节（UTF-8） | ≤ 12 288（12 KiB） | aster-lang-test 语料 416 个 `.aster`（`c35a564`）：p95 930 字节，p99 8 280 字节，最大 14 756 字节；12 KiB 覆盖 415 / 416 个样本 |
| 源码行数 | ≤ 200 | 同一语料 p99 为 126 行，最大 225 行；唯一超出的就是那个最大样本 |
| span 数 | ≤ 400 | 《斑点带子案》26 个 span / 450 字节，约每 17 字节一个；400 个 span 是一次评审能逐个核对的量级。超过它，评审者实际上不会逐 span 对照，「canonical 优先」的防线就名存实亡 |
| 序列化后 layout | ≤ 48 KiB | 12 KiB 源码在 display 扩写一倍、再加 JSON 转义的余量 |
| display 总长 | ≤ 24 KiB | 源码上限的 2 倍，容纳《斑点带子案》式的散文化扩写 |
| 单个结构 span 的 display | ≤ 256 个字符 | 示例中最长的约 15 个字符；结构 span 是连接词与标点，不是段落 |

源码超限时编辑器不提供 layout 入口并说明原因；保存时服务端复核。放宽上限需修订本 ADR，并以真实需求为依据（D3）。

### 7.7 防显示欺骗

1. **canonical 优先**：版本评审（`policy-review-panel.tsx`）、审批、版本对比（`version-compare-panel.tsx`）默认且首先显示 canonical；display 是次级标签页，标注「显示排版，不参与执行」。
2. **对比只比 canonical**：版本 diff 以 canonical 为准；layout 变化单列一行「显示排版已变更」（比 `layoutSha256`），不混入源码 diff。
3. **显示层文字始终可辨认**：display 视图里，结构 span 的 display 文字用独立样式渲染，悬停显示对应的 canonical；内容 span 正常渲染。读者总能分清哪些字是只给人看的。
4. **值与名字不能藏**：§7.5 `layout_structural_hides_value`。
5. **下游不读 layout**：执行、证据导出、Cedar 导出（ADR 0047）、What-If、回放、Guard 部署只读 `content`。
6. **不一致可见**：§7.2 读取时复验（一致性检测，不是防篡改签名）。
7. **词汇宏另有入口**：内容 span 原样显示的是源码写法；词汇字面量宏（如 guyong 演示把触发词解析为另一句字符串）改变的是编译含义而不是排版，LayoutMap 既不制造也不消除它。评审者通过 §8.2 的词汇规范预览看到宏展开后的值；这一点写进评审面板的说明。

### 7.8 诊断与 trace 位置映射

包导出 `canonicalToDisplay(layout, offset)` 与 `displayToCanonical(layout, offset)`：

- 落在内容 span 内的偏移：按 span 内相对位置一一对应。
- 落在结构 span 内的偏移：映射到该 span 的整段 display 区间；display 为空串时映射到插入点（前后两个内容 span 之间的位置），界面在该处画一个标记。
- 诊断（编译器与 LSP）与 decision trace 的 span（ADR 0032）都先按 canonical 算，再用这两个函数投到 display；display 视图与 canonical 视图的高亮同步也用它们。
- 不变式（包内测试）：对每个内容 span 内的偏移 p，`displayToCanonical(canonicalToDisplay(p)) === p`。

### 7.9 与 SourceIR、ADR 0028 / 0037 / 0038 的关系

- SourceIR（ADR 0037 §10）绑定一个文档，LayoutMap 是同一文档的第二种排版，两者正交（ADR 0038 §2 已实测）。本 ADR 不改 SourceIR，也不把 layout 塞进 SourceIR。
- ADR 0038 的 transform 层仍 DEFERRED：本 ADR 的 layout 是人工给定、可机械校验的映射，正是 ADR 0038 §4.2 第 3 条所说的「手写特例」；它的存在不构成 ADR 0038 §6 的触发条件。
- ADR 0028（显式块定界）让源码本身更接近自由排版，与 LayoutMap 互补，互不依赖。
- 规模上限（§7.6）正是 `SourceIr.java` 注释所说边界的落地：短诗可以，长策略不行。

### 7.10 代码归属：收进 aster-lang-ts

- 新增子路径 `@aster-cloud/aster-lang-ts/layout`（纯函数，无 Node 依赖，浏览器与 Workers 可用），并从 `./browser` 再导出。内容：`LayoutSpan`、`PolicyLayout`、`toCanonical`、`toDisplay`、`verifyContentParity`、`serializeLayout`、`parseLayout`、`validatePolicyLayout`、`canonicalToDisplay`、`displayToCanonical`、`LAYOUT_LIMITS`。
- `examples/jingyesi`、`examples/sherlock` 改为从包内源码导入，删除 `examples/jingyesi/layout-map.mjs`（测试迁入包的测试目录）。
- cloud 的演示（`poem-demo.ts`、`guyong-demo.ts`）改用包导出，删除 `src/lib/layout-map.ts`；aster-dev 同样删除 `src/lib/layout-map.ts`。
- 不做 Java 版本：layout 只在 cloud 与编辑器使用，aster-api 与 core 不读它；若将来 aster-api 需要校验，再按 ADR 0045 的「副本 + parity 测试」办理。

---

## 8. LSP 与编辑器（D4：别名只指类型别名与导入别名）

### 8.1 aster-lang-ts#333

- **类型位置补全**：本文档声明的 Data、类型别名（`type X as …`）、导入别名（`Use … as X`），加内置类型（按 lexicon 本地化），替换 `completion.ts:396` 的写死列表。`detail` 显示别名目标。
- **表达式位置补全**：词汇术语按 ADR 0051 角色过滤（类型、字段、枚举成员、函数），插入本地化写法，`detail` 显示规范名与角色。须覆盖 ADR 0051 回归集中「结构体与字段共用同一个本地化词」的情形。
- **hover 与文档符号**：`type X as T` 显示 `T`；`Use M as X` 显示 `M`。导入别名只到模块名为止：跨模块签名在生产路径上不可达（ADR 0051 §16.3 R58 延后），不补全导入模块的成员。
- **`aster/canonicalPreview`**：§8.2。
- 以上都是语言服务，不设门（§5.2）。en-US、de-DE、zh-CN 各有单测；发版后 cloud 升级依赖。

### 8.2 `aster/canonicalPreview` 契约

- 服务端在 `experimental.aster.canonicalPreview: true` 中声明，客户端按能力探测（与 `localizedParsing` 同法）。
- 请求：`{ textDocument: { uri } }`。响应：`{ text: string; mappings: Array<{ source: [start, end]; preview: [start, end]; canonical: string; role: string }>; resolverVersion: number }`。
- 生成规则：按 ADR 0051 的解析结果，把每个经词汇解析的标识符 token 替换为规范名，词汇字面量宏替换为它展开后的字面量，关键词与其余文字原样保留；`mappings` 只列被替换的 token，其余位置按累计偏移推出。
- 忠实性验收：对 jingyesi、sherlock、vocab-de 语料，「不带词汇编译预览文本」与「带词汇编译源码」得到的 Core IR 相同（忽略 origin）。不一致的样本逐个列出原因，不设豁免。
- 预览只给人看，不保存、不编译、不参与 envelope。

### 8.3 编辑器（cloud#793，能力为真时才出现）

- **模型标签页**：结构体（含字段）、类型别名、导入别名的大纲，点击跳到定义；「新建结构体」「添加字段」「新建类型别名」表单经 lexicon 生成本地化源码插入，作者不手写关键词。
- **词汇标签页**：作用域内术语按 ADR 0051 角色分组、可搜索；点击插入本地化写法；「把选中文字加为术语」打开现有 `vocabulary-dialog.tsx`；链接到 `/domain-vocabularies`（词汇的增删仍受现有套餐门禁）。
- **词汇规范预览**：只读分栏，内容来自 `aster/canonicalPreview`，光标同步。
- **LayoutMap**（阶段 2，cloud#794）：编辑器侧栏开关切换 display / canonical 并排视图，span 级高亮同步；span 编辑器按 §7.4。
- 新补全与 hover 经 `selectLspFeatures` 按能力探测接入；命令面板（`editor-palette.tsx`）加入新动作。
- 能力为假：编辑器与今天逐字节一致（快照测试）。

---

## 9. 分期与上线

**cloud 的迁移拆分规则**：CI `migration-split`（`scripts/ci/check-migration-split.mjs`）要求 `drizzle/*.sql` 与 `src/` 业务代码分开提交（`schema.ts`、`drizzle/meta/`、测试除外），否则必须打 `migration-and-code` 标签并在 PR 正文写 `## 迁移窗口容错`。本 ADR 的每次加表、改表都**先单独合入只含迁移的 PR**，生产迁移确认应用后再合代码 PR；不使用 `migration-and-code` 豁免。

### 9.1 阶段 1：授权 + 编辑器

1. cloud 迁移 PR：`CapabilityGrant`、`CapabilityTeamAllotment`（纯新增表，旧代码不读，无窗口问题）。
2. cloud 代码 PR（#792）：注册表、`resolveEntitlement`、`PlanCapabilities.structuredAuthoring`、`PlatformSetting` 键与审计、平台管理页与团队座位页、§4.10 接口、成员移除与团队删除的级联、编辑器 props。
3. aster-lang-ts（#333）：补全、hover、文档符号、`aster/canonicalPreview`、`./layout` 子路径（阶段 2 才在 cloud 使用，但随同一版本发布）；发版。
4. cloud 代码 PR（#793）：升级依赖；模型、词汇标签页与预览。
5. 上线：开关默认 OFF → 管理员给内部团队授权验证 → 按需开放。

### 9.2 阶段 2：LayoutMap

1. cloud 迁移 PR：`PolicyVersionLayout`。
2. cloud 代码 PR（#794）：`createVersion` 内的三态规则与校验、读取复验、并排视图与 span 编辑器、诊断与 trace 映射、评审 / 审批 / 对比的 canonical 优先、演示改用包导出并删除 `src/lib/layout-map.ts`。
3. aster-dev：改用包导出，删除副本。
4. aster-api：无改动（§7.2）。

### 9.3 阶段 3：迁移旧开关（D2；首期不实施）

**阶段 3 不新建表**：目标表 `CapabilityGrant` 在阶段 1 第 1 步已由迁移 PR 创建并上线（其 `source` 已含 `migration`、`grantedBy` 已可为空），所以 3a 的双写在部署时目标表已存在，符合「迁移先行」规则。若届时需要改表（例如新增列），则在 3a 之前再加一个只含迁移的 PR（记为 3-0）。之后按扩张 → 回填 → 切读 → 收缩四步，每步可单独回退：

| 步 | PR 类型 | 内容 | 回退 |
|---|---|---|---|
| 3a | 代码 | 注册 `structural_alias` 与 `ai_workspace`（§4.1）；旧管理接口**双写**：写旧结构的同时写 `CapabilityGrant`（`StructuralAliasGrant` 行用**同一 id**；`aiWorkspaceEnabled` 置 true / false 对应授予 / 撤销 `source = platform_admin`）。读仍走旧路径 | 撤掉双写 |
| 3b | 迁移 | 幂等回填：`StructuralAliasGrant` 全部行（含已撤销）按 id `INSERT … ON CONFLICT (id) DO UPDATE SET revokedAt = EXCLUDED.revokedAt`，`source = migration`，保留 `grantedBy` / `grantedAt`；`aiWorkspaceEnabled = true` 且无活跃授权的用户插入 `source = migration`、`grantedBy = NULL`。3a 已上线，回填期间没有漏写的窗口 | 删除 `source = migration` 的行 |
| 3c | 代码 | 读切到 `resolveEntitlement`：`getStructuralAliasGrant` 与 `isAiWorkspaceEnabledFor` 改为调用它（保留 `ai_workspace` 的 SaaS + URL 前提、管理员隐式与 `AI_WORKSPACE_ENABLED_FOR` 回退）；两个旧管理页并入通用管理页；结构词别名的授予 / 撤销开始写审计（今天没有）；停止写旧结构 | 读切回旧路径（旧结构在 3c 之前一直双写，数据完整） |
| 3d | 迁移（3c 上线满一个发布周期后） | 删除 `StructuralAliasGrant` 表与 `User.aiWorkspaceEnabled` 列 | 不可逆；执行前确认 3c 稳定、无读者 |

迁移后两个能力获得团队授权（平台管理员可授予团队）；团队 owner 委派对它们默认关闭（注册项 `teamDelegation: false`），是否开放另议。结构词别名的保存与执行语义、`allowStructural` 经 HMAC 下传 aster-api 的协议都不变。`AI_WORKSPACE_ENABLED_FOR` 环境名单的清理不在本 ADR 范围。

---

## 10. 备选方案（已否决）

| 方案 | 否决理由 |
|---|---|
| (b) 每个能力一张授权表（复制 `StructuralAliasGrant`） | 团队主体、委派、座位、级联、管理页都要按能力重复实现；D2 的迁移会变成三张表三套逻辑 |
| (c) 在 `User` / `Team` 上加布尔列 | 没有授予历史与授予者，做不了委派与座位；`aiWorkspaceEnabled` 正是这种模式的缺口 |
| 多态主体 `subjectType + subjectId` 单列 | 无法给用户主体加 FK 级联；两列 + CHECK 同样表达二选一且保住引用完整性 |
| 团队授权只在该团队的策略上生效 | 与 D5「授予成员」的直觉不符；套餐已按资源判定（§4.4），再按资源限制授权是第二套作用域规则 |
| 关闭委派时直接撤销委派行 | 不可逆；开关应当可以关了再开 |
| 授权结果跨请求缓存（KV / isolate） | 撤销要等缓存过期，违背 #614 的教训；一条索引查询的成本不值得换这个风险 |
| LSP 网关按 ticket 设门 | §5.2：没有可保护的东西，复杂度白付 |
| 给语言特性设门（保存时拒绝无权用户的 `Define` / 别名） | 破坏现有用户与已有策略；能力是编写辅助，不是语言 |
| layout 进 `computeSourceEnvelope` | §7.2：layout 不是编译输入；要改两侧 envelope、共享向量与存量行；往返校验已给出所需保证 |
| layout 作为 `PolicyVersion` 的 jsonb 列 | jsonb 重排键导致哈希不可复现；版本查询背上大字段 |
| 由 display 文本对比反推 span | 对齐歧义即欺骗来源（§7.4） |
| 用 ADR 0038 的 transform 层承载 layout | ADR 0038 仍 DEFERRED，且 transform 面向任意人类文本，过重 |

---

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 显示层仍可写出误导性的连接词（如把 `else` 显示成带否定意味的话） | 评审、审批 canonical 优先；显示层文字始终有标记；值与名字不能藏；规模上限让逐 span 核对可行（§7.6、§7.7）。这是 D3 把范围限定在展示文本与小策略的原因 |
| 团队授权让 Free 成员以为「已授权却不可用」 | 解析返回 `denial = 'plan_required'` 与来源列表，界面说明「个人策略需本人 Pro」 |
| 全局开关 60 秒传播 | 现有预算；开关是功能开关而非安全边界 |
| 成员移除路由的级联撤销失败 | 安全性靠解析时联查 `TeamMember`，级联只是统计与审计 |
| 阶段 3 回填与旧接口并发 | 先双写（3a）再回填（3b），回填幂等 |
| `layout_structural_hides_value` 依赖分词器，跨 locale 与关键词别名可能误判 | 使用与编译相同的 lexicon 与冻结 aliasSet；en / de / zh 与带关键词别名的夹具；误判只会拒绝保存，不会放过 |
| 编辑器在 Workers 运行时做分词的开销 | 上限 12 KiB，分词是线性的 |
| 词汇规范预览与真实编译不一致 | §8.2 的 Core IR 等价验收 |

---

## 12. 测试与验收

- **解析函数**（单元 + PG 集成）：开关 OFF 时任何授权都无效；套餐不满足时任何授权都无效；直接、团队、委派三种来源各自生效；viewer 不生效；成员移出、降为 viewer、委派关闭（全局与单团队）、团队删除后下一次请求即失效；关闭再打开委派后恢复；Free 成员在团队策略与个人策略上的不同结果；`ai_workspace` 的管理员隐式（阶段 3）。
- **接口**：非管理员 403、非 owner 403、非法载荷 400、座位并发（两个事务同时委派最后一个座位，只有一个成功）、下调座位 409、`requireLicenseWriteOk` 生效、审计行内容。
- **LayoutMap**：§7.5 每个错误码的正反例；三态规则（沿用、丢弃并返回 `layoutDropped`、显式 null）；无权用户提交 layout 403；读取复验（篡改 `layout` 或 `content` 后不渲染 display）；`sourceEnvelopeSha256` 附与不附 layout 相同；演示编译测试改用包导出后仍通过；位置映射往返不变式。
- **编辑器**：能力为假时快照逐字节不变；各标签页组件测试；插入动作在 en / de / zh 生成可编译源码。
- **端到端**（本地栈）：授予用户 → 用表单建结构体 → 插入词汇术语 → 预览与编译结果一致；附 layout → 并排同步 → 审批者首先看到 canonical；撤销后已有策略仍可执行。
- **本地验证命令**：cloud `pnpm run typecheck`、单元测试、集成测试（PG）、相关 e2e；aster-lang-ts `npm test` 与 golden 测试。

---

## 13. 对子 issue 的影响

| issue | 按本 ADR 调整 |
|---|---|
| cloud#792 | 采用 §4 的两张表与注册表；解析函数名 `resolveEntitlement`（避开 `plans.ts` 的 `hasCapability`）；加团队 owner 委派与座位；保存时只对 layout 设门 |
| aster-lang-ts#333 | 范围不变，补 §7.10 的 `./layout` 子路径与 §8.2 的契约；明确不设门 |
| cloud#793 | 按 §8.3；预览窗格随能力显示，`aster/canonicalPreview` 请求本身不设门（回答该 issue 的开放问题） |
| cloud#794 | 存储改为 `PolicyVersionLayout` 独立表；**不需要** aster-api 跟进；加 `layout_structural_hides_value` 与规模上限 |
| cloud#795 | 管理文档补团队座位与委派；LayoutMap 指南写明 §7.7 |

---

## 14. 待用户确认的问题

1. **团队 `admin` 角色能否委派？** 本 ADR 只允许 `Team.ownerId`（D5 的字面意思）。若要放开给 admin，只需改 §4.6 的身份判定。
2. **阶段 3 后，结构词别名与 AI 工作台是否也开放团队 owner 委派？** 本 ADR 默认关闭（注册项 `teamDelegation: false`）。
3. **能力的产品名称**：键名暂定 `structured_authoring`，界面文案待定。
