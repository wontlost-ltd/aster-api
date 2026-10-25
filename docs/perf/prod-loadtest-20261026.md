# 生产环境容量压测报告与扩展方案（2026-10-26）

> 范围：aster-api 生产部署（k3s，经 Cloudflare Tunnel 对外），同时涉及 Traefik、cloudflared、shared-postgres、shared-redis 与 aster-cloud（Cloudflare Workers）侧的 rate-check / 用量上报。
> 结论先行：**当前生产的瓶颈不在策略引擎本身，而在基础设施配置与请求路径上的同步外呼**。引擎在 2 CPU 单 Pod 的本地基准里可达 4137 RPS（见 `k8s-bench-results.txt`），生产实测稳定上限只有约 50 RPS，差距约 80 倍。

---

## 1. 测试环境与方法

### 1.1 生产拓扑与资源（压测时快照）

| 组件 | 规格 | 备注 |
|---|---|---|
| 集群节点 | 4 台（master-1/2/3 + worker-1），**每台 1 vCPU、约 5.5 GiB 内存** | 3 台 master 同时运行 etcd（k3s 内嵌）；压测前节点内存占用 65%–78% |
| aster-api | 2 副本（HPA 2–6），requests `cpu 100m / mem 512Mi`，limits `cpu 500m / mem 512Mi`，`-Xmx384m` | HPA 目标：CPU 70%、内存 80% |
| aster-api 关键参数 | REST 限流 60 次/分钟/租户或 IP；JDBC 池 8；worker 线程 32；`/evaluate-source` 许可 = min(2×CPU, 堆/64MB) ≈ 2 | 套餐级 per-key RPS 由 cloud rate-check 控制：Trial/Pro 10、Team 50、Enterprise 200 |
| Traefik | **1 副本，未设 requests/limits（BestEffort）** | 所有集群入口流量的唯一入口 |
| cloudflared | **1 副本，limits `cpu 100m`** | Cloudflare Tunnel 唯一出站连接器 |
| PostgreSQL | CNPG `shared-postgres`，**单实例**，`max_connections=100` | 与 authentik、grafana 等共享 |
| Redis | `shared-redis` 单主 | |
| 真实流量基线 | 当前约 0.02 RPS；近 7 天 5 分钟窗口峰值 **27.6 RPS** | 流量 85% 是匿名读接口（`/api/v1/messages*`、`/api/v1/lexicons`） |

### 1.2 方法

- 工具：k6（开放模型 `ramping-arrival-rate`，按到达率阶梯加压，找吞吐拐点），从公网经 Cloudflare 全链路访问 `policy.aster-lang.dev`。
- 自动止损：`http_req_failed > 2%` 或 `p99 > 2s` 即 `abortOnFail`；外部看门狗每 10 秒采样，节点内存 ≥92%、PG 连接 ≥90 或目标命名空间 Pod 重启即中断压测。
- 压测窗口内通过 k3s#893 临时把 `ASTER_RATELIMIT_REST_MAX` 调到 1000000，结束后由 k3s#894 回滚（已验证恢复 60/分钟：60×200 后 429）。
- 策略执行场景使用 5 个临时 Enterprise 租户（各 1 个 API key、1 条已批准策略），避免单租户审计链 advisory lock 串行化造成的人为瓶颈；压测后租户、key、策略、14,778 条用量记录、10 条异常报告均已删除。审计日志（append-only 触发器保护，按租户分区的独立链）保留未删。
- 负载机位于新西兰，到 Cloudflare 边缘的网络往返约 70ms，计入客户端延迟。

---

## 2. 压测结果

### 2.1 策略执行 `POST /api/v1/policies/evaluate`（API key 鉴权）

| 轮次 | 条件 | 结果 | 结论 |
|---|---|---|---|
| R1 | 新副本冷启动，10 RPS | 前 5 秒 59 个请求中 **12 个 500**（每个耗时约 2.8s），随后稳定 | 冷启动并发首次编译失败 → aster-api#392 |
| R2 | 2 副本预热，10 → 25 RPS | 25 RPS 稳定：avg ≈230ms、0 错误；约 25 RPS 时 HPA 直接扩到 6 副本，4 个新 JVM 同时启动（每个约 0.4–0.5 核）抢占 1 vCPU 节点，吞吐一度跌到 3.3 RPS、avg 2.6s | HPA 以 100m requests 为基准过于敏感，扩容风暴反而导致雪崩 |
| R3 | 6 副本预热，25 → 40 → 50 RPS | 40 RPS：avg ≈200ms、0 错误；50 RPS 后 avg 升至 649ms，累计 p99 2.55s 触发止损 | — |
| R4 | 6 副本预热，**固定 50 RPS × 75 秒** | 3,749 请求、**0 错误、avg 205ms、p95 357ms、p99 699ms** | **稳定上限 ≈50 RPS** |
| R5 | 6 副本预热，固定 75 RPS | 20 秒内 p99 2.43s、p90 1.39s，止损 | 拐点在 50–75 RPS 之间 |

服务端视角（Prometheus `http_server_requests_seconds`）：50–75 RPS 时 `/evaluate` 服务端平均耗时仅 **118–161ms**，aster-api 每个 Pod CPU 仅 0.1–0.14 核，CFS 节流比例 <5%。也就是说**延迟主要不在策略执行**：

- 本地基准中纯执行 <4ms；服务端 118ms 中绝大部分是**每请求同步外呼 cloud**（`/api/internal/api/rate-check` 套餐限速检查，经 Cloudflare 往返）与配额快照回源；
- `checkRate` 在 event-loop 上把外呼丢进仅 4 线程的 `precheckPool` 并 `f.get(1s)` 阻塞等待，event-loop 被占住时新请求在进入计时器之前就已排队——这部分时间不出现在服务端指标里，只体现为客户端延迟；
- 配额快照在 event-loop 上调用阻塞 Redis，每请求都抛 `The current thread cannot be blocked` 并降级（aster-api#393），本地快照热路径从不命中、月度计数器从不递增（200% 硬上限因此失效）。

压测期间 PostgreSQL 基本空闲（`aster_api` 库仅偶见 1 个 `pg_advisory_xact_lock` 等待），确认 DB 不是此档位的瓶颈。

### 2.2 匿名读路径（`/api/v1/lexicons`、`/api/v1/messages-manifest`、`/api/v1/messages/en-US`）

| 到达率 | 结果 |
|---|---|
| 75 RPS | avg ≈95ms，0 错误 |
| 150 RPS | avg 100–130ms，0 错误 |
| 冲向 300 RPS | 吞吐塌到 22 RPS、avg 4.6s，出现 **502**（cloudflared：`dial tcp <traefik ClusterIP>:443: connect: connection refused`） |

根因：**Traefik 单副本且无 CPU requests（BestEffort）**，与 aster-api、Redis 同挤在 1 vCPU 的 master-3 上；高负载下其 readiness 探针 `/ping` 超时被摘除 Endpoints，cloudflared 直接被拒连。Traefik 每 10 秒还因多个 Ingress 引用不存在的 TLS Secret 重复报错并重载配置，额外消耗 CPU。cloudflared 在 150 RPS 时 CPU 峰值 0.055 核（limit 0.1 核）、节流 9.5%，且所有请求都落在同一条 tunnel 连接（`connIndex=2`）上，是紧随 Traefik 之后的第二道天花板（估算约 250–300 RPS 饱和）。

### 2.3 压测引发的控制面事故（重要）

R3–R5 之后，3 台 master 上的 **etcd 就绪检查失败持续 20 分钟以上**（`/readyz`：`[-]etcd failed`），kube-apiserver 间歇不可达、master-3 kubelet exec/logs 返回 502；数据面（站点与 API）全程 200。人工在 master 节点处置后恢复。原因判断：etcd 与业务负载共用 1 vCPU 节点，HPA 扩容风暴 + Traefik/PG 压力导致 etcd 心跳与落盘延迟超标。**在控制面独立或扩容之前，禁止再对本集群做超过 30 RPS 的压测。**

### 2.4 压测中发现的缺陷

| 编号 | 问题 | 影响 |
|---|---|---|
| aster-api#391（修复 PR #395） | ui-messages 启动时 Redis 读取超时后永不补读，`/api/v1/messages/{locale}` 在该副本 404；每次冷启动都复现。根因：classpath 基础文案实际存在，Redis 读错误被当作「无文案」导致内存为空 | 两副本返回不一致；cloud 侧有三级回退，用户界面基本不受影响 |
| aster-api#392（修复 PR #398） | 冷缓存下多个版本并发首次评估，`compileOnce` 事务提交失败 → 500。本地未能复现原始 500；修复消除结构性诱因（编译移出事务、持连接时间从秒级降到毫秒级）并加就绪前预热 | 每次发版/扩容/重启的冷启动窗口内客户可见错误 |
| aster-api#393（修复 PR #397） | 配额快照在 event-loop 上调用阻塞 Redis；`checkRate` 在 event-loop 上同步等待外呼 | 本地计数器从不递增、200% 硬上限失效；evaluate 吞吐被限制在约 50 RPS |
| 执行时限缺口（修复 PR #396） | `DynamicCnlExecutor`（`/evaluate-source`、`/evaluate-json`、What-If）缺少墙钟上限；Aster 语言图灵完备，深递归/指数分支策略可长时间占用执行许可与 CPU | 匿名试用端点可被少量请求长时间占满；修复后统一 5s 墙钟上限、超时 422 `execution_timeout` 并及时归还许可 |
| API key 快照失效 | 直接删除 key/用户的数据库行后，Redis `aq:apikey:*` 快照（TTL 1h）仍可让该 key 通过鉴权最长 1 小时 | 仅在绕过正常撤销流程（如直接删库、账户清除）时出现 |
| HPA 配置 | requests 100m 使 70% CPU 目标形同虚设；80% 内存目标对 JVM（堆不回缩）意味着**永不缩容**（压测后停在 6 副本，`ceil(6×69/80)=6`） | 资源浪费、扩容风暴 |

---

## 3. 最大并发用户数估算

依据 Little 定律：并发用户数 N = 吞吐 λ ÷ 单用户请求速率 r。以下 r 为假设值，按实际用户行为可等比例换算。

| 用户类型 | 单用户请求模型（假设） | 受限资源 | 当前上限 |
|---|---|---|---|
| API 集成客户端（持续调用） | 每客户端 1 次评估/秒 | evaluate 50 RPS | **约 50 个并发客户端** |
| API 集成客户端（低频） | 每 10 秒 1 次评估 | evaluate 50 RPS | 约 500 个 |
| Web 控制台浏览用户 | 每 20 秒浏览一页，每页约 3 次匿名读 → 0.15 RPS | 匿名读 150 RPS | **约 1,000 个并发在线用户** |
| Web 编辑/试运行用户 | 每 30 秒执行一次策略 → 0.033 RPS（另有浏览读请求） | evaluate 50 RPS 与匿名读 150 RPS 先到者 | 约 1,000–1,500 个 |

对照真实流量：近 7 天峰值 27.6 RPS，**当前余量约 2 倍（evaluate）到 5 倍（匿名读）**；在 2 副本默认形态下 evaluate 仅约 25 RPS，与历史峰值几乎持平。

---

## 4. 扩展方案（按优先级）

### P0 — 立即执行：配置修正（无需改代码，预计 evaluate → 约 150 RPS、匿名读 → 约 500 RPS）

1. **控制面隔离/扩容（最高优先级，先于任何压测）**
   - 方案 A：master 节点升至 ≥2 vCPU，给 etcd 留出余量；
   - 方案 B：为 master 加 `NoSchedule` taint（仅系统组件），业务负载全部调度到 worker；需同时扩充 worker（见 P1）。
   - 为 etcd 所在磁盘确认低延迟存储（etcd 对 fsync 延迟敏感）。
2. **Traefik 高可用**：副本数 ≥2，`requests cpu 200m / mem 128Mi`，按节点反亲和打散，加 PDB（`minAvailable: 1`）；清理引用不存在 TLS Secret 的 Ingress（`wontlost-com-tls`、`authentik-outpost-tls`），消除每 10 秒的重复重载。
3. **cloudflared 高可用**：副本数 ≥2（每副本 4 条 tunnel 连接，提升并行与容错），`limits cpu 500m`、`requests cpu 100m`。
4. **aster-api HPA 与资源**：
   - `requests cpu` 调到约 400m（贴近真实稳态用量），`limits cpu` 1000m；
   - 移除内存指标或将其提高到 90%（JVM 堆不回缩，内存不适合作为扩缩依据）；
   - `behavior.scaleUp`：每 60 秒最多新增 1 个 Pod，`stabilizationWindowSeconds: 60`，避免扩容风暴；
   - `minReplicas` 保持 2，`maxReplicas` 按节点容量设定。
5. **API 限流参数**：60 次/分钟/租户对付费 API 客户偏低（Pro 套餐 10 RPS 的套餐级限速与之冲突），按套餐分档或改由 cloud rate-check 统一负责。

### P1 — 近期：代码修复（解除 evaluate 的 50 RPS 天花板）

6. **aster-api#393**：配额快照改为 Reactive Redis；`checkRate` 改为非阻塞（异步 HTTP + 超时 fail-open，或本地令牌桶按周期与 cloud 同步）。预期：服务端每请求耗时从约 120ms 降到个位数毫秒，单 Pod evaluate 吞吐提升一个数量级，并恢复 200% 配额硬上限。
7. **aster-api#392**：编译移出事务（事务内只读 DB），并在就绪前预热活跃策略的编译缓存，消除发版/扩容后的 500。
8. **aster-api#391**：ui-messages 启动失败 locale 按退避补读，就绪/指标可观测。
9. **执行时限**：`DynamicCnlExecutor` 加与 `/evaluate` 一致的墙钟看门狗并及时归还许可；What-If 超时真正取消执行。后续可考虑在 Truffle 调用节点加调用次数燃料，与 TS 解释器的步数/深度上限对齐。
10. **用量上报批量化**：`postUsage` 每请求一次异步外呼改为本地聚合、按秒批量上报，降低 cloud 与 Workers 调用量。

### P2 — 中期：容量与数据层

11. **节点扩容**：当前 4×1 vCPU、内存已用 72%–82%，没有任何扩容余量。建议至少 3 台 worker × 2–4 vCPU；PostgreSQL 独立节点。
12. **PostgreSQL**：引入 PgBouncer（事务模式）收敛连接；CNPG 增加 1 个同步副本（`instances: 2`）提供故障切换；读多写少的查询走只读副本。`max_connections=100` 与 authentik/grafana 共享，需按业务拆分或扩大。
13. **Redis**：增加副本与哨兵，或迁移托管 Redis；Redis 现在承担限流、快照、广播三类职责，单点故障面过大。
14. **可观测性**：为上述瓶颈加告警——Traefik readiness 失败、cloudflared CPU 节流率、aster-api event-loop 阻塞、本地配额快照命中率、`aster_redis_broadcast_up == 0`、etcd `wal_fsync` 与 leader 变更。

### P3 — 长期：架构

15. **匿名读接口下沉 CDN**：`/api/v1/messages*`、`/api/v1/lexicons`、`messages-manifest` 为近静态内容（占真实流量 85%），设置 `Cache-Control: public, max-age, stale-while-revalidate` 并配 Cloudflare 缓存规则，按版本号/sha 失效；源站读流量可下降 90% 以上。
16. **限速与鉴权下沉边缘**：rate-check 在 Cloudflare Workers + Durable Objects 完成，aster-api 只信任边缘签名头；彻底去掉每请求从源站回调 cloud 的往返。
17. **审计链写入异步化**：同租户审计链追加目前受 per-tenant advisory lock 串行化，单租户高吞吐场景会成为上限；改为 outbox + 批量追加，链式哈希在批内计算。
18. **多区域/水平扩展**：aster-api 为无状态服务，修完 P1 后可线性水平扩展；按客户地域部署多套数据面，控制面与数据库单独规划。

### 预期效果（估算，需在 P0/P1 落地后复测验证）

| 阶段 | evaluate 稳定上限 | 匿名读稳定上限 | 主要依据 |
|---|---|---|---|
| 当前 | ≈50 RPS（2 副本 ≈25） | ≈150 RPS | 本次实测 |
| P0 完成 | ≈150 RPS | ≈500 RPS | 去除 Traefik/cloudflared 单点与 HPA 风暴 |
| P0+P1 完成 | ≈500–1,000 RPS | ≈500 RPS | 去除每请求同步外呼；参照本地基准按 0.5–1 核折算 |
| P0–P3 完成 | 数千 RPS（随副本线性） | 由 CDN 承担，源站几乎无压力 | 架构下沉边缘 |

---

## 5. 复测建议

- 在 P0 完成、控制面独立之后再复测，起步 ≤30 RPS，逐级加压，同时监控 etcd 健康（`/readyz?verbose`）。
- 复测前先预热（每个租户 × 每个副本顺序请求一次），并在报告中区分冷启动与稳态。
- 压测租户使用一次性 Enterprise 账户，结束后删除租户、key、策略与用量记录；API key 快照需等待 1 小时 TTL 或经正常撤销流程失效。
