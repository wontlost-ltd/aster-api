# k3s apiserver 审计日志与探针 GitHub OIDC 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做到能事后还原任一凭证（首先是部署层探针身份）发过哪些 API 调用；用 GitHub OIDC 取代 Environment `guard-probe` 中 30 天有效的 SA token，使仓库里不再有长期集群凭证；同时把 control-plane 的主机配置纳入 k3s 仓的版本管理，带可重复的安装与漂移核对。

**Architecture:** k3s 仓新增 `hosts/k3s-server/`，内容是两个 `config.yaml.d` drop-in（`kube-apiserver-arg+:`）、审计策略、`AuthenticationConfiguration`，以及节点脚本 `node.sh`；用户经 ssh 逐台运行，每次重启一台。探针侧改动：RBAC 与准入策略 `guard-probe-pod-scope` 改为同时接受 SA 与 OIDC 两个身份；工作流加 `id-token: write` 与模式开关 `GUARD_PROBE_AUTH`（缺省 `static`，与今天的行为逐字节相同）；OIDC kubeconfig 不含 exec 插件，用 `tokenFile`，配一个按需刷新的 kubectl 包装脚本。所有新脚本都先写离线 `.test.sh`（TDD）。

**Tech Stack:** k3s v1.35.0+k3s1（内置 etcd，3 server + 1 agent，Oracle Linux 9.5 aarch64，1 vCPU）；`audit.k8s.io/v1` Policy；`apiserver.config.k8s.io/v1` AuthenticationConfiguration（k8s 1.34 GA）；bash + jq + yq（mikefarah v4）+ python3（仅节点侧解析 etcd JSON）；GitHub Actions 托管 `ubuntu-latest`；kubectl v1.35.0。

**Spec（按此顺序读）：**
- `docs/adr/0057-k3s-apiserver-audit-and-probe-oidc.md`（本计划的全部设计决定都在这里，计划只写怎么做）
- `docs/adr/0054-guard-execution-point.md` §12 D9、§12.1、§12.2
- `docs/superpowers/plans/2026-11-15-guard-bypass-phase-c.md` §3（凭证风险表）、§5 U-K5 / U-K6 / U-K8、§6 O5
- k3s：`.github/workflows/guard-bypass-probe.yml`、`scripts/guard-bypass/{lib.sh,kubeconfig-for-probe.sh,token-expiry.sh,vap-check.sh,workflow.test.sh,manifests.test.sh}`、`apps/infrastructure/guard-admission/`、`apps/aster-lang/{agents,guard,tools}/rolebinding-probe*.yaml`、`docs/runbooks/guard-bypass-probe.md`、`.github/workflows/verify-gate-selftests.yml`

---

## 0. 现状

见 ADR 0057 §1（2026-11-16 只读核对）。对实施有直接影响的四条：

- `verify-gate-selftests.yml` 只跑 `scripts/{image-pin,guard-bypass}/*.test.sh`。`hosts/` 下的测试必须接进这个工作流，否则等于没有测试。
- `workflow.test.sh` 现在断言「全文无 `id-token`」，`manifests.test.sh` 断言每条 RoleBinding 的 subjects 恰好 1 项，并断言 VAP 的 `matchConditions` 字面值。这三条断言随本计划改写，改写方式是收紧到新的确切形状，不是放宽。
- 节点上的 `config.yaml` 含 server token。任何脚本、文档里的命令都不得 `cat` 它，只能按键名计数。
- 探针的 API 地址是 master-1 的公网 IP。

## Global Constraints

- **ADR 硬门**：ADR 0057 由用户确认（PROPOSED → ACCEPTED）之前，k3s PR 可以评审但不合并。任何节点操作（Task 7 起）都必须在 k3s PR 合并之后进行。
- **只读集群访问**：实施者只能执行 `KUBECONFIG=~/.kube/k3s-config kubectl get|describe`（不读 Secret），以及 Prometheus 只读查询；不 ssh，不改 GitHub 设置。节点命令、Environment 变量、secret 删除一律写成用户执行的步骤。
- **身份常量（全文逐字一致）**：SA `system:serviceaccount:aster-guard:guard-bypass-probe`；OIDC 用户 `github-actions:wontlost-ltd/k3s:guard-probe`；audience `k3s-guard-probe`；`job_workflow_ref` `wontlost-ltd/k3s/.github/workflows/guard-bypass-probe.yml@refs/heads/main`；`repository_id` `1116032527`；`repository_owner_id` `81835091`；`user.extra` 前缀 `aster-lang.dev/gha-`。唯一来源是 `scripts/guard-bypass/lib.sh`，`hosts/k3s-server/authn-config.test.sh` 断言其它文件与它一致。
- **主机路径**：drop-in 放在 `/etc/rancher/k3s/config.yaml.d/{50-aster-audit,60-aster-oidc}.yaml`；策略与配置放在 `/etc/rancher/k3s/aster/{audit-policy,authentication-config}.yaml`（root 0600，目录 0700）；日志写到 `/var/lib/rancher/k3s/server/logs/audit.log`（目录 0700）；备份放在 `/var/lib/rancher/aster-host-backup/<UTC 时间戳>/`。
- **静态路径不变**：`GUARD_PROBE_AUTH` 未设置或为 `static` 时，探针的步骤序列、命令与断言都与 `origin/main` 相同；Task 5 的测试逐条断言。
- **安全不变量**：审计策略对 `secrets` / `configmaps` / `serviceaccounts/token` / `tokenreviews` 的任何请求求值都只能得到 Metadata 或 None。OIDC 身份的 RBAC 与 SA 完全相同。VAP 对两个身份同等生效。
- **TDD**：每个脚本先写 `.test.sh` 并确认失败（FAIL），再实现到通过（PASS）。所有测试离线运行，依赖假 `kubectl` / `curl` / `systemctl` / `firewall-cmd` / `journalctl` / `df`。
- **工具链**：每次提交前，`bash` 依次跑全部 `scripts/{image-pin,guard-bypass}/*.test.sh` 与 `hosts/*/*.test.sh`；对新增与改动的脚本跑 `shellcheck`；对改动的工作流跑 `actionlint`。
- 注释、文档与提交信息用简体中文。提交末尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。PR 描述末尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，正文写 `Refs wontlost-ltd/aster-api#466`。

## Review Focus

1. **审计日志里出现凭证**：规则顺序错了，或者 `pods` 与 `pods/*` 的匹配语义理解错了，会让 TokenRequest 的响应或 Secret 的请求体进入日志。期望：策略求值器（`audit-policy.test.sh` 中的 jq 实现，与 k8s `policy/checker.go` 同语义：首条命中，`resource/subresource` 组合匹配，`*`、`*/sub`、`res/*` 通配，非资源 URL 的 `*` 后缀）对 4 类敏感资源 × 探针 / 管理员 / 系统组件 × 读 / 写的每个组合，结果都 ≤ Metadata。用例 `sensitive_never_above_metadata`。
2. **探针调用被噪声规则吞掉**：lease、watch 或系统组件的排除规则排在探针规则之前。期望：探针两个身份的 watch / list / get / 越权尝试都 ≥ Metadata，写入为 RequestResponse。用例 `probe_identities_always_logged`。
3. **OIDC 的信任面过宽**：只校验 `repository`，或漏掉 `job_workflow_ref` / `environment` / ID 类 claim；用户名可以由 claim 控制，或者映射了组。期望：`authn-config.test.sh` 断言 `claimValidationRules` 的表达式集合恰为 ADR 0057 §4.1 列出的 10 条，`claimMappings` 没有 `groups`，`userValidationRules` 含固定用户名。
4. **身份改了，准入没跟上**：OIDC 用户拿到 RBAC，VAP 却仍只匹配 SA，那么 OIDC 身份就能在 `aster-agents` 起任意形状的 Pod。期望：`manifests.test.sh` 断言 RBAC subject 集合与 VAP 的用户名列表相同，都等于 {SA, OIDC 用户}；`vap-check.sh` 对两个身份各跑一遍 P/D/R 用例。
5. **apiserver 起不来，整个集群失去控制面**：期望：preflight 要求 3 个端点就绪与 etcd 3 个成员都健康；每次只动一台；就绪超时或 verify 失败时自动回滚（`node.test.sh` 中的 `apply_rolls_back_when_not_ready`、`apply_rolls_back_when_verify_fails`）；`config.yaml` 中已有冲突的键就拒绝安装（`check_fails_on_conflicting_config_keys`）。
6. **静态路径被改坏**：模式开关的缺省值不是 `static`，或者非法值被当成 `static`。期望：`workflow.test.sh` 中的 `default_mode_is_static`、`unknown_mode_is_inconclusive`，并逐步核对 `if:` 条件。

---

## 1. 文件结构

| 仓 / 文件 | 职责 |
|---|---|
| k3s `hosts/k3s-server/files/etc/rancher/k3s/config.yaml.d/50-aster-audit.yaml` | 审计 7 项参数（`kube-apiserver-arg+:`） |
| k3s `hosts/k3s-server/files/etc/rancher/k3s/config.yaml.d/60-aster-oidc.yaml` | `authentication-config=` 参数 |
| k3s `hosts/k3s-server/files/etc/rancher/k3s/aster/audit-policy.yaml` | ADR 0057 §3 的规则表 |
| k3s `hosts/k3s-server/files/etc/rancher/k3s/aster/authentication-config.yaml` | ADR 0057 §4.1 |
| k3s `hosts/k3s-server/node.sh` | 节点侧：`check / status / preflight / apply / verify / rollback` |
| k3s `hosts/k3s-server/{audit-policy,authn-config,node}.test.sh` | 离线测试 |
| k3s `scripts/guard-bypass/lib.sh` | 身份常量 |
| k3s `scripts/guard-bypass/{oidc-kubeconfig,assert-identity}.sh`、`oidc.test.sh` | 取 token、生成 kubeconfig 与包装脚本、身份断言 |
| k3s `scripts/guard-bypass/k3s-server-ca.crt` | 集群 CA（公开证书） |
| k3s `.github/workflows/guard-bypass-probe.yml` | 模式开关、`id-token: write`、OIDC 步骤 |
| k3s `.github/workflows/verify-gate-selftests.yml` | 接入 `hosts/*/*.test.sh` |
| k3s `apps/aster-lang/{agents,guard,tools}/rolebinding-probe*.yaml`、`apps/infrastructure/guard-admission/{guard-bypass-probe-ns-read,guard-probe-pod-scope}.yaml` | 身份并集 |
| k3s `scripts/guard-bypass/{manifests,workflow}.test.sh`、`vap-check.sh` | 断言收紧到新形状，VAP 核对覆盖两个身份 |
| k3s `docs/runbooks/k3s-server-host-config.md`（新）、`docs/runbooks/guard-bypass-probe.md` | 节点操作手册；凭证迁移章节 |
| aster-api `docs/adr/0057-…`、`0054-…` §12.2、本计划 | 设计与指针 |

---

## 阶段 R：仓库（一个 k3s PR，实施者）

### Task 1：审计策略与求值器测试

**Files:**
- Create: `hosts/k3s-server/files/etc/rancher/k3s/aster/audit-policy.yaml`、`hosts/k3s-server/audit-policy.test.sh`

**Interfaces:**
- Produces: `audit_level <policy.json> <request.json>`，是测试内的 jq 函数。请求形状为 `{user, groups[], verb, resourceRequest: bool, apiGroup, resource, subresource, namespace, name, path}`，输出 `None|Metadata|Request|RequestResponse`。语义与 k8s `staging/src/k8s.io/apiserver/pkg/audit/policy/checker.go` 相同，没有规则命中时输出 `None`。
- 策略：ADR 0057 §3 的 12 条规则，加 `omitStages: [RequestReceived]`、`omitManagedFields: true`。

- [ ] Step 1：写测试（下列用例都要先确认 FAIL）：`sensitive_never_above_metadata`（4 类资源 × {SA, OIDC, admin, `system:kube-controller-manager`, 组 `system:nodes`} × {get, list, watch, create, update, delete}）；`probe_identities_always_logged`（两个身份 × {get, list, watch, create, delete} × {`pods`, `pods/log`, `leases`, `events`, `namespaces`}，写入为 RequestResponse，其余为 Metadata）；`lease_heartbeat_dropped`；`event_writes_dropped`；`system_reads_dropped`；`others_watch_dropped`；`health_and_discovery_dropped`；`agents_pod_writes_request_response`（admin 与任意 SA 的 create / delete pods，以及 `pods/ephemeralcontainers` 的 patch；对照：`aster-cloud` 的 pods create 为 Metadata，`aster-agents` 的 `pods/status` patch 为 Metadata）；`rbac_and_admission_writes_request_response`；`catch_all_metadata`；`evaluator_semantics`（`pods` 不匹配 `pods/log`；`*/status`；`res/*`；`/healthz*` 前缀匹配；`namespaces: [""]` 只匹配集群级资源）；以及形状：`apiVersion audit.k8s.io/v1`、`omitStages`、每条规则的 level 都合法、最后一条为无条件的 Metadata。
- [ ] Step 2：`bash hosts/k3s-server/audit-policy.test.sh` → FAIL（策略文件不存在）。
- [ ] Step 3：写策略 → PASS。
- [ ] Step 4：提交 `feat(hosts): kube-apiserver 审计策略与离线求值测试（aster-api ADR 0057 §3）`。

### Task 2：身份常量、认证配置与 RBAC / VAP 身份并集

**Files:**
- Create: `hosts/k3s-server/files/etc/rancher/k3s/aster/authentication-config.yaml`、`hosts/k3s-server/files/etc/rancher/k3s/config.yaml.d/{50-aster-audit,60-aster-oidc}.yaml`、`hosts/k3s-server/authn-config.test.sh`
- Modify: `scripts/guard-bypass/lib.sh`（新增常量）、四个 RoleBinding / ClusterRoleBinding、`guard-probe-pod-scope.yaml`、`manifests.test.sh`、`vap-check.sh`

**Interfaces:**
- `lib.sh` 新增：`PROBE_SA_USER`、`PROBE_OIDC_USER`、`PROBE_OIDC_AUDIENCE`、`PROBE_OIDC_ISSUER`、`PROBE_OIDC_REPOSITORY`、`PROBE_OIDC_REPOSITORY_ID`、`PROBE_OIDC_OWNER_ID`、`PROBE_OIDC_WORKFLOW_REF`、`PROBE_OIDC_ENVIRONMENT`、`PROBE_OIDC_SUB`。
- 绑定的 subjects 恰为 `[{kind: ServiceAccount, name: guard-bypass-probe, namespace: aster-guard}, {kind: User, apiGroup: rbac.authorization.k8s.io, name: <PROBE_OIDC_USER>}]`。
- VAP 的 `matchConditions[0].expression` 恰为 `request.userInfo.username in ['<SA>', '<OIDC>']`。
- `vap-check.sh`：先以 `--as=<SA>` 跑完所有探针用例，再以 `--as=<OIDC>` 跑一遍，编号加后缀 `@sa` / `@oidc`。

- [ ] Step 1：写 `authn-config.test.sh`：`apiVersion` 为 v1；恰好 1 个 JWT 发行方；issuer 与 audience 等于常量；不含 `anonymous`、`groups`、`discoveryURL`；`claimValidationRules` 的表达式集合恰为 10 条（逐字比较）；`username.expression` 逐字比较；`extra` 的键恰为 6 个且都以 `aster-lang.dev/gha-` 开头；`userValidationRules` 含 `system:` 前缀禁止与固定用户名；两个 drop-in 的键都是 `kube-apiserver-arg+`，取值恰为 ADR 0057 D6 / §4.1 所列；**跨文件一致性**：RBAC subject、VAP 列表、工作流期望的用户名与 audience、`audit-policy.yaml` 中的用户名都等于 `lib.sh` 常量。再改 `manifests.test.sh` 的三条断言。然后 → FAIL。
- [ ] Step 2：写配置、drop-in，改清单与 `vap-check.sh` → PASS（`manifests.test.sh`、`authn-config.test.sh`）。
- [ ] Step 3：提交 `feat(guard-bypass): GitHub OIDC 认证配置与探针身份并集（RBAC / VAP，ADR 0057 §4.1 D8）`。

### Task 3：节点脚本 `node.sh`

**Files:**
- Create: `hosts/k3s-server/node.sh`、`hosts/k3s-server/node.test.sh`

**Interfaces:**
- `node.sh check | status | preflight <c> | apply <c> [--no-restart] [--no-auto-rollback] | verify <c> [--since <epoch>] | rollback <c>`；`<c>` 是逗号列表，取值 `audit`、`oidc`。退出码：0 成功；1 核对失败，或 apply 失败且已回滚；2 用法错误；4 apply 失败且回滚也失败（需要人工处理）。
- 测试注入点：`NODE_ROOT`（文件系统根前缀，缺省为空）、`SYSTEMCTL`、`FIREWALL_CMD`、`JOURNALCTL`、`CURL`、`K3S_KUBECTL`、`DF`、`NODE_NAME`、`NODE_WAIT_TIMEOUT`（缺省 300）、`NODE_POLL`（缺省 5）、`NODE_BUNDLE`（缺省为脚本所在目录）。
- `check`：firewalld 运行中时，`trusted` 区的运行时与永久配置都必须没有 masquerade（`firewall-cmd --query-masquerade` 退出码 0 = 开、1 = 未开，其它退出码按查询失败判 fail；其它区有 masquerade 只 warn）；按组件输出 `installed / absent / drift`，drift 判 fail；`config.yaml` 及 `config.yaml.d/` 中非本组件文件里的 `audit-` / `oidc-` / `authentication-config` 计数为 0；本机有 `k3s.service`。
- `status`：本节点名；etcd 成员（经 `https://127.0.0.1:2379` grpc-gateway，使用 k3s 的 etcd 客户端证书）及 leader；`kubernetes` 端点 IP 数；已安装组件；审计日志目录占用。
- `preflight`：`check` 通过；端点为 3 个；etcd 成员为 3 个且都已启动，本地 `/health` 为 true；`/var/lib/rancher` 可用空间 ≥ 2 GiB（audit）；`$CURL` 取 GitHub 发现文档，`issuer` 必须为常量（oidc）。
- `apply`：preflight → 备份 → 安装 → 重启 → 等待（本地 `readyz` 返回 200、端点为 3 个、本节点 Ready）→ verify；失败则 rollback。
- `verify`：`k3s` active；本地 `readyz` 为 200；`--since` 之后的 journal 中，`Running kube-apiserver` 行带上了组件参数；audit：日志文件存在且非空，最后一行含 `"apiVersion":"audit.k8s.io/v1"`；oidc：`--since` 之后没有 `oidc authenticator` 错误行。
- `rollback`：删除组件文件，有备份则恢复，重启并等待就绪。

- [ ] Step 1：写测试（假二进制目录加临时根目录）→ FAIL：`check_passes_on_clean_host`、`check_fails_when_firewalld_query_errors`、`check_fails_on_trusted_masquerade_runtime`、`check_fails_on_trusted_masquerade_permanent`、`check_warns_other_zone_masquerade`、`check_fails_on_drift`、`check_fails_on_conflicting_config_keys`（断言输出不含假 `config.yaml` 中的 token 值）、`check_skips_firewall_when_inactive`、`preflight_fails_when_endpoints_lt_3`、`preflight_fails_when_etcd_member_unstarted`、`preflight_fails_low_disk`、`preflight_oidc_needs_github_discovery`、`apply_installs_files_0600_and_restarts`、`apply_no_restart_only_stages`、`apply_rolls_back_when_not_ready`、`apply_rolls_back_when_verify_fails`、`apply_refuses_on_preflight_failure`（不安装任何文件，不重启）、`rollback_removes_files_and_restarts`、`verify_audit_requires_flag_and_events`、`verify_oidc_fails_on_authenticator_error`、`usage_errors_exit_2`。
- [ ] Step 2：实现 → PASS；`shellcheck hosts/k3s-server/*.sh` 无输出。
- [ ] Step 3：提交 `feat(hosts): k3s server 主机配置安装 / 核对 / 回滚脚本（含 firewalld masquerade 守卫，ADR 0057 §5）`。

### Task 4：OIDC kubeconfig 与身份断言脚本

**Files:**
- Create: `scripts/guard-bypass/{oidc-kubeconfig,assert-identity}.sh`、`scripts/guard-bypass/oidc.test.sh`、`scripts/guard-bypass/k3s-server-ca.crt`

**Interfaces:**
- `oidc-kubeconfig.sh init --dir <d> --server <https://h:p> --ca <file>`：从 `$ACTIONS_ID_TOKEN_REQUEST_URL&audience=<aud>` 取 token（请求头 `Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN`，使用 `$CURL`），本地核对 `aud`、`iss`、`repository`、`environment`、`job_workflow_ref`、`ref` 与 `exp > now`。然后写入 `<d>/token`（0600）、`<d>/kubeconfig`（0600，`tokenFile: <d>/token`，CA 内嵌，不含 exec）、`<d>/bin/kubectl`（包装脚本，内置真实 kubectl 的绝对路径）。成功时 stdout 只输出 `<d>/kubeconfig`。退出码：0；2 参数错误；3 缺环境变量（「job 未授予 id-token: write」）、取 token 失败或 claim 不符。token 从不回显。
- `oidc-kubeconfig.sh refresh --dir <d>`：token 文件超过 `OIDC_MAX_AGE`（缺省 60 秒），或剩余有效期小于 `OIDC_MIN_REMAINING`（缺省 120 秒）时重取，原子替换（先写临时文件再 `mv`）。
- `assert-identity.sh <static|dual|oidc> [--oidc-dir <d>]`：行为见 ADR 0057 §4.2。退出码：0；2；3。

- [ ] Step 1：写 `oidc.test.sh`（假 curl 返回自造的 JWT，假 kubectl）→ FAIL：`init_writes_exec_free_kubeconfig_with_token_file`、`init_without_id_token_env_is_3`、`init_rejects_wrong_audience / wrong_workflow_ref / wrong_environment / expired`（都是 3，且不写 kubeconfig）、`init_never_prints_token`、`wrapper_refreshes_stale_token`、`wrapper_keeps_fresh_token`、`assert_static_requires_sa`、`assert_oidc_requires_oidc_user`、`assert_dual_checks_both_and_rbac`（can-i 为 yes / no；admin 身份判 3）、`assert_unknown_mode_is_2`。
- [ ] Step 2：实现 → PASS。用 `kubectl get cm kube-root-ca.crt -n aster-guard -o jsonpath='{.data.ca\.crt}'`（只读，非 Secret）写入 CA 文件，并核对其 SHA-256 指纹与 ADR 0057 §1 一致。
- [ ] Step 3：提交 `feat(guard-bypass): GitHub OIDC kubeconfig 与身份断言脚本（ADR 0057 §4.2）`。

### Task 5：工作流模式开关

**Files:**
- Modify: `.github/workflows/guard-bypass-probe.yml`、`scripts/guard-bypass/workflow.test.sh`、`.github/workflows/verify-gate-selftests.yml`

**Interfaces:**
- `probe` job 的 `permissions: {contents: read, id-token: write}`；job `env` 新增 `K3S_SERVER: https://158.179.18.241:6443`。
- 新增首步 `Select credential mode`：`env: {MODE: ${{ vars.GUARD_PROBE_AUTH }}}`，值为空时取 `static`，不属于 `static|dual|oidc` 时判 3，结果写入 `$GITHUB_ENV` 的 `GUARD_PROBE_AUTH`。
- `Write probe kubeconfig` 与 `Token expiry` 加 `if: env.GUARD_PROBE_AUTH != 'oidc'`。
- 新增 `Configure OIDC credential`，`if: env.GUARD_PROBE_AUTH != 'static'`：调用 `oidc-kubeconfig.sh init --dir "$RUNNER_TEMP/oidc" --server "$K3S_SERVER" --ca scripts/guard-bypass/k3s-server-ca.crt`。`oidc` 模式下再把 `KUBECONFIG` 写入 `$GITHUB_ENV`，把 `$RUNNER_TEMP/oidc/bin` 写入 `$GITHUB_PATH`。
- `Assert probe identity` 改为 `bash scripts/guard-bypass/assert-identity.sh "$GUARD_PROBE_AUTH" --oidc-dir "$RUNNER_TEMP/oidc"`。
- `Remove kubeconfig` 同时删除 `$RUNNER_TEMP/oidc`。
- `verify-gate-selftests.yml` 的 `tests=` 加上 `hosts/*/*.test.sh`。

- [ ] Step 1：改 `workflow.test.sh` → FAIL：`workflow_permissions_contents_read_only`；`only_probe_job_has_id_token_write`；`watchdog_has_no_id_token`；`default_mode_is_static`（用 yq 取出 `Select credential mode` 的 run，在 `MODE=` 时执行，输出 `static`）；`unknown_mode_is_inconclusive`（`MODE=admin` 时退出 3）；`static_path_steps_unchanged`（步骤名序列等于 origin/main 的序列加两个新步骤，静态步骤的 `run` 逐字不变）；`oidc_steps_gated`（各步的 `if:` 条件）；`secret_only_in_write_step`（保留）；`audience_and_user_match_lib`；`selftests_include_hosts`。
- [ ] Step 2：改工作流 → PASS；`actionlint` 无输出。
- [ ] Step 3：提交 `ci(guard-bypass): 探针凭证模式开关 static/dual/oidc（缺省 static，ADR 0057 §4.2）`。

### Task 6：操作手册

**Files:**
- Create: `docs/runbooks/k3s-server-host-config.md`
- Modify: `docs/runbooks/guard-bypass-probe.md`（§3 签发改为「静态路径（M4 前）」；新增「OIDC 迁移 M0–M5」；§6 审计改为查询方法；§5 失败表加 OIDC 三行）

- [ ] Step 1：手册写清：节点清单、分发命令、滚动顺序、每一步的期望输出、回滚、审计查询、磁盘与保留天数核对（份数上限先到，约 8–22 天）、firewalld 守卫（查询失败也判 fail）、维护窗口避开 03:17 UTC 且窗口内不手动触发探针（窗口内失败为预期，结束后重跑）。
- [ ] Step 2：全量测试，`shellcheck`，`actionlint`；开 PR。PR 描述含用户步骤 U-H1–U-H6（§3），以及各项验证的命令与输出。
- [ ] Step 3：提交 `docs(runbooks): k3s server 主机配置与探针 OIDC 迁移手册（ADR 0057）`；Codex 审查 ≥ 90。

## 阶段 H：节点与 GitHub（用户执行，Task 6 的 PR 合并之后）

### Task 7：审计（U-H1、U-H2）
- [ ] 按 `docs/runbooks/k3s-server-host-config.md` §3 对三台节点逐台执行 `node.sh apply audit`。每台完成后执行 Mac 侧核对，三台都完成后执行 `node.sh check`。窗口结束后手动触发一次探针，确认变绿。
- [ ] 24 小时后，在每台节点上执行 `node.sh status`，记录审计日志的实际速率，贴到 #466。

### Task 8：OIDC（U-H3–U-H5）
- [ ] `vap-check.sh` 对两个身份全部符合（M0）。
- [ ] 逐台执行 `node.sh apply oidc`（M1）。
- [ ] `gh variable set GUARD_PROBE_AUTH --env guard-probe --body dual -R wontlost-ltd/k3s`，手动触发一次运行，再等一次 schedule 运行（M2）。在任一台节点上用审计查询找到该运行的 `run_id`。
- [ ] 改为 `oidc`（M3），运行一次。
- [ ] 删除 secret，吊销 SA token（M4）。

### Task 9：收尾（实施者）
- [ ] 另起 k3s PR 删除静态路径（M5）：`kubeconfig-for-probe.sh`、`token-expiry.sh` 及其测试、模式开关；移除 RBAC 与 VAP 中的 SA 身份。
- [ ] aster-api docs PR：ADR 0057 改为 IMPLEMENTED；ADR 0054 §12 D9 与 §12.1 U-K8 改写为现状。

---

## 2. 任务依赖

| 顺序 | 项 | 谁 |
|---|---|---|
| 0 | 本 docs PR（ADR 0057 + 计划）合并，ADR 确认 | 用户 |
| 1 | k3s PR（Task 1–6，可以先于 0 评审） | 实施者 → 用户合并 |
| 2 | Task 7（审计，三台逐台） | 用户 |
| 3 | Task 8（OIDC，M0–M4） | 用户 |
| 4 | Task 9 | 实施者 |

## 3. 需要用户做的事

- **U-H1**：确认 ADR 0057（含 Q1–Q3 的缺省选项），合并 docs PR 与 k3s PR。
- **U-H2**：维护窗口内对三台 master 执行审计安装（Task 7）。
- **U-H3**：k3s PR 合并、Argo 同步后，执行 `bash scripts/guard-bypass/vap-check.sh --kubeconfig ~/.kube/k3s-config`，把输出贴到 k3s PR。
- **U-H4**：对三台 master 执行 OIDC 安装（Task 8 / M1）。
- **U-H5**：设置 Environment 变量 `GUARD_PROBE_AUTH`，依次为 `dual` → `oidc`（M2 / M3）。
- **U-H6**：删除 `GUARD_PROBE_KUBECONFIG` 并吊销 SA token（M4）。

## 4. 自审记录

- 规格覆盖：ADR 0057 D1–D6 → Task 1、Task 2 的 drop-in、Task 3 的 verify；D7 → Task 2；D8 → Task 2；D9 → Task 4、5；D10 → Task 3；D11 → Task 3 与手册；D12 → Task 8、9。
- 命名一致：身份常量只在 `lib.sh` 定义一次，`authn-config.test.sh` 做跨文件断言；`node.sh` 的子命令与手册逐字一致。
- 风险与测试一一对应：Review Focus 1–6 各自指向具体的测试用例。
