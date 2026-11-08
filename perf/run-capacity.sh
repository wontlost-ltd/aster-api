#!/usr/bin/env bash
#
# run-capacity.sh — 在「生产等价规格」下实测 aster-api 容量并定位瓶颈
#
# 生产规格（对齐 k3s deployment.yaml / hpa.yaml）：
#   - CPU limit  = 500m（0.5 核）
#   - JVM 堆      = -Xmx384m -Xms128m
#   - 内存 limit  = 512Mi
# 本脚本用 podman 的 --cpus / --memory + JAVA_OPTS 精确复现单个生产 pod，
# 从而量出「单 pod 满负荷吞吐」，再据此外推到 HPA 的 2–6 副本（见 CAPACITY.md）。
#
# 用法：
#   ./run-capacity.sh            # 起环境 + 跑三组容量测试 + 抓服务端指标
#   ./run-capacity.sh --keep     # 测完不拆环境（便于手动复查 /q/metrics）
#   CPU=1 HEAP=768 ./run-capacity.sh   # 覆盖规格（例如验证「CPU 翻倍吞吐是否线性」）
#
# 依赖：podman（docker 已 alias）、k6、已构建的 build/quarkus-app（gradlew build）
#
set -euo pipefail

# ---- 可覆盖的规格参数（默认=生产规格）----
CPU="${CPU:-0.5}"                # 容器 CPU 核数（生产 500m）
HEAP="${HEAP:-384}"             # JVM 最大堆 MB（生产 384）
MEM="${MEM:-512}"               # 容器内存 limit MB（生产 512Mi）
API_PORT="${API_PORT:-8080}"
PG_PORT="${PG_PORT:-55432}"     # 避开本机默认 5432
NET="aster-cap-net"
PG_NAME="aster-cap-pg"
API_NAME="aster-cap-api"
IMG="aster/policy-api:capacity"
# ADR 0040 §12 D1：执行收据签名密钥无默认值，缺失即启动失败。
# 仅测试/仅压测：固定种子 0x0d×32 的公开密钥，非生产，不得用于任何真实环境。
RECEIPT_KEYS_TEST_ONLY='{"revision":1,"keys":[{"kid":"perf-only","privateKey":"DQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0"}]}'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PERF="$ROOT/perf"
OUT="$PERF/capacity-results"
KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1

mkdir -p "$OUT"

log()  { printf '\033[1;34m[cap]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[cap]\033[0m %s\n' "$*"; }

cleanup() {
  [[ "$KEEP" == "1" ]] && { warn "--keep：保留容器 $API_NAME / $PG_NAME（记得手动清理）"; return; }
  log "清理容器与网络…"
  podman rm -f "$API_NAME" "$PG_NAME" >/dev/null 2>&1 || true
  podman network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---- 1. 构建 JVM 镜像（若缺）----
if ! podman image exists "$IMG"; then
  log "构建镜像 $IMG（Dockerfile.jvm）…"
  [[ -f "$ROOT/build/quarkus-app/quarkus-run.jar" ]] || {
    warn "未找到 build/quarkus-app —— 先跑：./gradlew build -x test"; exit 1;
  }
  podman build -t "$IMG" -f "$ROOT/Dockerfile.jvm" "$ROOT"
fi

# ---- 2. 网络 + Postgres ----
podman network exists "$NET" || podman network create "$NET" >/dev/null
log "启动 Postgres…"
podman rm -f "$PG_NAME" >/dev/null 2>&1 || true
podman run -d --name "$PG_NAME" --network "$NET" \
  -e POSTGRESQL_USERNAME=aster -e POSTGRESQL_PASSWORD=aster \
  -e POSTGRESQL_DATABASE=aster_cap \
  -p "$PG_PORT:5432" \
  docker.io/bitnami/postgresql:latest >/dev/null

log "等待 Postgres 就绪…"
for i in {1..30}; do
  podman exec "$PG_NAME" pg_isready -U aster >/dev/null 2>&1 && break
  sleep 2
  [[ $i == 30 ]] && { warn "Postgres 超时"; exit 1; }
done

# ---- 3. 启动 aster-api（生产规格约束 + 关限流用于 CPU/信号量测试）----
# 注意：CPU/信号量容量测试必须关限流，否则先撞 60/min 墙。
# 限流墙由 capacity-ratelimit.js 单独在开限流的容器上验证（见步骤 6）。
start_api() {
  local ratelimit="$1"  # true|false
  podman rm -f "$API_NAME" >/dev/null 2>&1 || true
  log "启动 aster-api  CPU=$CPU 核 · 堆=${HEAP}m · 内存=${MEM}m · 限流=$ratelimit"
  podman run -d --name "$API_NAME" --network "$NET" \
    --cpus="$CPU" --memory="${MEM}m" \
    -e JAVA_OPTS="-Xmx${HEAP}m -Xms128m -XX:+UseG1GC -XX:MaxGCPauseMillis=100" \
    -e QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://$PG_NAME:5432/aster_cap" \
    -e QUARKUS_DATASOURCE_REACTIVE_URL="postgresql://$PG_NAME:5432/aster_cap" \
    -e QUARKUS_DATASOURCE_USERNAME=aster \
    -e QUARKUS_DATASOURCE_PASSWORD=aster \
    -e QUARKUS_FLYWAY_MIGRATE_AT_START=true \
    -e ASTER_SECURITY_APIKEY_ENABLED=false \
    -e ASTER_SECURITY_SIGNATURE_ENABLED=false \
    -e ASTER_RATELIMIT_ENABLED="$ratelimit" \
    -e ASTER_GUARD_RECEIPT_SIGNING_KEYS="$RECEIPT_KEYS_TEST_ONLY" \
    -e ASTER_GUARD_RECEIPT_ACTIVE_KID=perf-only \
    -e QUARKUS_REDIS_HOSTS="redis://localhost:6379" \
    -p "$API_PORT:8080" \
    "$IMG" >/dev/null

  log "等待 aster-api 健康…"
  for i in {1..40}; do
    curl -fs "http://localhost:$API_PORT/q/health" >/dev/null 2>&1 && { log "aster-api 就绪"; return 0; }
    sleep 3
    [[ $i == 40 ]] && { warn "aster-api 健康检查超时；日志："; podman logs --tail 40 "$API_NAME"; exit 1; }
  done
}

seed_policy() {
  log "预置 perf 策略 loadtest.zero.compute…"
  podman exec -e PGPASSWORD=aster "$PG_NAME" psql -h localhost -U aster -d aster_cap <<'SQL' >/dev/null
INSERT INTO policy_versions (
  policy_id, version, module_name, function_name, content, active,
  created_at, source_hash, tenant_id, status, is_default
) VALUES (
  'perf-zero', 1, 'loadtest.zero', 'compute',
  E'Module loadtest.zero.\n\nRule compute, produce Int:\n  Return 42.',
  true, NOW(),
  'zerohash00000000000000000000000000000000000000000000000000000000',
  'perf-tenant', 'APPROVED', true
) ON CONFLICT DO NOTHING;

INSERT INTO policy_catalog (
  id, tenant_id, module_name, function_name, default_version_id, created_at, updated_at
)
SELECT gen_random_uuid(), 'perf-tenant', 'loadtest.zero', 'compute',
       (SELECT id FROM policy_versions WHERE module_name='loadtest.zero' LIMIT 1),
       NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM policy_catalog WHERE tenant_id='perf-tenant' AND module_name='loadtest.zero'
);
SQL
}

# 后台采样服务端资源（CPU/内存），与 k6 客户端指标交叉印证瓶颈类型。
sample_stats() {
  local label="$1" secs="$2"
  local f="$OUT/${label}-podman-stats.csv"
  echo "ts,cpu_pct,mem_used,mem_limit,mem_pct" > "$f"
  local end=$((SECONDS + secs))
  while [[ $SECONDS -lt $end ]]; do
    local line
    line=$(podman stats --no-stream --format "{{.CPU}},{{.MemUsage}},{{.MemPerc}}" "$API_NAME" 2>/dev/null | tr -d ' ' || true)
    [[ -n "$line" ]] && echo "$(date +%s),$line" >> "$f"
    sleep 2
  done
  log "服务端资源采样 → $f"
}

run_k6() {
  local script="$1" label="$2" maxrps="${3:-}"
  log "▶ 跑 $script（结果 → $OUT/$label.txt）"
  local dur; dur=$(k6 inspect "$PERF/$script" >/dev/null 2>&1 && echo 400 || echo 300)
  sample_stats "$label" "$dur" &
  local spid=$!
  ( cd "$PERF" && API_BASE="http://localhost:$API_PORT" TENANT_ID=perf-tenant \
      ${maxrps:+MAX_RPS=$maxrps} k6 run "$script" | tee "$OUT/$label.txt" ) || true
  wait "$spid" 2>/dev/null || true
}

# ===================== 执行三组测试 =====================

# 组 1 & 2：关限流，压 CPU（/evaluate）与信号量（/evaluate-source）
start_api "false"
seed_policy
run_k6 "capacity-evaluate.js"        "evaluate"        "${EVAL_MAX_RPS:-2000}"
run_k6 "capacity-evaluate-source.js" "evaluate-source" "${SRC_MAX_RPS:-250}"

# 组 3：开限流，验证 60/min/pod 墙
start_api "true"
seed_policy
run_k6 "capacity-ratelimit.js" "ratelimit"

log "全部完成。汇总结果目录：$OUT"
log "关键产物："
log "  - evaluate.txt / evaluate-capacity-summary.json          → /evaluate CPU 饱和点"
log "  - evaluate-source.txt / evaluate-source-capacity-summary.json → 信号量 cap=2 墙"
log "  - ratelimit.txt / ratelimit-capacity-summary.json         → 60/min 限流墙"
log "  - *-podman-stats.csv                                      → 服务端 CPU/内存，用于确证瓶颈类型"
log ""
log "外推到全系统：单 pod 饱和 RPS × 有效 pod 数(HPA 2–6) = 全系统吞吐上限。"
log "  /evaluate 与 /evaluate-source 近似线性可加（瓶颈是 per-pod CPU/信号量）；"
log "  但注意共享单实例 Postgres 会在写路径/冷路径成为非线性上限（见 CAPACITY.md）。"
