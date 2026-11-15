# ADR 0057：k3s control-plane 主机配置 —— apiserver 审计日志与探针的 GitHub OIDC 认证

- 状态：**PROPOSED**（2026-11-16 提出，待用户确认）
- 提出：2026-11-16；来源 ADR 0054 §12.1 的两个跟进项：U-K8（apiserver 审计日志）与 O5（探针凭证改 GitHub OIDC），见 wontlost-ltd/aster-api#466 阶段 C 计划 `docs/superpowers/plans/2026-11-15-guard-bypass-phase-c.md` §5 U-K8、§6 O5
- 相关：ADR 0054 §12 D9（探针凭证、准入策略 `guard-probe-pod-scope` / `guard-agents-secret-allowlist`）、§12.1（firewalld masquerade 前置条件发现、U-K8 残余风险）
- 实施计划：`docs/superpowers/plans/2026-11-16-k3s-apiserver-audit-and-probe-oidc.md`
- 实现仓：`wontlost-ltd/k3s`（`hosts/k3s-server/`、`scripts/guard-bypass/`、`.github/workflows/guard-bypass-probe.yml`、`apps/aster-lang/{agents,guard,tools}`、`apps/infrastructure/guard-admission`）

**为什么另立 ADR，而不是 ADR 0054 §12.2**：两项改动都落在 control-plane 主机上，作用于整个集群，不只服务 aster-guard。审计策略约束所有身份的所有 API 请求；「主机配置进仓库、逐台滚动安装」是一条新的集群级约定，今后的 apiserver 参数都要照此办理。这些内容放进「aster-guard 执行点」的 ADR，读者就找不到了。所以 ADR 0054 只在 §12.2 留一行指针，与探针有关的决定（身份、RBAC、准入策略、工作流）写在本 ADR 的 §4。

---

## 0. TL;DR

1. **审计（U-K8）**：三台 control-plane 的 kube-apiserver 开启文件审计日志。缺省记 Metadata 级。以下请求记 RequestResponse：探针身份（SA 与 OIDC 两个用户名）的**写**请求、`aster-agents` 的 Pod 写入、RBAC 与准入配置的写入。Secret、ConfigMap、TokenReview、`serviceaccounts/token` 永远只记 Metadata，而且这条规则排在所有记录请求体的规则之前。以下高频噪声不记：lease 心跳、事件写入、系统组件的读、所有身份的 watch（探针除外）、健康检查与 discovery。日志写在本地 `/var/lib/rancher/k3s/server/logs/audit.log`，保留规则为 100 MB 轮转、gzip、保留 20 份、最长 30 天。按估算的日增量，份数上限先到，可保留约 8–22 天；磁盘占用预计约 0.5 GB（gzip 后，属估算），未压缩的硬上限约 2.1 GB。集群目前没有日志后端（OTel collector 的 logs 管道只接 `debug` exporter，没有 Loki），所以**不外送**。
2. **OIDC（O5）**：kube-apiserver 加 `--authentication-config`，使用 `apiserver.config.k8s.io/v1` 的 `AuthenticationConfiguration`（k8s 1.34 GA，集群是 v1.35.0+k3s1）。JWT 发行方为 `https://token.actions.githubusercontent.com`，audience 为 `k3s-guard-probe`。CEL 规则要求以下 claim 同时成立：`repository`、`repository_id`、`repository_owner_id`、`job_workflow_ref`（`…/guard-bypass-probe.yml@refs/heads/main`）、`ref`、`environment == guard-probe`、`sub`、`runner_environment == github-hosted`、`event_name`。通过后映射为固定用户名 `github-actions:wontlost-ltd/k3s:guard-probe`，并把 `run_id` / `sha` 等写入 `user.extra`，审计日志据此可以回溯到具体运行。现有三条 RoleBinding 与一条 ClusterRoleBinding 各加这个 User subject，权限与 SA 完全相同；`guard-probe-pod-scope` 的 `matchConditions` 改成匹配两个用户名。
3. **工作流**：只有 `probe` job 有 `id-token: write`。凭证模式由 Environment 变量 `GUARD_PROBE_AUTH` 决定：`static`（缺省，行为与今天完全一致）、`dual`（探针仍用静态 SA token，同时断言 OIDC 身份、RBAC 与准入匹配）、`oidc`（只用 OIDC）。OIDC 的 kubeconfig 不含 exec 插件：`server` 与集群 CA（公开证书，入库）加上 `tokenFile`。token 文件在每次调用 kubectl 前按需刷新。因此 k3s PR 可以先合并，apiserver 改好之前也不影响静态路径。
4. **主机配置入库**：k3s 仓新增 `hosts/k3s-server/`，包含两个 `config.yaml.d` drop-in（以 `kube-apiserver-arg+:` 追加，不改手写的 `config.yaml`）、审计策略、认证配置，以及节点脚本 `node.sh`。`node.sh` 提供 `check / preflight / apply / verify / rollback / status` 子命令，用户经 ssh 运行。`check` 同时守住 #466 的 firewalld 教训：`trusted` 区（运行时与永久配置）不得开 masquerade，已安装文件不得与仓库漂移。
5. **滚动重启**：每次只动一台节点。顺序是先非 etcd leader、最后 leader，并避开探针定时运行的 03:17 UTC；窗口期间不手动触发探针、不合并会触发它的 PR，窗口内的失败为预期，结束后重跑一次。每台节点重启前要通过 preflight：3 个 apiserver 端点就绪、etcd 3 个成员都已启动且本地健康、磁盘余量足够、GitHub 发现文档可达（仅 oidc 组件需要）。重启后 `verify` 必须通过才动下一台。失败时 `node.sh` 自动回滚该节点（删除 drop-in 后再重启），其余两台节点保持仲裁。

## 1. 现状（2026-11-16，只读核对）

| 事实 | 证据 |
|---|---|
| 集群 v1.35.0+k3s1：3 台 server（`master-1/2/3`，内置 etcd，`master-1` 带 `--cluster-init`）、1 台 agent（`worker-1`）；Oracle Linux 9.5 aarch64，每台 1 vCPU / 5.5 GiB | `kubectl get nodes -o wide`；节点注解 `k3s.io/node-args` |
| server 参数里没有任何 `kube-apiserver-arg`，也就没有审计日志与认证配置（U-K8 已核实：`config.yaml` 无 `audit-*`，无 `/var/lib/rancher/k3s/server/logs/`） | `k3s.io/node-args` = `server --v 1 [--cluster-init] --node-taint CriticalAddonsOnly=true:NoExecute --tls-san 130.162.194.32`；ADR 0054 §12.1 |
| 根分区 29.4 GiB，可用 5.7（master-2）/ 6.8 / 7.1 GiB；`/var/lib/rancher` 在根分区 | Prometheus `node_filesystem_avail_bytes`（只读查询） |
| apiserver 请求量（6 h 平均，全集群）：非 watch 的请求约 9.4/s，其中 lease 的 GET + PUT 合计 15.4/s（按 verb 汇总，lease 占了绝大部分）。排除 lease 与 watch 后约 5.1/s；单台 apiserver 约 2–4 req/s（含 lease） | `apiserver_request_total` |
| 系统身份：`system:k3s-controller`、`system:kube-controller-manager`、`system:kube-scheduler`、`system:kube-proxy`、`k3s-cloud-controller-manager`、`kube-apiserver`，以及组 `system:nodes`、`system:monitoring` | 所有 (Cluster)RoleBinding 的非 SA subject |
| 没有日志后端：命名空间只有 `monitoring`（kube-prometheus-stack）、`tempo`、`otel-system`；OTel collector 是单副本 Deployment（在 worker-1 上），`logs` 管道只接 `debug` exporter；没有 Loki / promtail / fluent / vector / alloy，也没有 DaemonSet 能读 master 的文件 | `kubectl get ns,ds -A`；ConfigMap `otel-system/otel-collector-opentelemetry-collector` |
| 出站：三台 master 都在公有子网（路由表 `0.0.0.0/0 → Internet Gateway`），各有公网 IP；安全列表出站 `0.0.0.0/0 all`。所以 apiserver 访问 `token.actions.githubusercontent.com:443` 在 OCI 层不受限，主机 firewalld 缺省也不限出站（preflight 实测） | `oci network route-table list`、`security-list list`、`compute instance list-vnics`（只读） |
| 探针的 API 地址 `https://158.179.18.241:6443` 是 **master-1** 的临时公网 IP（不是负载均衡），所以 master-1 重启期间探针连不上 API | 同上；kubeconfig `server` |
| 集群 CA `CN=k3s-server-ca@1742207648`，有效期至 2035-03-15；`kube-root-ca.crt` 与管理员 kubeconfig 的 CA 一致（SHA-256 `43:BB:3B:FA:…:A4:8B`） | `kubectl get cm kube-root-ca.crt`、`openssl x509 -fingerprint` |
| `wontlost-ltd/k3s`：`id 1116032527`，org `id 81835091`，**private**（阶段 C 计划 §0 写的是 public，以本次查询为准，不影响本 ADR）；OIDC `sub` 用缺省模板（`use_default: true`）；Environment `guard-probe` 只放行自定义分支策略 `main` | `gh api repos/wontlost-ltd/k3s`、`…/actions/oidc/customization/sub`、`…/environments` |
| 准入策略只有 `guard-probe-pod-scope` 按身份匹配：`matchConditions` 为 `request.userInfo.username == 'system:serviceaccount:aster-guard:guard-bypass-probe'`。`guard-agents-secret-allowlist` 对 `aster-agents` 的所有 Secret CREATE 生效，与身份无关 | k3s `apps/infrastructure/guard-admission/*.yaml` |
| 结构化认证配置：v1 在 1.34 GA。不能与 `--oidc-*` 同时使用（同时设置 apiserver 会直接退出）。文件每分钟重读一次，新内容无效时保留旧配置。发行方在启动时可以离线（异步初始化，不阻塞启动），但**启动时文件本身无效则 apiserver 退出** | kubernetes.io Authenticating §「Using Authentication Configuration」；KEP-3331；`pkg/kubeapiserver/authenticator/config.go` |

## 2. 决策

| # | 决定 | 理由 |
|---|---|---|
| D1 | 审计策略按 §3 的规则表，首条命中即生效；`omitStages: [RequestReceived]`、`omitManagedFields: true` | 目标是能还原「某个凭证发了哪些 API 调用」。Metadata 已经包含用户、`user.extra`、源 IP、UA、verb、资源、名称、状态码。请求体只在写入且有取证价值时才记 |
| D2 | **敏感资源的 Metadata 规则排在所有记录请求体的规则之前**：`secrets`、`configmaps`、`serviceaccounts/token`（TokenRequest 的响应就是 token）、`tokenreviews`（请求体就是 token） | 任何身份（包括探针规则）都不可能把凭证写进审计日志。测试用策略求值器断言这一点，而不是只检查规则顺序 |
| D3 | 探针两个身份的**所有**请求都记，并排在噪声排除规则之前：写入记 RequestResponse，读 / watch / 越权失败记 Metadata | 泄露时要界定影响面，所以失败的越权尝试与 watch 也要留证。读请求的响应体（Pod 列表）没有额外取证价值，每天的轮询却有几百次，所以不记 |
| D4 | 以下请求记 RequestResponse（与身份无关）：`aster-agents` 的 `pods`、`pods/ephemeralcontainers`、`pods/eviction` 写入；全集群 RBAC 四类对象与 `admissionregistration.k8s.io` 下所有对象的写入 | 这几类对象决定探针的爆炸半径：谁给探针加了权限、谁删改了准入策略、`aster-agents` 里起了什么 Pod。它们本身就低频 |
| D5 | 不记的噪声：`coordination.k8s.io/leases` 的 get / update / watch；`events`（core 与 `events.k8s.io`）的写入；§1 所列系统用户与组 `system:nodes` 的 get / list / watch；所有身份的 watch（探针身份除外，D3 已先命中）；`/healthz*`、`/livez*`、`/readyz*`、`/version`、`/metrics` 与 discovery（`/api*`、`/apis*`、`/openapi*`） | lease 心跳与续约是最大的请求源（约 15/s）。watch 是长连接，每次重连都会产生一条记录。系统组件的读请求没有取证价值。代价是：非探针身份的 watch 不留痕，接受 |
| D6 | 日志后端是本地文件 `/var/lib/rancher/k3s/server/logs/audit.log`（目录 0700），参数为 `audit-log-format=json`、`maxsize=100`、`maxbackup=20`、`maxage=30`、`audit-log-compress=true`，模式保持缺省 `blocking`。**不外送**：集群没有日志后端（§1） | 按 §3 的估算，每节点每天 90–260 MB 原始日志。20 份 × 100 MB 共 2 GB 原始日志，份数上限先于 30 天的 maxage 到达，可保留约 8–22 天，足以覆盖探针每日运行之后一周以上的追查。磁盘占用：gzip 后预计约 0.5 GB（压缩比按约 8 倍估算，属估算值，以 `node.sh status` 实测为准），不到最紧的 master-2 可用空间的 10%；未压缩的硬上限是 21 × 100 MB ≈ 2.1 GB，所以 preflight 要求可用空间 ≥ 2 GiB。保留天数实测低于 7 天就调大 maxbackup。外送要在 1 vCPU 的 master 上加 DaemonSet 并新建 Loki，与本 ADR 的问题不相称；留作 Q1 |
| D7 | 结构化认证配置见 §4.1：一个 JWT 发行方，CEL 规则同时校验仓库名与不可变 ID、`job_workflow_ref`、`ref`、`environment`、`sub`、`runner_environment`、`event_name` 和 token 有效期上限。用户名是常量形状 `github-actions:wontlost-ltd/k3s:guard-probe`，不映射组。`userValidationRules` 禁止 `system:` 前缀的用户名与组，并要求用户名恰为该值 | 只用 `repository` 校验，删库后同名重建就能冒用，所以加上 `repository_id` / `repository_owner_id`。只用 `environment`，同仓库其它工作流挂同一个 Environment 也能取得身份，所以加 `job_workflow_ref` 并钉到 main。用户名固定，RBAC、VAP 和审计查询才能逐字一致。没有映射组，任何身份都不会因为组成员资格而多拿权限 |
| D8 | RBAC 与准入的**身份并集**：四条绑定各加 `kind: User, name: github-actions:wontlost-ltd/k3s:guard-probe`；`guard-probe-pod-scope` 的 `matchConditions` 改为 `request.userInfo.username in [SA, OIDC 用户]`。`vap-check.sh` 对两个身份各跑一遍探针用例 | 新身份必须先受准入策略约束，才能拿到 `pods/create`；两处改动在同一个提交里。apiserver 加 `--authentication-config` 之前，这个用户名无法通过认证，所以合并 k3s PR 不扩大任何人的权限 |
| D9 | 工作流按 §4.2 改：`id-token: write` 只加在 `probe` job；`GUARD_PROBE_AUTH` 取 `static | dual | oidc`，缺省 `static`，其它值判 3；OIDC kubeconfig 用 `tokenFile`，不用 exec 插件；身份断言仍是 `kubectl auth whoami` | 有了模式开关，k3s PR 可以先于 apiserver 改动合并，静态路径逐字节不变。`dual` 先证明 OIDC 端到端可用，再切换到 `oidc`。GitHub OIDC token 的有效期没有文档承诺（公开样本从 5 分钟到 6 小时不等），所以每次调用 kubectl 前按需刷新，不依赖有效期长短 |
| D10 | 主机配置进 k3s 仓 `hosts/k3s-server/`，见 §5：由 drop-in 承载参数，文件安装到 `/etc/rancher/k3s/aster/`，`node.sh` 是唯一的安装与核对入口。`check` 同时核对 firewalld `trusted` 区不得开 masquerade（运行时与永久配置）、已安装文件与仓库一致、`config.yaml` 里没有冲突的键（只按键名计数，不读值） | 节点配置今天靠手改，#466 的 masquerade 问题就是手改造成的漂移。drop-in 让回滚变成「删一个文件再重启」，不碰含 server token 的 `config.yaml` |
| D11 | 滚动重启每次一台，先非 leader、最后 etcd leader；每台节点必须先通过 preflight，重启后通过 verify，才能处理下一台；verify 失败就自动回滚该节点。审计与 OIDC 可以合并为一轮重启，也可以分两轮，缺省分两轮（§6） | 3 个成员的 etcd 可以容忍 1 台不可用。分两轮时，出问题能直接定位到某个组件。每台重启约 1–3 分钟，两轮共六次重启，用户可以在同一个维护窗口里做完 |
| D12 | 迁移顺序见 §7：先在 `dual` 下连续两次成功（一次 push、一次 schedule），切到 `oidc` 后再运行一次，然后删除 Environment secret 与 SA token，最后另起 PR 删掉静态路径。SA 本身保留 | 新旧两种凭证并行时，证据不中断。SA 保留是因为删除它会让所有 SA token 立即失效，作为回滚路径；RBAC 的 SA subject 在静态路径删除之后再移除 |

## 3. 审计策略

规则按顺序排列，首条命中生效。完整文件：k3s `hosts/k3s-server/files/etc/rancher/k3s/aster/audit-policy.yaml`。

| # | 匹配 | 级别 |
|---|---|---|
| 1 | 非资源 URL：`/healthz*`、`/livez*`、`/readyz*`、`/version`、`/version/*`、`/metrics`、`/openid/v1/jwks`、`/.well-known/openid-configuration` | None |
| 2 | `secrets`、`configmaps`、`serviceaccounts/token`、`authentication.k8s.io/tokenreviews`（任何身份、任何 verb） | Metadata |
| 3 | 用户 = 探针两个身份，verb ∈ create / update / patch / delete / deletecollection | RequestResponse |
| 4 | 用户 = 探针两个身份（其余所有请求） | Metadata |
| 5 | `coordination.k8s.io/leases`，verb ∈ get / update / patch / watch / list | None |
| 6 | `events`、`events.k8s.io/events`，verb ∈ create / update / patch | None |
| 7 | §1 所列系统用户与组 `system:nodes`，verb ∈ get / list / watch | None |
| 8 | 任何身份的 watch | None |
| 9 | 非资源 URL：`/api`、`/api/*`、`/apis`、`/apis/*`、`/openapi/*` | None |
| 10 | 命名空间 `aster-agents`：`pods`、`pods/ephemeralcontainers`、`pods/eviction` 的写入 | RequestResponse |
| 11 | RBAC 四类对象、`admissionregistration.k8s.io/*` 的写入 | RequestResponse |
| 12 | 其余所有请求 | Metadata |

**容量估算**：排除第 5–9 条后，全集群约 3–5 req/s，单台最忙的 apiserver 约 1–2 req/s。Metadata 事件按 1–1.5 KB 计，每节点每天约 90–260 MB 原始日志（RequestResponse 只占几 MB）。保留期由份数决定：2 GB ÷ 90–260 MB/天 ≈ 8–22 天（maxage 30 天不会先到）。按 D6 的参数，预计磁盘占用为当前文件 100 MB，加 20 份压缩备份 × 约 12 MB，再加轮转瞬间的 100 MB，约 0.45 GB（估算）；压缩失效时的硬上限约 2.1 GB。preflight 要求 `/var/lib/rancher` 可用空间 ≥ 2 GiB。上线一周后用 `node.sh status` 核对实际速率，偏离估算 2 倍以上就调整 maxbackup。

**查询**（节点上，用户执行；日志可能含 Pod 规格，不要整份外传）：`zcat -f /var/lib/rancher/k3s/server/logs/audit*.log* | jq -c 'select(.user.username=="github-actions:wontlost-ltd/k3s:guard-probe") | {t:.requestReceivedTimestamp, run:.user.extra["aster-lang.dev/gha-run-id"], v:.verb, r:.objectRef, code:.responseStatus.code, ip:.sourceIPs}'`。审计日志分散在三台节点上（请求落在哪台 apiserver，就记在哪台），还原一个凭证的全部调用，需要合并三台的日志。

## 4. 探针凭证：GitHub OIDC

### 4.1 认证配置

文件：k3s `hosts/k3s-server/files/etc/rancher/k3s/aster/authentication-config.yaml`；参数：`kube-apiserver-arg+: [authentication-config=/etc/rancher/k3s/aster/authentication-config.yaml]`。

- `apiVersion: apiserver.config.k8s.io/v1`，`kind: AuthenticationConfiguration`。**不设** `anonymous` 字段：k3s 自己会传 `--anonymous-auth`，两者同时出现时 apiserver 拒绝启动。
- `issuer.url: https://token.actions.githubusercontent.com`，`audiences: [k3s-guard-probe]`。不设 `discoveryURL`，也不设 `certificateAuthority`，使用系统信任库。
- `claimValidationRules`（全部满足才通过）：`repository == 'wontlost-ltd/k3s'`、`repository_id == '1116032527'`、`repository_owner_id == '81835091'`、`job_workflow_ref == 'wontlost-ltd/k3s/.github/workflows/guard-bypass-probe.yml@refs/heads/main'`、`ref == 'refs/heads/main'`、`environment == 'guard-probe'`、`sub == 'repo:wontlost-ltd/k3s:environment:guard-probe'`、`runner_environment == 'github-hosted'`、`event_name in ['push','schedule','workflow_dispatch']`、`exp - iat <= 86400`。缺少某个 claim 时 CEL 求值出错，即认证失败（失败即关闭）。
- `claimMappings.username.expression: "'github-actions:' + claims.repository + ':' + claims.environment"`（规则通过后恒为常量）。`extra`：`aster-lang.dev/gha-run-id`、`…/gha-run-attempt`、`…/gha-sha`、`…/gha-actor`、`…/gha-event`、`…/gha-jti`，用于审计回溯。
- `userValidationRules`：`!user.username.startsWith('system:')`；`user.username == 'github-actions:wontlost-ltd/k3s:guard-probe'`；`user.groups.all(g, !g.startsWith('system:') || g == 'system:authenticated')`（不映射组时恒真，作为防回归）。
- **改这个文件不需要重启**：apiserver 每分钟重读一次，新内容无效时保留旧配置，所以第一次加参数之后，再调整规则都不用重启。**加参数的那次重启**才是风险点：文件无效时 apiserver 起不来，见 §6 的自动回滚。

### 4.2 工作流

- `probe` job 的 `permissions: {contents: read, id-token: write}`；工作流级保持 `contents: read`；`watchdog` 不变。
- 第一步解析 `vars.GUARD_PROBE_AUTH`（缺省 `static`，经 `env` 传入而非内联插值），不在枚举内就判 3。
- `static` / `dual`：照旧写入静态 kubeconfig，照旧检查 token 有效期。
- `dual` / `oidc`：`scripts/guard-bypass/oidc-kubeconfig.sh init` 先向 runner 取一个 audience 为 `k3s-guard-probe` 的 ID token，在本地按 §4.1 的 claim 核对一遍（目的是给出可读的错误；真正的校验在 apiserver）。然后写入 `$RUNNER_TEMP/oidc/{token,kubeconfig}`（0600；kubeconfig 只含 `server`、集群 CA 与 `tokenFile`），以及一个 `kubectl` 包装脚本：每次调用前，若 token 已超过 60 秒或剩余不足 120 秒就刷新，然后 `exec` 真正的 kubectl。集群 CA 是公开证书，存在 `scripts/guard-bypass/k3s-server-ca.crt`。`server` 是工作流 `env` 常量 `https://158.179.18.241:6443`。
- `scripts/guard-bypass/assert-identity.sh <mode>`：`static` 下断言 SA 身份；`oidc` 下断言 OIDC 用户；`dual` 下两者都断言，并对 OIDC 身份断言 `auth can-i create pods -n aster-agents` = yes、`get secrets -n aster-agents` = no。任一不符判 3。
- `dual` 下，探针本身仍然使用静态凭证；`oidc` 下，PATH 中的包装脚本排在真正的 kubectl 前面，`KUBECONFIG` 指向 OIDC 的 kubeconfig。
- 最后一步 `always()` 删除两份 kubeconfig 与 token 文件。

### 4.3 风险边界的变化

- 仓库与 Environment 中不再有长期集群凭证。ID token 只能在 main 上本工作流的 `probe` job 里取得，存在于托管 VM 的 `$RUNNER_TEMP`。被截获后，在 `exp` 之前（GitHub 的有效期没有文档承诺，D7 的上限是 24 小时）能做的事与今天的 SA token 相同（ADR 0054 §12 D9）；每次使用都带 `run_id` 记入审计。
- `id-token: write` 让该 job 能为**任意** audience 签 token。目前只有集群信任 audience `k3s-guard-probe`，Vault 的 OIDC 后端是 Authentik，与 GitHub 无关。今后若有别的依赖方信任这个仓库的 token，必须同样钉住 `job_workflow_ref` 与 audience。
- 新增的依赖：apiserver 要访问 GitHub 的发现文档与 JWKS（只在 OIDC 认证时用到）。GitHub 不可用时，只有探针认证失败（判 3、变红），其它认证方式不受影响。

## 5. 主机配置版本化（`hosts/k3s-server/`）

```
hosts/k3s-server/
  files/etc/rancher/k3s/config.yaml.d/50-aster-audit.yaml   # kube-apiserver-arg+: 审计 7 项
  files/etc/rancher/k3s/config.yaml.d/60-aster-oidc.yaml    # kube-apiserver-arg+: authentication-config
  files/etc/rancher/k3s/aster/audit-policy.yaml
  files/etc/rancher/k3s/aster/authentication-config.yaml
  node.sh            # check | preflight <c> | apply <c> | verify <c> | rollback <c> | status；c ∈ audit | oidc
  *.test.sh          # 离线：策略求值器、配置形状与身份一致性、node.sh（假 systemctl / firewall-cmd / curl / kubectl）
```

- 分发：用户在 Mac 上执行 `tar -C hosts/k3s-server -czf - . | ssh <user>@<node> '…tar -xzf - -C ~/aster-host && sudo bash ~/aster-host/node.sh …'`。脚本不联网下载，也不读取或打印 `config.yaml` 的值。
- `check`（只读，可以随时跑，也可以用于定期巡检）：firewalld 运行中时，`trusted` 区的运行时与永久配置都必须是 `--query-masquerade` = no；其它区开着 masquerade 只告警。逐个组件核对已安装文件与本仓库副本的 sha256，结果为 `installed / absent / drift`。`config.yaml` 中 `audit-`、`oidc-`、`authentication-config` 键的出现次数必须为 0。本机必须是 k3s server（`k3s.service` 存在）。
- `apply` 依次执行：preflight；把将被覆盖的文件备份到 `/var/lib/rancher/aster-host-backup/<时间戳>/`；以 root 0600 安装文件（审计组件另建 0700 的日志目录）；`systemctl restart k3s`；等待就绪；verify。等待超时或 verify 失败时，自动执行 rollback（删除本组件文件，恢复备份，再重启），并以 1 退出。

## 6. 滚动重启（摘要；逐条命令见 k3s `docs/runbooks/k3s-server-host-config.md`）

1. 维护窗口避开 03:17 UTC 的探针。窗口期间不手动触发 `guard-bypass-probe`，也不合并会触发它的 PR：master-1 重启时，探针的 API 地址不可达，运行会以 3 退出，这是预期内的失败；窗口结束后重跑一次，确认变绿。然后在 Mac 上执行 `kubectl get nodes` 与 `kubectl get endpoints kubernetes -n default`，确认 3 个 master 都 Ready、端点是 3 个 IP；然后在 master-1 上执行 `k3s etcd-snapshot save --name pre-aster-host`。
2. 在三台节点上分别执行 `node.sh status`，记下 etcd leader，顺序为两台非 leader 在前、leader 最后。
3. 对第一台执行 `node.sh apply audit`，它会依次完成 preflight、安装、重启、等待、verify。verify 包括：`k3s` active；本地 `/readyz` 返回 200；k3s 日志中 `Running kube-apiserver` 那一行带上了新参数；`audit.log` 在增长，最后一行是 `audit.k8s.io/v1` Event；端点恢复为 3 个；本节点 Ready。
4. 在 Mac 上再看一次 `kubectl get nodes` 与 endpoints，确认之后才处理下一台。
5. 三台都完成后，对每台执行 `node.sh check`。oidc 组件重复 2–5。oidc 的 verify 额外要求：参数行带 `--authentication-config`；本次启动之后，日志中不能出现 `oidc authenticator` 的初始化错误。
6. **回滚**：单台执行 `node.sh rollback <c>`（删除文件并重启）。apiserver 起不来、`node.sh` 也没能完成自动回滚时，手工删除 `/etc/rancher/k3s/config.yaml.d/<50|60>-aster-*.yaml`，再执行 `systemctl restart k3s`。只要另外两台仍然健康，仲裁就在。

## 7. 迁移与回滚（凭证）

| 步 | 动作 | 退出条件 | 回滚 |
|---|---|---|---|
| M0 | 合并 k3s PR（`GUARD_PROBE_AUTH` 未设置，即 `static`）；Argo 同步 RBAC / VAP；用户跑 `vap-check.sh`，两个身份都要全部符合 | 探针在 `static` 下照常变绿 | git revert |
| M1 | 按 §6 安装 oidc 组件 | 三台 verify 通过 | `node.sh rollback oidc` |
| M2 | 设置 Environment 变量 `GUARD_PROBE_AUTH=dual`，手动触发一次运行 | 连续两次运行绿：一次 `workflow_dispatch`、一次 schedule；三台的审计日志里能查到 OIDC 用户和对应的 `run_id` | 删除该变量（回到 `static`） |
| M3 | 设为 `oidc` | 一次运行绿 | 改回 `dual` |
| M4 | `gh secret delete GUARD_PROBE_KUBECONFIG --env guard-probe`；吊销已签发的 SA token：删除 SA 后由 Argo 重建，新 UID 让旧 token 全部失效 | 下一次 `oidc` 运行绿 | 重新签发 SA token（`kubeconfig-for-probe.sh`）并设回 `dual` |
| M5 | 另起 k3s PR 删除静态路径：`kubeconfig-for-probe.sh`、`token-expiry.sh`、模式开关；从 RBAC 与 VAP 中移除 SA 身份 | 测试与探针绿 | git revert |

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 认证配置或审计策略无效，apiserver 起不来 | 离线测试覆盖形状与身份一致性；每次只动一台；`node.sh` 自动回滚；另外两台保持仲裁 |
| 审计日志写满根分区（最紧的一台可用 5.7 GiB） | 有 maxbackup / maxage 上限，并开启 gzip；未压缩硬上限约 2.1 GB，preflight 要求可用空间 ≥ 2 GiB；`status` 报告实际占用 |
| 审计的 `blocking` 模式拖慢 1 vCPU 的 apiserver | 排除规则去掉了约四分之三的请求；verify 之后观察 `apiserver_request_duration_seconds`，若 p99 上升超过 20%，改用 `audit-log-mode=batch` |
| master-1 重启期间探针连不上 API（kubeconfig 指向 master-1 的公网 IP） | 维护窗口避开 03:17 UTC，窗口内不手动触发、不合并会触发探针的 PR；窗口内的失败为预期，结束后必须重跑一次并确认绿（watchdog 口径为 36 小时） |
| 审计日志只存在本地：节点丢失，它的日志也随之丢失；攻击者拿到 root 后可以删日志 | 接受（Q1）；探针凭证本身拿不到节点 root |
| GitHub 改变 OIDC claim 或 `sub` 模板，探针认证失败 | 失败即关闭（判 3）；规则改动无需重启（自动重读） |
| 手工改动再次引入 masquerade 或文件漂移 | `node.sh check`（Q2 讨论是否定期运行） |

## 9. 待裁定

- **Q1（外送）**：缺省不外送。备选方案：在 master 上部署 Loki 单体 + Alloy DaemonSet（需要容忍 `CriticalAddonsOnly`），在 1 vCPU 的节点上大约多占 100–200 m CPU。建议等出现第二个需要集中日志的场景再做。
- **Q2（巡检）**：缺省由用户在每次改主机配置或升级 k3s 之后手动跑 `node.sh check`。备选：做一个 systemd timer，把结果写进 journal，再由 node-exporter textfile 暴露给 Prometheus 告警。本 ADR 不做。
- **Q3（一轮还是两轮重启）**：缺省分两轮（D11）。用户可以选择一轮完成（执行 `apply audit,oidc`），重启次数减半，代价是出问题时不好判断是哪个组件。

## 10. 与 ADR 0054 的关系

本 ADR 落地后，ADR 0054 §12.1 的「U-K8 残余风险」改为「已关闭（ADR 0057 §3）」，§12 D9 的凭证描述改为 OIDC。这两处文字由计划的最后一个任务在 M4 之后修改，在此之前只在 §12.2 加一行指针。
