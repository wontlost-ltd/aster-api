# #466 阶段 C：部署层隔离证明实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 k3s 上部署「模型命名空间 → aster-guard-mcp（Streamable HTTP）→ 受保护 HTTP 工具」的真实拓扑，让网络策略与工具侧 consume 票据两层隔离同时生效，并用自动运行的探针与两条非生产反向记录证明每一层都独立挡住「模型直连原始工具」，从而关闭 wontlost-ltd/aster-api#466 第 1 条（部署层）。

**Architecture:** aster-guard 加 HTTP 上游与无状态 Streamable HTTP 服务端（ADR 0054 §8 ⑤），并在同仓提供演示 HTTP 工具与两个签名镜像；k3s 新增 `apps/aster-lang/{agents,guard,tools}` 三个应用（三个命名空间），工具命名空间启用第一条 namespace-only 入站策略；探针分两处跑：self-hosted runner 以受限 ServiceAccount 在 `aster-agents` 起一次性 Pod 测网络层，`aster-guard` 里的 CronJob 测票据层。反向记录由用户在临时副本命名空间中运行，生产两层永不关闭。

**Tech Stack:** aster-guard：TypeScript 5 / Node 24 ESM / `@modelcontextprotocol/sdk` 1.32.1 / zod 4 / vitest 5 / pnpm 10.31.0；镜像：`node:24-alpine`（digest pin）、docker buildx（linux/arm64）、cosign v2.5.2 keyless；k3s：v1.35.0+k3s1（kube-router 网络策略）、Argo CD ApplicationSet、ExternalSecrets（`ClusterSecretStore vault-backend`）、sigstore policy-controller、kustomize 5、bash + kubectl + jq + yq；探针镜像 `docker.io/curlimages/curl`（digest pin）；aster-api：JUnit 5（仅改 `@Disabled` 原因与文档）。

**Spec（按此顺序读）：**
- `docs/adr/0054-guard-execution-point.md` §3.1、§3.3、§7、§8 ⑤、§11 D2 / D3 / D11 / D12，以及本 PR 新增的 **§12（R67，D1–D9）**——本计划的设计决定全部在那里，计划只写怎么做
- `docs/superpowers/plans/2026-11-14-guard-bypass-suite.md` 阶段 C（Task C1 / C2，本计划取代之）、§5 U3
- aster-guard v0.2.0（`origin/main` `0ad812c`）：`README.md`、`src/tool/consume-ticket.ts`、`src/mcp/{config,cli,proxy}.ts`
- k3s：`apps/aster-lang/ai/{kustomization,network-policy,namespace}.yaml`（namespace-only 约定与 kube-router 缺陷注释）、`argocd/applicationsets/aster-lang.yaml`、`argocd/projects/aster-lang.yaml`、`.github/image-pin/{allowed-images,apps}.yaml`、`apps/infrastructure/policy-controller/`、`scripts/image-pin/verify-{cip-sync,no-floating-tags,registry-sync}.sh`

---

## 0. 现状核对（2026-11-15，各仓 `origin/main` 与集群只读查询）

| 事实 | 证据 |
|---|---|
| aster-guard 0.2.0 已发布 npm（`npm view` → `0.0.0-stage, 0.2.0`）；代理只有 stdio 上游与 stdio 服务端；`createGuardProxyServer` 与传输无关，接受任意已连接的 `UpstreamClient`；`auth` 模式在 `params._meta["io.aster/guard-consume"]` 附票据 | `src/mcp/proxy.ts:84-117,159-175`、`src/mcp/cli.ts:53-62` |
| `verifyConsumeTicket` 需要工具**自己的** `GuardClient`（`get`），回读 `GET /api/v1/guard/decisions/{id}`；该端点类级 `@RequireRole(Role.MEMBER)`，所以工具 key 必须是同租户 MEMBER | `src/tool/consume-ticket.ts:85-114`；aster-api `GuardDecisionResource.java:49` |
| MCP SDK 1.32.1 的 Streamable HTTP 无状态模式（`sessionIdGenerator: undefined`）不校验会话、可单 POST 调 `tools/call`；每个请求须新建 transport | `node_modules/@modelcontextprotocol/sdk/dist/esm/server/webStandardStreamableHttp.js:174-176,738-746` |
| 集群 4 节点（3 control-plane + `worker-1`），均无 taint；全集群只有 1 条 NetworkPolicy（`cloudflare/cloudflare-tunnel-deny-ingress`）；没有命名空间带 `pod-security.kubernetes.io/enforce`（`system-upgrade` 为 privileged 例外） | `kubectl get nodes/ns/networkpolicies -A`（只读） |
| kube-router 在本集群**会执行**网络策略（R31 事故：`ipBlock.except` 规则阻断 traefik 回流）；组合 peer 会退化为 deny-all-including-traefik，所有 aster-lang 策略因此注释停用，且已改写为 namespace-only | k3s `apps/infrastructure/cloudflare-tunnel/network-policy.yaml:1-12`、`apps/aster-lang/{ai,cloud,lsp}/kustomization.yaml` |
| ApplicationSet `aster-lang-apps` 按 `apps/aster-lang/*/kustomization.yaml` 派生应用 `aster-<目录>`、目标命名空间 `aster-<目录>`，`automated.prune/selfHeal: true`；AppProject `aster-lang` 显式列 destinations 与 namespaced 资源白名单（**无** ResourceQuota / LimitRange） | `argocd/applicationsets/aster-lang.yaml`、`argocd/projects/aster-lang.yaml` |
| sigstore policy-controller：带 `policy.sigstore.dev/include: "true"` 的命名空间按 CIP 验签，`no-match-policy: allow`；每个受控镜像须「allowed-images 条目 + digest-verify CIP + reject-tag CIP」原子同落（`verify-cip-sync.sh` 的 N 仓 → 2N CIP）；`allowed-images.yaml` 受 push ruleset 保护，只走人工 PR | `apps/infrastructure/policy-controller/application.yaml:50`、`scripts/image-pin/verify-cip-sync.sh` |
| image-pin：应用以 `.github/image-pin/apps.yaml` 显式登记（`name / lock / kust`）；源仓用自带的 `scripts/ci/open-image-pin-pr.sh` 经 image-pin App（id 4253193）开 PR，脚本等 `verify-image-pin` 通过后自行合并；每镜像在 kustomization `images` 中须恰 1 条 | aster-api `scripts/ci/open-image-pin-pr.sh:230-236`；aster-ai-workspace `deploy.yml:255-345` |
| **self-hosted runner 现状**：唯一的 Linux runner 是用户 Mac 上 podman 里的 ephemeral 容器（以 root 运行、挂宿主 podman socket、`--network host`），注册在 org 组 `local-mac`（`allows_public_repositories: false`）；当前在线的只有 macOS runner `Pang-wontlost-org`。**k3s 与 aster-api 都是 public 仓**，所以现有 runner 组不能为它们跑任何作业（aster-api 的 `pick-runner` 实际一直回落托管）。集群内**没有** ARC，`aster-runner` 命名空间是 Aster 自己的 replay runner-launcher，与 GitHub runner 无关 | aster-api `.github/self-hosted-runner/README.md`、`run-runner.sh:60-75`；`gh api orgs/wontlost-ltd/actions/runner-groups` / `runners`；`gh repo view … visibility` |
| `RUNNER_PROBE_TOKEN`（org secret，self-hosted runners: read）只被 `pick-runner` 用来查 runner 是否在线；部署后 smoke 目前只有 k3s `scripts/post-deploy-smoke.sh`（公网 HTTPS 探测），没有工作流接线 | aster-api `.github/workflows/ci.yml:53-67`；k3s `scripts/post-deploy-smoke.sh:19-22` |
| aster-api 集群内入口 `Service aster-cloud/aster-api`（ClusterIP，80/TCP）；`aster-cloud` 无网络策略 | `kubectl get svc -n aster-cloud`（只读） |
| aster-api `B1DirectConnectToolBypassTest` 为 `@Disabled`（原因指向 k3s 探针），k3s 尚无 `scripts/guard-bypass/`（旧计划 Task C1 未做） | `src/test/java/io/aster/guard/bypass/B1DirectConnectToolBypassTest.java:13`；k3s `origin/main` |

## 1. 拓扑与信任边界

```
aster-agents  ──(Bearer agent token)──►  aster-guard: aster-guard-mcp :8080 /mcp ──► aster-cloud: aster-api（决策 / consume / result）
  │  一次性探针 Pod（runner SA 创建）                    │ --upstream-url，_meta 附票据
  │                                                       ▼
  └──✗ 直连（NetworkPolicy 拒绝）──────────────►  aster-tools: demo-tool :8080 /mcp ──► aster-api（工具自己的 key 回读决策）
                                                          ▲
                        aster-guard: CronJob guard-auth-probe ── 直连、无 / 伪造票据 → 401
```

| 命名空间 | 放什么 | 不得放什么 |
|---|---|---|
| `aster-agents` | 模型 / Agent 工作负载（今天只有探针 Pod）、演示 Agent token | 任何带 API 权限的 ServiceAccount 绑定（`pods/create` 可借用同命名空间的任何 SA） |
| `aster-guard` | 代理、代理的 Guard key 与 agents 登记、`guard-auth-probe`、探针 SA `guard-bypass-probe` | 模型 / Agent 工作负载（它被工具网络策略放行） |
| `aster-tools` | 受保护工具及其 Guard key；每个工具一条入站策略，只放行 `aster-guard` | 其它工作负载 |

## Global Constraints

- **ADR 硬门**：ADR 0054 §12（R67）与本计划同一个 docs PR；用户合并前，下列任何实现 PR 不得合并（可并行开发与评审）。
- **仓与分支**：aster-guard = `aster-cloud/aster-guard`（public）；k3s = `wontlost-ltd/k3s`（public）；aster-api = `wontlost-ltd/aster-api`。每个 PR 从目标仓 `origin/main` 新开分支，不堆叠。
- **命名（全文一致）**：命名空间 `aster-agents / aster-guard / aster-tools`；k3s 目录 `apps/aster-lang/{agents,guard,tools}`；Deployment / Service `aster-guard-mcp`、`demo-tool`；镜像 `docker.io/wontlost/aster-guard-mcp`、`docker.io/wontlost/aster-guard-demo-tool`；ServiceAccount `aster-guard/guard-bypass-probe`；CronJob `aster-guard/guard-auth-probe`；探针 Pod 标签 `app.kubernetes.io/name=guard-bypass-probe` + `guard-bypass/run-id=<id>`；工作流 `.github/workflows/guard-bypass-probe.yml`；Environment `guard-probe`；runner 组 / 标签 `guard-probe`；策略地址 `guard.demo/decide`；工具名 `demo_echo`（ALLOW）、`demo_delete`（DENY）。
- **探针退出码**（ADR 0054 §12 D7）：`0` 通过、`1` 绕过成功、`2` 参数错误、`3` 无法判定；`curl` 退出码 7（拒绝）/ 28（超时）= 被挡，`0`（任何 HTTP 状态）= 可达 = 1，`6`（DNS）及其它 = 3。工作流对 1 与 3 都判红。
- **网络策略形状**（R67 D2）：`policyTypes: [Ingress]`；每条 `from` peer 恰为 `namespaceSelector.matchLabels: {kubernetes.io/metadata.name: aster-guard}`；无 `podSelector` / `ipBlock` peer、无空 `from`、无 egress 规则；端口仅 `8080/TCP`。探针按**所有**选中工具 Pod 的策略的并集判定（策略是叠加的）。
- **Pod 安全**：三个命名空间 `pod-security.kubernetes.io/enforce: restricted`（`enforce-version: latest`）与 `policy.sigstore.dev/include: "true"`；所有 Pod `runAsNonRoot`、`seccompProfile: RuntimeDefault`、`allowPrivilegeEscalation: false`、`capabilities.drop: [ALL]`、`readOnlyRootFilesystem: true`、`automountServiceAccountToken: false`（探针 SA 本身只经 kubeconfig 使用，不挂进任何 Pod）。
- **镜像**：全部按 `@sha256` 引用（`verify-no-floating-tags.sh`）；两个自有镜像 linux/arm64、cosign keyless 身份 `https://github.com/aster-cloud/aster-guard/.github/workflows/image.yml@refs/heads/main`；探针镜像 `docker.io/curlimages/curl:<8.x 版本>@sha256:<多架构索引 digest>` 只写在 `apps/aster-lang/guard/cronjob-auth-probe.yaml` 一处，runner 侧从渲染结果读取。
- **生产两层永不关闭**：演示工具的 `TICKET_CHECK=off` 只允许出现在 `redteam-window.sh` 生成的临时副本里；k3s 测试断言渲染结果中不存在该值，探针断言线上 Deployment 与 `/healthz` 均不报告 insecure。
- **不碰集群 / 不读 Secret**：实施者只可 `KUBECONFIG=~/.kube/k3s-config kubectl get|describe`（非 Secret）；所有写集群、建 Vault 路径、GitHub 设置、发版、runner 注册由用户执行，步骤写在对应 PR 描述里。
- 注释、文档、提交信息用简体中文；提交末尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 描述末尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，正文 `Refs wontlost-ltd/aster-api#466`。
- 每个 aster-guard PR 前跑 `pnpm install --frozen-lockfile && pnpm run typecheck && pnpm run lint && pnpm run test && pnpm run build`；每个 k3s PR 前跑本计划新增的 `.test.sh` 与 `scripts/image-pin/verify-{no-floating-tags,cip-sync,registry-sync}.sh`、`actionlint`、`shellcheck`。

## Review Focus

1. **kube-router 只挡了一半**：同节点与跨节点的转发路径不同（cloud 注释提到跨节点缺陷），经 Service ClusterIP 与直接打 Pod IP 也不同。期望：四个组合都被挡；任一可达即 exit 1。测试落在 Task 6 的 `probe_runs_same_and_cross_node_against_svc_and_pod_ip`（假 kubectl 断言生成了两个 Pod、每个四个目标）与 Task 10 的基线记录。
2. **「挡住了」其实是坏了**：DNS 解析失败、探针 Pod 调度不上、PSA 拒绝 Pod、代理挂了、凭证过期，都会让直连失败而被误判为通过。期望：正向对照（同一 Pod 访问代理 `/healthz` 可达且金丝雀 `demo_echo` 经代理执行成功、`demo_delete` 被拒）不过即 exit 3；`curl` 6 即 3；Pod 未在 90 s 内结束即 3。测试落在 Task 6 的 `dns_failure_is_inconclusive`、`positive_control_failure_is_inconclusive`、`pod_not_scheduled_is_inconclusive`。
3. **票据层被 JSON-RPC 形态绕开**：批量请求把 `tools/call` 夹在数组里、`params._meta` 被二次解析得到不同对象、`method` 大小写变体。期望：数组 → 400 不执行；核对与执行用同一个已解析对象；只有精确 `tools/call` 进入执行且必先核对。测试落在 Task 3 的 `batchRequestIsRejected` 与 `ticketIsReadFromTheSameParsedBody`。
4. **探针留下 Pod / 占满配额**：runner 被取消、kubectl 中断、`--rm` 不可用（无 `pods/attach`）。期望：trap 按 `run-id` 删除；每次运行先清扫标签匹配且超过 10 分钟的残留；ResourceQuota `pods: "4"` 让残留不至于无限增长；CronJob `successfulJobsHistoryLimit: 1`、`failedJobsHistoryLimit: 3`、`activeDeadlineSeconds: 120`。测试落在 Task 6 的 `cleanup_deletes_by_run_id_on_failure` 与 `stale_probe_pods_are_swept`。
5. **runner SA 的 `pods/create` 被当作跳板**：在 `aster-agents` 起任意 Pod 可挂载该命名空间的 Secret、借用该命名空间的 SA。期望：`aster-agents` 只有演示 Agent token 一个 Secret；探针静态检查「`aster-agents` 内没有任何 RoleBinding 指向 `aster-agents` 的 SA」，违反即 exit 1；kubeconfig token 过期前 14 天工作流告警。测试落在 Task 6 的 `agents_namespace_with_sa_binding_fails` 与 Task 8 的 `token_expiry_warning`。

---

## 2. 文件结构

| 仓 / 文件 | 职责 |
|---|---|
| aster-guard `src/mcp/config.ts` | 新参数 `--upstream-url / --upstream-auth-env / --listen / --agents-file`，`--isolation` 列表 |
| aster-guard `src/mcp/upstream.ts`（新） | 按配置连接 stdio 或 HTTP 上游，返回已连接的 `Client` |
| aster-guard `src/mcp/agents.ts`（新） | agents 登记文件解析与 Bearer 鉴权 |
| aster-guard `src/mcp/http-server.ts`（新） | 无状态 Streamable HTTP 服务端、`/healthz`、`/readyz` |
| aster-guard `src/mcp/proxy.ts`、`cli.ts` | 接受 `isolation` 列表与共享工具名缓存；CLI 分派 stdio / 监听两种服务方式 |
| aster-guard `examples/http-demo-tool/` | 演示 HTTP 工具（票据核对、`/effects`、`/healthz`） |
| aster-guard `docker/{mcp,demo-tool}.Dockerfile`、`.github/workflows/image.yml`、`scripts/ci/{open-image-pin-pr,pin-closure-guard}.sh` | 镜像构建、签名与 k3s pin |
| k3s `apps/aster-lang/{agents,guard,tools}/` | 三个应用的清单 |
| k3s `argocd/projects/aster-lang.yaml`、`.github/image-pin/{apps,allowed-images}.yaml`、`apps/infrastructure/policy-controller/policies/*` | 目标命名空间与资源白名单、pin 登记、信任根与 CIP |
| k3s `scripts/guard-bypass/` | `lib.sh`（策略形状判定）、`direct-connect-probe.sh`、`redteam-window.sh` 及各自 `.test.sh`、`manifests.test.sh` |
| k3s `apps/aster-lang/guard/probe/auth-probe.sh` | 票据层探针（在 CronJob Pod 内运行，busybox `sh`） |
| k3s `.github/workflows/guard-bypass-probe.yml`、`verify-gate-selftests.yml` | 部署后 / 定时探针；新测试接入自测工作流 |
| aster-api `.github/self-hosted-runner/run-guard-probe-runner.sh`（+ plist） | 专用 `guard-probe` runner（无 podman socket） |
| aster-api `src/test/java/io/aster/guard/bypass/{B1DirectConnectToolBypassTest.java,README.md}`、`docs/adr/0054-guard-execution-point.md` §7、本计划与旧计划阶段 C 指针 | 验收后的登记 |

---

## 阶段 G：aster-guard（HTTP 模式、演示工具、镜像）

### Task 1：HTTP 上游与 `--isolation` 列表

**Files:**
- Create: `src/mcp/upstream.ts`、`test/mcp-upstream-http.test.ts`、`test/fixtures/http-upstream.ts`
- Modify: `src/mcp/config.ts`、`src/mcp/cli.ts:44-63`、`src/mcp/proxy.ts`（`isolation` 类型与判定）、`test/mcp-config.test.ts`、`README.md`（代理参数一节）

**Interfaces:**
- Produces:
  - `type UpstreamSpec = { kind: 'stdio'; command: string; args: string[] } | { kind: 'http'; url: URL; authEnv?: string }`；`ProxyConfig.upstream?: UpstreamSpec`（stdio 分支保持原字段名，CLI 内部改用 `kind` 判别）。
  - `type Isolation = 'network' | 'auth'`；`ProxyConfig.isolation?: readonly Isolation[]`（去重、按字母序）；`GuardProxyOptions.isolation?: Isolation | readonly Isolation[]`（仍接受单值，导出类型向后兼容）。
  - `connectUpstream(spec: UpstreamSpec, env: NodeJS.ProcessEnv, apiKeyEnv: string): Promise<Client>`（`src/mcp/upstream.ts`）：stdio 照旧（`upstreamEnv` 剔除 API key）；http 用 `StreamableHTTPClientTransport(url, { requestInit: { headers } })`，`authEnv` 存在时 `headers.Authorization = "Bearer " + env[authEnv]`。
  - `test/fixtures/http-upstream.ts`：`startHttpUpstream(): Promise<{ url: URL; calls: Array<{ name: string; arguments: unknown; meta: unknown; authorization: string | null }>; close(): Promise<void> }>`（无状态、`enableJsonResponse`，工具 `echo`）。Task 2、3 复用。
- 配置错误（`ConfigError`，退出码 2）：`--upstream` 与 `--upstream-url` 同时出现；`--upstream-auth-env` 无 `--upstream-url`；`--upstream-url` 非 http(s)；`--upstream-auth-env` 等于 API key 变量名；该变量未设置或为空；`--isolation` 含未知值或为空。日志与错误信息不得包含 auth 变量的值。

- [ ] **Step 1：写失败测试**
  - `mcp-config.test.ts`：`isolationListIsParsedSortedAndDeduped`（`network,auth,auth` → `['auth','network']`）；`singleIsolationStillAccepted`；`upstreamAndUpstreamUrlAreExclusive`；`upstreamAuthEnvRequiresUrl`；`upstreamAuthEnvMustDifferFromApiKeyEnv`；`missingUpstreamAuthValueIsConfigError`（错误文本不含变量值）。
  - `mcp-upstream-http.test.ts`（fake-api + `startHttpUpstream`）：`authIsolationForwardsTicketInMetaOverHttp`（`isolation: ['auth','network']` → `calls[0].meta["io.aster/guard-consume"]` 的 `decisionId / attemptId` 等于 `_meta.asterGuard`）；`networkOnlyForwardsNoTicket`；`upstreamAuthEnvSendsBearer`（`authorization === "Bearer s3cret"`）；`denyIsNotForwardedOverHttp`（`calls` 为空）。
- [ ] **Step 2：** `pnpm vitest run test/mcp-config.test.ts test/mcp-upstream-http.test.ts` → FAIL（未知参数 / 模块不存在）。
- [ ] **Step 3：实现** `config.ts` 新选项与 `executionConfig` 校验；`upstream.ts`；`proxy.ts` 中 `options.isolation === 'auth'` 改为 `isolations(options).includes('auth')`；`cli.ts` 用 `connectUpstream`，启动日志写上游 `stdio:<command>` 或 `http:<origin><path>`（不打印查询串）；`USAGE` 增加三项说明（逐字写明 `network,auth` 的含义，见 ADR 0054 §12 D1 / D4）。
- [ ] **Step 4：** 全量 `pnpm run typecheck && pnpm run lint && pnpm run test` → PASS（既有 `test/mcp-cli*.test.ts` 不改断言）。
- [ ] **Step 5：提交** `feat(mcp): HTTP 上游与 --isolation 列表（ADR 0054 §12 D1/D4，aster-api#466）`。

### Task 2：无状态 Streamable HTTP 服务端与 Agent 登记

**Files:**
- Create: `src/mcp/agents.ts`、`src/mcp/http-server.ts`、`test/mcp-agents.test.ts`、`test/mcp-http-server.test.ts`
- Modify: `src/mcp/config.ts`、`src/mcp/cli.ts`、`src/mcp/proxy.ts`（共享工具名缓存）、`src/mcp/index.ts`（导出 `startHttpProxy`）、`README.md`（「Streamable HTTP 模式」一节）

**Interfaces:**
- Consumes: Task 1 的 `connectUpstream`、`startHttpUpstream`。
- Produces:
  - `interface AgentEntry { id: string; tokenSha256: string; principalId?: string; principalRoles?: string[] }`；`loadAgents(path: string): AgentEntry[]`（严格 zod：`agents` 非空、`id` 唯一、`tokenSha256` 为 64 位小写十六进制且唯一、文本字段同 `actionText` 限制）；`authenticate(header: string | undefined, agents: readonly AgentEntry[]): AgentEntry | undefined`（取 `Bearer ` 之后的值做 sha256，与每项 `timingSafeEqual` 比较，遍历全部项不提前返回）。
  - `ProxyConfig` 增 `listen?: { host: string; port: number }`、`agentsFile?: string`；监听模式要求有上游与 `--agents-file`，禁止 `--principal-id / --principal-type / --principal-roles`（ConfigError「监听模式的 principal 取自 --agents-file」）；stdio 模式 `--principal-id` 仍必填。
  - `createToolNameCache(upstream: UpstreamClient, timeoutMs: number): ToolNameCache`（`proxy.ts` 导出，即现 `UpstreamToolNames`）；`GuardProxyOptions.toolNames?: ToolNameCache`（缺省每个代理实例自建，行为不变）。
  - `startHttpProxy(opts: { listen: { host: string; port: number }; agents: readonly AgentEntry[]; upstream: Client; makeProxyOptions: (agent: AgentEntry) => Omit<GuardProxyOptions, 'upstream'> }): Promise<{ url: URL; close(): Promise<void> }>`：
    - `POST /mcp`：鉴权失败 → 401 `{"error":"unauthorized"}` + `WWW-Authenticate: Bearer`，不建服务器、不访问 Guard；请求体 > 1 MiB → 413；通过后为该请求新建 `createGuardProxyServer({ ...makeProxyOptions(agent), upstream: 共享上游的不关闭包装, toolNames: 共享缓存 })` 与 `StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true })`，响应结束后关闭二者。
    - `GET|DELETE /mcp` → 405；`GET /healthz` → 200 `ok`；`GET /readyz` → 上游 `listTools`（3 s 超时）在最近 30 s 内成功过则 200，否则 503（结果缓存 10 s，并发共享一次探测）；其余路径 404。
    - 每个 agent 的 `GuardClient` 在启动时构造一次，`principal = { id: principalId ?? id, type: 'agent', roles? }`。
  - CLI：`--listen` 存在时走 `startHttpProxy`，收到 SIGTERM 先停止接受新连接、等待在途请求至 `SHUTDOWN_GRACE_MS` 再关闭上游。

- [ ] **Step 1：写失败测试**
  - `mcp-agents.test.ts`：`duplicateTokenHashIsRejected`、`uppercaseHashIsRejected`、`authenticateMatchesOnlyRegisteredToken`、`missingOrNonBearerHeaderIsUndefined`。
  - `mcp-http-server.test.ts`（fake-api + Task 1 HTTP 上游夹具 + 真实 HTTP 客户端 `StreamableHTTPClientTransport`）：`unregisteredTokenIs401AndGuardIsNeverCalled`（fake-api 请求计数 0）；`eachAgentDecidesWithItsOwnPrincipal`（两个 token → 决策请求体 `principal.id` 分别为登记值）；`allowedCallExecutesOnceThroughHttp`（上游 `calls` 恰 1、带票据）；`deniedCallIsNotForwarded`；`toolsListIsFetchedOncePerCacheNotPerRequest`（连续 3 次 `tools/call`，上游 `tools/list` 计数 1）；`oversizedBodyIs413`；`getMcpIs405`；`readyzIs503WhenUpstreamDown`（关闭上游夹具后 30 s 窗口用假时钟推进）；`listenModeRejectsPrincipalId`（config）。
- [ ] **Step 2：** 运行上述文件 → FAIL。
- [ ] **Step 3：实现**（`node:http`，不新增运行期依赖）。
- [ ] **Step 4：** 全量四件套 → PASS；本地手动：`ASTER_GUARD_API_KEY=x node dist/mcp/cli.js --listen 127.0.0.1:18080 --upstream-url http://127.0.0.1:<夹具端口>/mcp …` 后 `curl -s localhost:18080/healthz` 得 `ok`，输出贴 PR。
- [ ] **Step 5：提交** `feat(mcp): 无状态 Streamable HTTP 服务端与 Agent 登记（ADR 0054 §3.1、§12 D4）`。

### Task 3：演示 HTTP 工具

**Files:**
- Create: `pnpm-workspace.yaml`（`packages: ['examples/*']`）、`examples/http-demo-tool/{package.json,tsconfig.json,src/server.ts,src/effects.ts,test/server.test.ts,README.md}`
- Modify: `vitest.config.ts`（`include` 加 `examples/*/test/**/*.test.ts`）、`eslint.config.js`（覆盖 `examples`）、`pnpm-lock.yaml`、根 `package.json` 的 `lint` 脚本加 `examples`、`test/workflow-parity.test.ts`（若其对 `include` 有断言则同步）

**Interfaces:**
- Consumes: `@aster-cloud/aster-guard/tool`（`workspace:*`）的 `verifyConsumeTicket / MemoryAttemptLedger / TicketRejection`；`@aster-cloud/aster-guard` 的 `GuardClient`；Task 1 的 HTTP 夹具风格。
- Produces:
  - 环境变量：`PORT`（缺省 8080）、`ASTER_API_BASE_URL`、`ASTER_GUARD_TOOL_API_KEY`（工具**自己的** MEMBER key）、`ASTER_GUARD_TENANT_ID`、`ASTER_GUARD_POLICY`（`<module>/<function>`，D12 期望策略）、`TICKET_CHECK`（`on` 缺省 | `off`）；`on` 时三个 Guard 变量缺一即启动失败（退出 2）。
  - `startDemoTool(env): Promise<{ url: URL; close(): Promise<void> }>`。工具：`demo_echo { message: string }` 返回 `{ echoed, decisionId, attemptId }`（text + `structuredContent`）；`demo_delete { id: string }` 返回 `{ deleted: id, … }`。两者都向 `EffectLog` 追加一条（这就是「副作用」）。
  - `EffectLog`：`record(tool: string, ticket: { decisionId: string; attemptId: string } | null)`；`snapshot(): { count: number; unticketed: number; last: Array<{ tool, decisionId, attemptId, at }> }`（保留最近 20 条）。
  - HTTP：`POST /mcp` 先 `JSON.parse` 一次——数组 → 400 `{"error":"batch_not_supported"}`；对象且 `method === "tools/call"` 且 `TICKET_CHECK=on` → 以**同一对象**的 `params._meta / params.arguments / params.name` 调 `verifyConsumeTicket`（`policy` 取 `ASTER_GUARD_POLICY`，`ledger` 为进程内单例），拒绝 → 401 `{"error":"ticket_rejected","reason":<TicketRejection>}` 且不交给 MCP；通过后把核对结果放进该请求的上下文供工具处理函数写入 `EffectLog`，再 `transport.handleRequest(req, res, parsedBody)`。其它方法直接交给 MCP（无状态，`enableJsonResponse`）。`GET /healthz` → 200 `{"status":"ok","ticketCheck":"on"|"off"}`；`GET /effects` → `EffectLog.snapshot()`。`TICKET_CHECK=off` 时每 60 s 在 stderr 打 `INSECURE: 票据核对已关闭`。

- [ ] **Step 1：写失败测试** `examples/http-demo-tool/test/server.test.ts`（fake-api 支持 `GET /decisions/{id}` 回读）：
  - `missingTicketIs401AndNothingExecutes`（`reason: "missing_ticket"`，`/effects` `count 0`）
  - `forgedTicketIs401`（随机 UUID、合法形状 envelope → `reason ∈ {"guard_unavailable","not_allowed"}`）
  - `malformedTicketIs401`
  - `validTicketViaProxyExecutesOnce`（Task 2 的 `startHttpProxy` → 工具，`count 1`、`unticketed 0`、`last[0].attemptId` 等于代理 `_meta.asterGuard.attemptId`）
  - `replayedTicketIs401`（同一请求体再 POST 一次 → `ticket_replayed`，`count` 仍 1）
  - `batchRequestIsRejected`（`[{tools/call…}]` → 400，`count 0`）
  - `ticketIsReadFromTheSameParsedBody`（用含重复键 `"params"` 的原文：核对与执行看到的对象一致——断言要么 401 要么执行时记录的 `decisionId` 等于核对通过的 id，不出现「核对 A、执行 B」）
  - `toolsListNeedsNoTicket`
  - `ticketCheckOffExecutesUnticketed`（`TICKET_CHECK=off`：无票据 200，`unticketed 1`，`/healthz.ticketCheck === "off"`）
  - `startupFailsWithoutGuardEnvWhenCheckOn`
- [ ] **Step 2：** `pnpm vitest run examples/http-demo-tool` → FAIL。
- [ ] **Step 3：实现**；`README.md` 写明：单副本 + `Recreate` 的原因（ADR 0054 §11 D11，重启后 ttl 内可重放一次）、工具 key 必须同租户 MEMBER、`TICKET_CHECK=off` 只用于红队副本。
- [ ] **Step 4：** 全量四件套 → PASS；`npm pack --dry-run` 输出不含 `examples/`。
- [ ] **Step 5：提交** `feat(examples): 演示 HTTP 工具——consume 票据核对与副作用日志（ADR 0054 §12 D6）`。

### Task 4：镜像、签名与 k3s pin 工作流

**Files:**
- Create: `docker/mcp.Dockerfile`、`docker/demo-tool.Dockerfile`、`.dockerignore`、`.github/workflows/image.yml`、`scripts/ci/open-image-pin-pr.sh`（自 aster-ai-workspace `origin/main` 逐字复制，头部注明来源 commit）、`scripts/ci/pin-closure-guard.sh`、`scripts/ci/pin-closure-guard.test.sh`（同样复制）、`test/workflow-image.test.ts`
- Modify: `.github/workflows/ci.yml`（跑 `bash scripts/ci/pin-closure-guard.test.sh`）、`release.yml`（同步同一步骤以满足 parity 测试）

**Interfaces:**
- Produces: 镜像 `docker.io/wontlost/aster-guard-mcp`（`ENTRYPOINT ["node","/app/dist/mcp/cli.js"]`）与 `docker.io/wontlost/aster-guard-demo-tool`（`ENTRYPOINT ["node","/app/examples/http-demo-tool/dist/server.js"]`）；两者 `FROM node:24-alpine@sha256:<digest>`、多阶段（构建阶段 `pnpm install --frozen-lockfile && pnpm -r build`，运行阶段 `pnpm deploy --prod`）、`USER 10001:10001`、无 shell 依赖的入口、`EXPOSE 8080`、可在只读根文件系统下运行。
- `image.yml`：触发 `push: branches [main]` 与 `workflow_dispatch`（**无** `pull_request`）；`build` job `permissions: { contents: read, id-token: write }`，对每个镜像：buildx `platforms: linux/arm64`、`provenance: false`、tag `<sha>`、按输出 digest 拉 arm64 变体跑 `uname -m` 必须 `aarch64`（同 aster-api `deploy.yml:386-389` 的教训）、`cosign sign` digest（cosign-installer `@6f9f1778… # v4.1.2`、`cosign-release: v2.5.2`）、`cosign verify` 身份等于 Global Constraints 中的值；`image-pin-pr` job（`environment: image-pin`，`needs: build`，仅 main 且 sha 仍为 main tip）分别以 `LOCK_PATH=apps/aster-lang/guard/image-lock.yaml KUSTOMIZATION_PATH=apps/aster-lang/guard/kustomization.yaml` 与 `…/tools/…` 调 `open-image-pin-pr.sh`，分支 `image-pin/aster-guard-mcp`、`image-pin/aster-guard-demo-tool`；`pin-closure-guard` 同 aster-ai-workspace。
- Secrets / vars（用户配置，写进 PR 描述）：仓库 secrets `DOCKER_USERNAME / DOCKER_PASSWORD`；Environment `image-pin`（部署分支仅 `main`）内 `vars.IMAGE_PIN_APP_CLIENT_ID`、`secrets.IMAGE_PIN_APP_PRIVATE_KEY`。

- [ ] **Step 1：写失败测试** `test/workflow-image.test.ts`（解析 YAML）：`imageWorkflowHasNoPullRequestTrigger`；`idTokenOnlyOnBuildJob`；`cosignVersionPinned`；`imagePinJobUsesEnvironment`；`bothImagesAreArchVerifiedBeforeSigning`（步骤顺序：push → uname 校验 → sign）。
- [ ] **Step 2：** 运行 → FAIL。
- [ ] **Step 3：实现** Dockerfile 与工作流。
- [ ] **Step 4：** `pnpm run test` PASS；`actionlint` 无输出；本地 `docker buildx build --platform linux/arm64 -f docker/mcp.Dockerfile --load -t t/mcp .` 与 demo-tool 同法成功，`docker run --rm --read-only t/mcp --help` 退出 0、`docker run --rm --read-only -e TICKET_CHECK=off -p 18081:8080 t/tool` 后 `curl -s localhost:18081/healthz` 含 `"ticketCheck":"off"`，输出贴 PR。
- [ ] **Step 5：提交** `ci(image): aster-guard-mcp 与演示工具镜像构建、cosign 签名与 k3s pin（ADR 0054 §12 D5）`，开 PR。**合并顺序**：本 PR 可先合并（pin PR 会因 k3s 尚无对应 lock / 信任根而被 `verify-image-pin` 拒绝，`pin-closure-guard` 红是预期，见 Task 7 的顺序说明），或等 Task 7 的信任根 PR 合并后再合并——由用户选，默认后者。

### Task 5：aster-guard 0.3.0 发版（用户批准后执行）

**Files:** Modify `package.json`（`version: 0.3.0`）、`src/index.ts`（`VERSION`）、`CHANGELOG.md`（新增：HTTP 上游、`--upstream-auth-env`、`--listen` / `--agents-file`、`/healthz` `/readyz`、`--isolation` 列表、演示工具与镜像）

- [ ] Step 1：`test/smoke.test.ts` 的版本断言随之更新后 `pnpm run test` → PASS。
- [ ] Step 2：提交 `chore(release): 0.3.0`，开 PR；合并后由**用户**推 tag `v0.3.0`（`release.yml` 以组织 `NPM_TOKEN` 发布，ADR 0054 §11 D6）。
- [ ] Step 3：`npm view @aster-cloud/aster-guard@0.3.0 version` 输出 `0.3.0`，贴 PR。

> 0.3.0 是 minor：新增能力，0.2.0 的 CLI 与导出类型全部保持兼容（`isolation` 仍接受单值）。部署不依赖 npm 发版（镜像从 main 构建），发版服务于阶段 B 与第三方工具作者。

---

## 阶段 K：k3s（清单、探针、工作流）

### Task 6：探针脚本（取代旧计划 Task C1）

**Files:**
- Create: `scripts/guard-bypass/lib.sh`、`scripts/guard-bypass/direct-connect-probe.sh`、`scripts/guard-bypass/direct-connect-probe.test.sh`、`apps/aster-lang/guard/probe/auth-probe.sh`、`scripts/guard-bypass/auth-probe.test.sh`、`scripts/guard-bypass/testdata/`（假 kubectl / curl 的 JSON 夹具）
- Modify: `.github/workflows/verify-gate-selftests.yml`（运行两个 `.test.sh`）

**Interfaces:**
- `lib.sh`：`policy_union_violations <policies.json> <pod-labels.json> <admit-ns>`（stdout 每行一个违规，空即合规；规则见 Global Constraints「网络策略形状」，并要求至少一条策略选中工具 Pod）；`classify_curl <exit> <http_code>` → `blocked | reachable | inconclusive`。
- `direct-connect-probe.sh network --agents-ns <ns> --tool-ns <ns> --tool-selector <k=v> --tool-svc <name:port> --admit-ns <ns> --probe-image <ref@sha256> [--positive-url <url>] [--canary-proxy <url> --canary-secret <name>] [--run-id <id>] [--dry-run]`：
  1. 静态：`kubectl get networkpolicy -n <tool-ns> -o json` + 工具 Pod 标签 → `policy_union_violations`，有输出即 exit 1；`kubectl get rolebindings -n <agents-ns>` 中任一 subject 为 `ServiceAccount` 且 `namespace == <agents-ns>` → exit 1；工具 Deployment 若 `replicas != 1`、`strategy.type != Recreate` 或任一容器 env `TICKET_CHECK=off` → exit 1。
  2. 清扫：删除 `<agents-ns>` 中带 `app.kubernetes.io/name=guard-bypass-probe` 且创建超过 600 s 的 Pod。
  3. 读工具 Pod 的 `podIP` 与 `nodeName`；以 `kubectl create -f -` 建两个 Pod（不用 `kubectl run --rm -i`，SA 没有 `pods/attach`）：`same` 用 `nodeSelector: {kubernetes.io/hostname: <nodeName>}`，`cross` 用对 `<tool-ns>` 中工具 Pod 的 required `podAntiAffinity`（`namespaces: [<tool-ns>]`，`topologyKey: kubernetes.io/hostname`）；Pod 满足 Global Constraints 的 restricted 要求，`activeDeadlineSeconds: 90`，`restartPolicy: Never`；容器以 `sh -c` 依次对 `http://<svc>.<tool-ns>.svc.cluster.local:<port>/mcp` 与 `http://<podIP>:<port>/mcp` 执行 `curl -sS -o /dev/null -m 5 -w '%{http_code}' -X POST`，每个目标打印一行 `RESULT <target> <curl_exit> <http_code>`；`--positive-url` 给出时以 `curl -k -sS -o /dev/null -m 5` 访问并打印 `POSITIVE <curl_exit> <http_code>`（判据：`classify_curl` 为 `reachable` 即通过，任何 HTTP 状态都算——它只证明该 Pod 的 DNS 与出站正常）；`cross` Pod 在 `--canary-proxy` 给出时从 `secretKeyRef <canary-secret>/token` 读 token，以单个 POST（`Accept: application/json, text/event-stream`）调 `tools/call demo_echo {"message":"probe-<run-id>"}` 与 `tools/call demo_delete {"id":"probe-<run-id>"}`，打印 `CANARY echo <ok|fail>`（结果非 `isError` 且含 `_meta.asterGuard.decisionId`）与 `CANARY delete <ok|fail>`（`isError: true` 且 `asterGuard.status == "denied"`）。
  4. `kubectl wait --for=jsonpath='{.status.phase}'=Succeeded`（95 s）→ `kubectl logs` → 解析。判定：任一 `RESULT` 为 `reachable` → 1；任一 `inconclusive`、`POSITIVE` 不是 `reachable`、任一 `CANARY … fail`、Pod 未成功结束 → 3；否则 0。最后打印一行 JSON 摘要 `{"verdict":…, "runId":…, "results":[…]}`。
  5. `trap` 在 EXIT / INT / TERM 时 `kubectl delete pod -n <agents-ns> -l guard-bypass/run-id=<id> --wait=false`。
- `direct-connect-probe.sh auth-status --guard-ns <ns> --cronjob <name> --since <RFC3339> [--wait <seconds>]`：等到 `<since>` 之后启动的、由该 CronJob 创建的 Job 结束（缺省等 1500 s）→ 读其 Pod 日志最后一行 JSON 摘要 → 以摘要的 `verdict` 映射退出码；等不到 → 3。
- `auth-probe.sh`（busybox `sh` + `curl`，环境变量 `TOOL_URL`，无 jq）：① `GET /healthz` 必须 200 且不含 `"ticketCheck":"off"`（否则 1）；② `tools/list` 必须 200（否则 3：放行位置连不上工具，说明网络层把放行方也挡了或工具坏了）；③ 记 `/effects` 的 `unticketed`；④ 无票据 `tools/call demo_echo` 期望 401 且 `"reason":"missing_ticket"`；⑤ 伪造票据期望 401 且 reason ∈ {`guard_unavailable`,`not_allowed`}；⑥ 畸形票据期望 401 `malformed_ticket`；⑦ 批量请求期望 400；⑧ 再读 `unticketed`，增加即 1。④–⑦ 得到 2xx → 1，其它状态 → 3。末行 JSON 摘要同上。

- [ ] **Step 1：写失败测试**（表驱动，`PATH` 注入假 `kubectl` / `curl`，夹具在 `testdata/`）
  - `direct-connect-probe.test.sh`：`missing_policy_fails`(1)；`combined_peer_fails`(1)；`ipblock_peer_fails`(1)；`second_policy_admitting_agents_fails`(1，并集)；`agents_namespace_with_sa_binding_fails`(1)；`ticket_check_off_in_deployment_fails`(1)；`probe_runs_same_and_cross_node_against_svc_and_pod_ip`（假 kubectl 记录的两个 Pod 规格分别含 nodeSelector / podAntiAffinity，日志 4 行 RESULT）；`all_blocked_is_pass`(0)；`any_reachable_is_bypass`(1，curl 0 + 401 也算)；`dns_failure_is_inconclusive`(3)；`positive_control_failure_is_inconclusive`(3)；`canary_failure_is_inconclusive`(3)；`pod_not_scheduled_is_inconclusive`(3)；`cleanup_deletes_by_run_id_on_failure`；`stale_probe_pods_are_swept`；`missing_args_is_usage`(2)；`dry_run_prints_without_creating`；`auth_status_maps_verdict`(0/1/3)；`auth_status_timeout_is_inconclusive`(3)。
  - `auth-probe.test.sh`：`all_rejected_is_pass`(0)；`unticketed_call_executes_is_bypass`(1)；`healthz_reports_off_is_bypass`(1)；`tools_list_unreachable_is_inconclusive`(3)；`unexpected_500_is_inconclusive`(3)；`batch_accepted_is_bypass`(1)。
- [ ] **Step 2：** `bash scripts/guard-bypass/direct-connect-probe.test.sh && bash scripts/guard-bypass/auth-probe.test.sh` → FAIL。
- [ ] **Step 3：实现**；`shellcheck` 三个脚本无告警；`auth-probe.sh` 用 `busybox sh -n` 检查语法。
- [ ] **Step 4：** 两个测试全过；`verify-gate-selftests.yml` 加两步；`actionlint` 无输出。
- [ ] **Step 5：提交** `feat(guard-bypass): 部署层直连探针与票据层探针（aster-api#466，ADR 0054 §12 D7）`。

### Task 7：三个应用的清单、信任根与 pin 登记

拆两个 PR（信任根受 push ruleset 保护，只能人工 PR；且 `verify-cip-sync` 要求条目与 CIP 原子同落）：

**PR 7a（信任根，人工合并）Files:**
- Modify: `.github/image-pin/allowed-images.yaml`（两条：`image: docker.io/wontlost/aster-guard-mcp` / `docker.io/wontlost/aster-guard-demo-tool`，`sourceRepo: aster-cloud/aster-guard`，`workflowFile: image.yml`，`sourceRef: refs/heads/main`，`deployBinding: kustomization`）
- Create: `apps/infrastructure/policy-controller/policies/cluster-image-policy-aster-guard-mcp{,-reject-tag}.yaml`、`…-aster-guard-demo-tool{,-reject-tag}.yaml`（与 `aster-ai-workspace` 两份同形，`metadata.name` 为 `wontlost-aster-guard-mcp` 等）；`policies/kustomization.yaml` 列入
- 验证：`bash scripts/image-pin/verify-cip-sync.sh` → 退出 0（`origin/main` 现为 7 仓 / 14 CIP，本 PR 新增 2 仓 → **9 仓 / 18 CIP**）。

**PR 7b（应用）Files:**
- Create `apps/aster-lang/tools/`：`namespace.yaml`（PSA restricted + sigstore include + `app.kubernetes.io/part-of: aster-lang`）、`deployment.yaml`（`demo-tool`，`replicas: 0` 起步，`strategy: Recreate`，env 见 Task 3，`ASTER_API_BASE_URL=http://aster-api.aster-cloud.svc.cluster.local`、`ASTER_GUARD_POLICY=guard.demo/decide`、`TICKET_CHECK` **不写**（缺省 on），readiness / liveness `GET /healthz`，`/tmp` emptyDir）、`service.yaml`（8080）、`network-policy.yaml`（`demo-tool-ingress`：`podSelector {app: demo-tool}`，`from` 仅 `aster-guard`，端口 8080；**列入 resources**，文件头注释写明这是 aster-lang 下首个启用的策略、为何只用 namespace-only 且不写 egress，引用 ADR 0054 §12 D2）、`external-secrets.yaml`（`demo-tool-guard` ← Vault `secret/apps/aster-guard-demo` 的 `tool-api-key`、`tenant-id`）、`image-lock.yaml`、`kustomization.yaml`（`namespace: aster-tools`，`images` 一条全零占位 digest，同 ai 约定）。
- Create `apps/aster-lang/guard/`：`namespace.yaml`、`deployment.yaml`（`aster-guard-mcp`，`replicas: 0` 起步，args `--listen 0.0.0.0:8080 --upstream-url http://demo-tool.aster-tools.svc.cluster.local:8080/mcp --isolation network,auth --base-url http://aster-api.aster-cloud.svc.cluster.local --policy guard.demo/decide --tenant-id $(ASTER_GUARD_TENANT_ID) --tools demo_echo,demo_delete --agents-file /etc/aster-guard/agents.json --executor-id aster-guard-mcp@$(POD_NAME)`，readiness `/readyz`、liveness `/healthz`）、`service.yaml`、`external-secrets.yaml`（`aster-guard-mcp` ← 同一 Vault 路径的 `proxy-api-key`、`tenant-id`、`agents-json`，后者挂为文件）、`serviceaccount.yaml`（`guard-bypass-probe`，`automountServiceAccountToken: false`）、`role-probe-read.yaml` + `rolebinding-probe-read.yaml`（本命名空间：`pods` / `pods/log` get list；`batch` `jobs` / `cronjobs` get list；`apps` `deployments` get list）、`cronjob-auth-probe.yaml`（`schedule: "*/10 * * * *"`、`concurrencyPolicy: Forbid`、`startingDeadlineSeconds: 300`、历史限额与 `activeDeadlineSeconds` 见 Review Focus 4、`TOOL_URL=http://demo-tool.aster-tools.svc.cluster.local:8080`、镜像为 curl digest、命令 `sh /probe/auth-probe.sh`）、`kustomization.yaml`（`configMapGenerator` 从 `probe/auth-probe.sh` 生成 `guard-auth-probe-script`；`images` 一条 `aster-guard-mcp` 占位）、`image-lock.yaml`。
- Create `apps/aster-lang/agents/`：`namespace.yaml`、`resource-quota.yaml`（`pods: "4"`、`requests.cpu: 500m`、`requests.memory: 512Mi`、`limits.cpu: "1"`、`limits.memory: 1Gi`）、`limit-range.yaml`（容器缺省 `50m / 64Mi`，上限 `200m / 128Mi`）、`role-probe.yaml`（`pods`: create get list watch delete；`pods/log`: get；`rbac.authorization.k8s.io` `rolebindings`: list；`apps` `deployments` 不需要）、`rolebinding-probe.yaml`（subject `ServiceAccount guard-bypass-probe`，`namespace: aster-guard`）、`external-secrets.yaml`（`guard-demo-agent` ← `agent-token`）、`kustomization.yaml`。
- `apps/aster-lang/tools/` 另加 `role-probe-read.yaml` + `rolebinding-probe-read.yaml`（`pods` get list、`networking.k8s.io` `networkpolicies` get list、`apps` `deployments` get list、`services` get）。
- Modify: `argocd/projects/aster-lang.yaml`（destinations 加三命名空间；`namespaceResourceWhitelist` 加 `ResourceQuota`、`LimitRange`，注释说明只为 `aster-agents`）、`.github/image-pin/apps.yaml`（`guard`、`tools` 两条，`lock` / `kust` 指向上面）、`scripts/image-pin/verify-registry-sync.sh` 若需要同步则同步。
- Create: `scripts/guard-bypass/manifests.test.sh`：`kustomize build` 三个目录；断言：三命名空间 PSA restricted 与 sigstore include；`tools` 渲染结果的策略 `policy_union_violations` 为空；渲染结果不含 `TICKET_CHECK` 值 `off`；所有 Pod 模板满足 restricted 字段；`demo-tool` `replicas ≤ 1` 且 `Recreate`；`guard-bypass-probe` 的 Role 不含 `secrets`、`pods/exec`、`pods/attach`、`*` 动词或资源；`aster-agents` 内无指向本命名空间 SA 的 RoleBinding；`apps/aster-lang/agents` 中可产生 Secret 的对象恰为 `ExternalSecret guard-demo-agent`；CronJob 镜像带 `@sha256`。接入 `verify-gate-selftests.yml`。

- [ ] Step 1：写 `manifests.test.sh` → 运行 FAIL（目录不存在）。
- [ ] Step 2：写清单 → `bash scripts/guard-bypass/manifests.test.sh` PASS；`bash scripts/image-pin/verify-no-floating-tags.sh`、`verify-registry-sync.sh` PASS。
- [ ] Step 3：只读核对集群前提：`kubectl get clustersecretstore vault-backend`、`kubectl get svc -n aster-cloud aster-api` 存在，输出贴 PR。
- [ ] Step 4：提交 `feat(guard): aster-agents / aster-guard / aster-tools 三应用与首条工具入站策略（ADR 0054 §12 D2/D3）`，开 PR。PR 描述列出用户动作（§5 U-K1–U-K4）与**上线顺序**：7a 合并 → aster-guard Task 4 合并、两个 pin PR 自动合并 → 用户写 Vault → 7b 合并（`replicas: 0`）→ 另开 PR 把两个 Deployment 翻为 1。

### Task 8：部署后 / 定时探针工作流与专用 runner

**Files:**
- Create（k3s）：`.github/workflows/guard-bypass-probe.yml`、`scripts/guard-bypass/workflow.test.sh`
- Create（aster-api）：`.github/self-hosted-runner/run-guard-probe-runner.sh`、`.github/self-hosted-runner/com.wontlost.guard-probe-runner.plist`；Modify `.github/self-hosted-runner/README.md`（新增一节）

**Interfaces:**
- 工作流：`on: push: branches [main], paths ['apps/aster-lang/agents/**','apps/aster-lang/guard/**','apps/aster-lang/tools/**','scripts/guard-bypass/**']`；`schedule: '17 3 * * *'`；`workflow_dispatch`。**无** `pull_request`。`permissions: contents: read`。`concurrency: { group: guard-bypass-probe, cancel-in-progress: false }`。
  - `probe` job：`runs-on: [self-hosted, linux, ARM64, guard-probe]`，`environment: guard-probe`，`timeout-minutes: 45`。步骤：checkout（`persist-credentials: false`）→ 安装 pin 版本并校验 sha256 的 `kubectl`、`kustomize`、`yq`、`jq` 到 `$RUNNER_TEMP` → 把 `secrets.GUARD_PROBE_KUBECONFIG` 写入 `$RUNNER_TEMP/kubeconfig`（`umask 077`，job 结束删除）→ `token-expiry`：解 JWT `exp`，剩余 < 14 天 `::warning::`、已过期 exit 3 → `converge`：最多 15 分钟轮询，直到线上 `demo-tool` / `aster-guard-mcp` Pod 的 `imageID` digest 等于本 commit `kustomize build` 的镜像 digest、两个 Deployment `availableReplicas == 1`、线上 `demo-tool-ingress` 规格等于渲染结果（jq 规范化后比较），记录收敛时刻 → `direct-connect-probe.sh network --agents-ns aster-agents --tool-ns aster-tools --tool-selector app=demo-tool --tool-svc demo-tool:8080 --admit-ns aster-guard --probe-image <从渲染的 CronJob 读取> --positive-url http://aster-guard-mcp.aster-guard.svc.cluster.local:8080/healthz --canary-proxy http://aster-guard-mcp.aster-guard.svc.cluster.local:8080/mcp --canary-secret guard-demo-agent --run-id ${{ github.run_id }}-${{ github.run_attempt }}` → `direct-connect-probe.sh auth-status --guard-ns aster-guard --cronjob guard-auth-probe --since <收敛时刻>` → 两个 JSON 摘要写 `$GITHUB_STEP_SUMMARY` 并上传为制品 `guard-bypass-probe-<run_id>`。
  - `watchdog` job：`runs-on: ubuntu-latest`，仅 `schedule` 触发，job 级 `permissions: { actions: read }`；用 `GITHUB_TOKEN` 调 `GET /repos/wontlost-ltd/k3s/actions/workflows/guard-bypass-probe.yml/runs?branch=main&status=success&per_page=1`，最近一次成功距今 > 36 h、无任何成功运行、或查询本身失败（非 2xx / 空响应）均判失败（runner 离线时 `probe` 会排队而非失败，这一步让「没有证据」变红；查不到不能当作有证据）。判定写成 `scripts/guard-bypass/watchdog.sh <now-epoch> <api-json-file|->`，`workflow.test.sh` 覆盖 `watchdog_recent_success_passes / watchdog_stale_fails / watchdog_api_error_fails / watchdog_no_runs_fails`。
- `run-guard-probe-runner.sh`：复用 `localhost/aster-runner:2.336.0` 镜像，`RUNNER_GROUP=guard-probe`、`RUNNER_LABELS=self-hosted,linux,ARM64,guard-probe`、`CONTAINER_NAME=guard-probe-runner`、ephemeral 守护循环同 `run-runner.sh`；与之相比**不挂** podman socket、**不用** `--network host`，加 `--cap-drop ALL --security-opt no-new-privileges --pids-limit 512 --memory 1g`。
- `workflow.test.sh`（k3s）：yq 断言无 `pull_request` / `pull_request_target`；`probe` 有 `environment: guard-probe` 与 `guard-probe` 标签；checkout `persist-credentials: false`；kubeconfig 只在写入步骤出现且未被 `echo`；工具下载均有 sha256 校验；`token_expiry_warning`（以过期 / 10 天 / 60 天三个伪造 JWT 跑 token-expiry 步骤抽出的脚本函数）。

- [ ] Step 1：写 `workflow.test.sh` → FAIL；Step 2：写工作流 → PASS，`actionlint` 无输出；Step 3：接入 `verify-gate-selftests.yml`；Step 4：提交 k3s `ci(guard-bypass): 部署后与定时探针工作流（ADR 0054 §12 D9）` 与 aster-api `ci(runner): guard-probe 专用 runner（无 podman socket）`，各开 PR，描述列 U-K5–U-K7。

---

## 阶段 V：验收

### Task 9：上线与首个探针记录（用户与实施者协作）

- [ ] Step 1（用户）：完成 §5 全部用户动作，合并 Deployment `replicas: 1` 的 PR。
- [ ] Step 2（实施者，只读）：`kubectl get pods -n aster-guard`、`kubectl get pods -n aster-tools`、`kubectl describe networkpolicy -n aster-tools demo-tool-ingress`、`kubectl get cronjob,jobs -n aster-guard` → 贴 #466。
- [ ] Step 3：`guard-bypass-probe` 工作流在该次 main 推送上 `probe` job 成功（两个摘要 `verdict: pass`），把运行 URL 与摘要贴 #466，分开写三个断言：网络层「模型命名空间直连被挡」、票据层「放行位置无票据 / 伪造票据被拒且 `unticketed == 0`」、正向「持有效票据的 `demo_echo` 经代理执行、`demo_delete` 被 DENY」（金丝雀）——前两个是隔离，第三个证明前两个不是因为链路坏了。若 `verdict: inconclusive`，按摘要定位（正向对照 / 金丝雀 / DNS），不得以重跑代替排查；连续三次 inconclusive 回到设计复盘（`.claude/operations-log.md` 记录）。

### Task 10：两条反向记录（非生产副本，用户执行）

**Files（k3s，先于本任务随 Task 6 的 PR 一起交付更好；单列于此因为只在验收时运行）：** Create `scripts/guard-bypass/redteam-window.sh`、`scripts/guard-bypass/redteam-window.test.sh`

**Interfaces:**
- `redteam-window.sh --kubeconfig <path> [--ttl-minutes 20] [--dry-run]`：只能用集群管理员凭证（建命名空间），**不经 Argo**。生成 `RUN=$(date +%s)`；分别 `kustomize build apps/aster-lang/{agents,guard,tools}`，按下表取舍后用 yq 把 `aster-tools / aster-guard / aster-agents` 全文映射到 `aster-rt-$RUN-{tools,guard,agents}`（含 `namespaceSelector` 值、Service DNS、环境变量里的 URL）；镜像 digest 原样保留（副本与生产同一镜像）。

  | 来源 | 保留并改写 | 跳过（理由） |
  |---|---|---|
  | `agents` | `Namespace`、`ResourceQuota`、`LimitRange` | `Role / RoleBinding`（副本由管理员直接起 Pod）、`ExternalSecret`（副本不跑金丝雀） |
  | `guard` | `Namespace`、`ConfigMap guard-auth-probe-script`；把 `CronJob guard-auth-probe` 的 `jobTemplate` 实例化为按需 `Job`（每次票据探针一份） | 代理 `Deployment / Service`、`ExternalSecret`、`ServiceAccount`、`Role / RoleBinding`、`CronJob` 本身（副本只需要「放行位置」，不验证代理） |
  | `tools` | `Namespace`、`Deployment demo-tool`、`Service`、`NetworkPolicy demo-tool-ingress` | `ExternalSecret`（改由脚本建字面量 Secret：`ASTER_GUARD_TOOL_API_KEY=redteam-dummy`、`ASTER_GUARD_TENANT_ID=redteam`）、`Role / RoleBinding` |

  命名空间加标签 `guard-bypass/redteam-expires=<epoch>`；`trap` 删除三个命名空间，并另起 `sleep <ttl>` 的后台看门狗兜底删除。依次：
  1. **基线**（两层开）：网络探针（`network`，`--agents-ns aster-rt-$RUN-agents`，无金丝雀，`--positive-url https://kubernetes.default.svc.cluster.local/livez`）期望 0；在 `aster-rt-$RUN-guard` 起一次 `auth-probe.sh` Pod（脚本从 `apps/aster-lang/guard/probe/auth-probe.sh` 建 ConfigMap，Pod 规格与生产 CronJob 的 jobTemplate 相同）期望 0（伪造票据得 `guard_unavailable`，因为 dummy key）。
  2. **R-N（删网络策略）**：`kubectl delete networkpolicy -n aster-rt-$RUN-tools demo-tool-ingress` → 网络探针期望 **1**；票据探针仍期望 **0**（票据层独立成立）。
  3. **R-A（关票据核对）**：恢复策略，`kubectl set env deploy/demo-tool -n aster-rt-$RUN-tools TICKET_CHECK=off` 并等待就绪 → 网络探针期望 **0**（网络层独立成立）；票据探针期望 **1**（无票据调用被执行，`unticketed` 增加）。
  4. 打印六个摘要组成的表（场景 × 探针 × 期望 × 实际），任一实际 ≠ 期望则脚本退出 1。
- `redteam-window.test.sh`：假 kubectl 断言映射后渲染结果不含 `\baster-(tools|guard|agents)\b`；渲染结果中的资源种类恰为上表「保留」列；`TICKET_CHECK=off` 只出现在 R-A 步骤；trap 删除三个命名空间；`--dry-run` 只打印将执行的命令与映射后的清单，**零次**调用 kubectl（假 kubectl 调用计数为 0）。

- [ ] Step 1：测试先行 → 实现 → `bash scripts/guard-bypass/redteam-window.test.sh` PASS（随 Task 6 PR 提交）。
- [ ] Step 2（用户，约 15 分钟窗口）：`bash scripts/guard-bypass/redteam-window.sh --kubeconfig ~/.kube/k3s-config`，结束后 `kubectl get ns -l guard-bypass/redteam-expires` 为空；把六行表与两条反向摘要贴 #466。生产命名空间不受影响（脚本不读写 `aster-agents / aster-guard / aster-tools`，由 `redteam-window.test.sh` 断言）。

### Task 11：登记验证结果（aster-api docs + test PR）

**Files:** Modify `src/test/java/io/aster/guard/bypass/B1DirectConnectToolBypassTest.java`、`src/test/java/io/aster/guard/bypass/README.md`、`docs/adr/0054-guard-execution-point.md`（§7 场景表第 1 条；§12 状态行）
- [ ] Step 1：`B1DirectConnectToolBypassTest` **保持 `@Disabled`**，原因改为：`"部署层由 k3s guard-bypass-probe 工作流判定（ADR 0054 §12 D7/D9）：最近记录见 aster-api#466；JUnit 无集群凭证，不在此重复"`，类注释同步。理由：必需检查 `Guard bypass suite (#466)` 跑在 public 仓 CI 上，不应持有集群凭证；把部署层判定搬进 JUnit 只会变成另一个需要 skip 的环境依赖。
- [ ] Step 2：`README.md` 场景矩阵第 1 条（部署层）改为「已验证（部署层）：k3s 探针 + 两条反向记录，见 #466 评论 <链接>」；「后续阶段的启用条件」中阶段 C 一条改为已完成。
- [ ] Step 3：ADR 0054 §7 表第 1 条断言列后加「——**已验证**（2026-MM-DD，#466 评论 <链接>）」。
- [ ] Step 4：`./gradlew guardBypassTest` PASS（只改注解字符串），提交 `docs(guard-bypass): #466 第 1 条部署层已验证`，开 PR；合并后 #466 可关闭（其余条目已在阶段 A/B 完成的前提下）。

---

## 3. RBAC 与 runner 爆炸半径评审

| 主体 | 权限 | 能做到的最坏事 | 约束 |
|---|---|---|---|
| SA `aster-guard/guard-bypass-probe`（`pods/create` 等于**间接读取** `aster-agents` 内全部 Secret：Pod 可用 `secretKeyRef` / Secret 卷挂载，不需要 `secrets/get`。探针无权枚举 Secret，所以「该命名空间只有一个 Secret」由 git 侧持续保证——`manifests.test.sh` 断言 `apps/aster-lang/agents` 中可产生 Secret 的对象恰为 `ExternalSecret guard-demo-agent`、没有 `Secret` 种类；手工在该命名空间建 Secret 属违规，新 Agent 凭证进入时须重新评审本表） | `aster-agents`：`pods` create/get/list/watch/delete、`pods/log` get、`rolebindings` list；`aster-tools`：`pods / services / deployments / networkpolicies` get list；`aster-guard`：`pods / pods/log / jobs / cronjobs / deployments` get list。无 Secret、无 exec / attach / portforward、无 cluster 范围 | 在 `aster-agents` 起任意镜像的 Pod：消耗算力（ResourceQuota 封顶 4 Pod / 1 CPU / 1 Gi）、以 Agent 身份调用代理（可挂 `guard-demo-agent`，这本来就是金丝雀用的身份，所有调用仍经 Guard 决策）、从模型命名空间向外发起网络连接（与任何被攻破的 Agent 相同，无新增）；读三命名空间的工作负载规格（不含 Secret） | PSA restricted 禁 hostNetwork / hostPath / 特权；`aster-agents` 不得有带权限的 SA（探针静态检查 + manifests 测试）；它**不能**进入 `aster-guard`（被工具策略放行的命名空间）起 Pod，这正是票据层探针改用 CronJob 的原因 |
| kubeconfig（Environment `guard-probe` secret） | 同上 SA | 泄露即上行 | `kubectl create token guard-bypass-probe -n aster-guard --duration=2160h`（90 天）；只在 main 分支的该工作流可读；到期前 14 天告警；轮换 = 重新生成并覆盖 secret |
| runner 组 `guard-probe` | 只跑 `wontlost-ltd/k3s/.github/workflows/guard-bypass-probe.yml@refs/heads/main` | 能修改 main 上该工作流的人（k3s 写权限 + 通过 main ruleset）即可在用户 Mac 的容器里执行任意代码 | 容器无 podman socket、无 host network、cap-drop ALL、ephemeral；与 CI runner（`local-mac`，root + socket）分组；fork PR 与非 main ref 无法调度到该组 |
| CronJob `guard-auth-probe` | 默认 SA、`automountServiceAccountToken: false`，不挂任何 Secret | 无 API 权限；能从放行位置访问工具——与代理同等网络位置，但不持有任何票据或 key | Pod 由 CronJob 控制器创建，探针身份不因此获得 `aster-guard` 的 `pods/create`（若获得，可挂载代理的 Guard key 与 agents 登记） |

## 4. 依赖与上线顺序

| 顺序 | 项 | 谁 |
|---|---|---|
| 0 | 本 docs PR（ADR §12 + 计划）合并 | 用户 |
| 1 | aster-guard Task 1–3 PR（可并行开发，按序合并） | 实施者 → 用户审阅 |
| 2 | k3s PR 7a（信任根 + CIP） | 实施者开、用户人工合并 |
| 3 | aster-guard Task 4 PR 合并 → `image.yml` 产出签名镜像 → 两个 pin PR | 自动（需 U-G1、U-G2） |
| 4 | k3s Task 6 PR、PR 7b（`replicas: 0`）、Task 8 PR | 实施者 → 用户 |
| 5 | Vault、aster-cloud 演示租户与策略、runner 组与 runner、Environment | 用户（U-K1–U-K7） |
| 6 | `replicas: 1` PR → 首次探针（Task 9）→ 反向记录（Task 10）→ Task 11 | 用户 + 实施者 |
| 可选 | aster-guard 0.3.0 发版（Task 5） | 用户批准 |

## 5. 需要用户做的事

- **U-G1**：aster-cloud/aster-guard 仓库 secrets `DOCKER_USERNAME / DOCKER_PASSWORD`（与 aster-api 相同的 Docker Hub 账号，推 `wontlost/` 命名空间）。
- **U-G2**：aster-guard 建 Environment `image-pin`（部署分支仅 `main`），放 `vars.IMAGE_PIN_APP_CLIENT_ID` 与 `secrets.IMAGE_PIN_APP_PRIVATE_KEY`（image-pin App 4253193）。公开仓里放 App 私钥的风险：有 aster-guard 写权限者可开 k3s pin PR，但 pin 只接受 allowed-images 中该工作流签名的镜像。
- **U-G3**：批准并推 `v0.3.0` tag（Task 5，可选）。
- **U-K1**：在 aster-cloud 建演示租户（团队）`guard-demo`，在其下发布策略 `guard.demo/decide` 并设为默认版本（发布即部署到 aster-api，ADR 0054 §11 D1）：`demo_echo` → ALLOW，`demo_delete` → DENY；建两个 **MEMBER** API key：代理用、工具用（必须是两个不同 key，ADR 0054 §3.3 `auth` 第 1 条）。
- **U-K2**：`vault kv put secret/apps/aster-guard-demo proxy-api-key=… tool-api-key=… tenant-id=… agent-token="$(openssl rand -hex 32)" agents-json='{"agents":[{"id":"guard-probe-canary","tokenSha256":"<上面 token 的 sha256>"}]}'`（一次写全，KV-v2 put 整体替换）。
- **U-K3**：合并 k3s PR 7a（受保护路径，人工）。
- **U-K4**：合并 Deployment `replicas: 0 → 1` 的 PR。
- **U-K5**：建 org runner 组 `guard-probe`：`visibility: selected`（仅 `wontlost-ltd/k3s`）、`allows_public_repositories: true`、`restricted_to_workflows: true`、`selected_workflows: ["wontlost-ltd/k3s/.github/workflows/guard-bypass-probe.yml@refs/heads/main"]`；在 Mac 上以 `run-guard-probe-runner.sh`（launchd plist）注册 runner。
- **U-K6**：`kubectl create token guard-bypass-probe -n aster-guard --duration=2160h` 生成 kubeconfig，存入 k3s Environment `guard-probe`（部署分支仅 `main`）的 secret `GUARD_PROBE_KUBECONFIG`；日历提醒 80 天后轮换。
- **U-K7**：Task 10 的反向记录窗口（约 15 分钟，管理员 kubeconfig）。

## 6. 待裁定

- **O1（runner 位置，偏离 U3 第 3 条的执行方式，不偏离其权限模型）**：现有 self-hosted runner 组不服务 public 仓，且当前没有 Linux runner 在线。默认方案为上面的专用组 `guard-probe`。备选：探针 job 跑在 GitHub 托管 runner（`ubuntu-24.04-arm`）上，凭同一 Environment secret；SA 权限不变，少一台依赖用户 Mac 在线的机器与一个允许 public 仓的 runner 组，代价是集群凭证会出现在托管机器上（API server `158.179.18.241:6443` 本就公网可达）。
- **O2（票据层探针位置）**：默认 `aster-guard` 中的 CronJob（ADR §12 D7 的理由）。备选：给探针 SA 增加一个专用命名空间 `aster-guard-probe` 的 `pods/create` 并在工具策略中放行它——工具策略会多放行一个命名空间，故不推荐。
- **O3（工具 key 的权限）**：工具只需要 `GET /decisions/{id}`，但该端点要求 MEMBER，MEMBER key 也能建决策与 consume。是否为 aster-api 增加只读回读角色另立 issue；本计划接受 MEMBER。

## 7. 自审记录

- 规格覆盖：U3-1 两层 → Task 1（`--isolation` 列表）、Task 3（票据）、Task 7（策略）；kube-router 缺陷 → Global Constraints 策略形状、`lib.sh`、Review Focus 1；U3-2 演示工具与 HTTP 代理 → Task 1–4、命名空间与命名见 ADR §12 D3；U3-3 runner SA → Task 7 RBAC、Task 8、§3、O1；每层独立通过 → Task 9（生产两处探针）；反向记录与安全关闭方式、执行人 → Task 10、ADR §12 D8；各仓改动 → §2；`B1DirectConnectToolBypassTest` → Task 11 Step 1；探针脚本与 `.test.sh` → Task 6；安全评审 → §3；发版与用户动作 → Task 5、§5；验收四项（#466 exit-0 记录、两条反向记录、§7 第 1 条标已验证、#466 可关闭）→ Task 9–11。Review Focus 六类失败（kube-router、DNS / egress、探针镜像 digest、PSA、遗留 Pod、SA token）分别落在 Review Focus 1–5 与 Global Constraints「镜像」「Pod 安全」。
- 类型一致：`UpstreamSpec / Isolation / AgentEntry / startHttpProxy / createToolNameCache / EffectLog / TicketRejection` 在 Task 1–3 同名；探针参数与工作流调用逐字一致；命名见 Global Constraints。
- 取代关系：本计划取代 `2026-11-14-guard-bypass-suite.md` 的 Task C1（探针接口改为子命令 `network` / `auth-status`、退出码加 3、Pod 不用 `--rm -i`）与 Task C2，旧计划阶段 C 节首已在本 PR 中加取代指针（避免实施期间被当作仍有效）。
