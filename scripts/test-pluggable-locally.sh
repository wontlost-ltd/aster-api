#!/usr/bin/env bash
# 本地 podman 测试：语言包热插拔 + FallbackLexicon 回退
#
# 验证目标（与 .claude/plan/pluggable-language-modules.md 对齐）：
#   1. 仅启用 en（拔除 zh、de）→ en 源码可执行；zh 源码报错（lexer 无法识别）
#   2. 启用 en+zh（拔除 de）→ zh 源码可执行
#   3. 启用 en+de（拔除 zh）→ de 源码可执行
#   4. 启用全部 → 三语言均可执行
#
# 每个场景都重启容器并探活 /q/health，确保 SPI 在新 classpath 下重新扫描。
#
# 前置条件：
#   - 已执行 ./gradlew build -x test （生成 build/quarkus-app/）
#   - 本机 podman 可用，已登录或可拉取 bitnami/postgresql:latest
#
# 用法：
#   ./scripts/test-pluggable-locally.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

IMAGE="aster/policy-api:pluggable"
PG_CONTAINER="aster-pluggable-pg"
REDIS_CONTAINER="aster-pluggable-redis"
APP_CONTAINER="aster-pluggable-app"
NETWORK="aster-pluggable-net"
APP_PORT="18080"   # 避免与本机 8080 冲突
# ADR 0040 §12 D1：执行收据签名密钥无默认值，缺失即启动失败。
# 仅测试/仅压测：固定种子 0x0d×32 的公开密钥，非生产，不得用于任何真实环境。
RECEIPT_KEYS_TEST_ONLY='{"revision":1,"keys":[{"kid":"perf-only","privateKey":"DQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0"}]}'

cleanup() {
  echo
  echo "[harness] cleanup ..."
  podman rm -f "$APP_CONTAINER"   >/dev/null 2>&1 || true
  podman rm -f "$PG_CONTAINER"    >/dev/null 2>&1 || true
  podman rm -f "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  podman network rm "$NETWORK"     >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------- 0. 前置检查 ----------
if [ ! -f "build/quarkus-app/quarkus-run.jar" ]; then
  echo "[harness] FATAL: build/quarkus-app/ not found. Run ./gradlew build -x test first." >&2
  exit 1
fi

if ! command -v podman >/dev/null 2>&1; then
  echo "[harness] FATAL: podman not in PATH" >&2
  exit 1
fi

# ---------- 1. 构建镜像 ----------
echo "[harness] building $IMAGE ..."
podman build -f Dockerfile.pluggable -t "$IMAGE" . >/dev/null

# ---------- 2. 网络 + Postgres ----------
podman network create "$NETWORK" >/dev/null 2>&1 || true

echo "[harness] starting postgres ..."
podman run -d --rm \
  --name "$PG_CONTAINER" \
  --network "$NETWORK" \
  -e POSTGRESQL_USERNAME=postgres \
  -e POSTGRESQL_PASSWORD=postgres \
  -e POSTGRESQL_DATABASE=aster_policy \
  docker.io/bitnami/postgresql:latest >/dev/null

# 等 pg 起来
for _ in $(seq 1 30); do
  if podman exec "$PG_CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "[harness] starting redis ..."
podman run -d --rm \
  --name "$REDIS_CONTAINER" \
  --network "$NETWORK" \
  --network-alias redis \
  docker.io/library/redis:7-alpine >/dev/null

# 给 redis 一两秒
for _ in $(seq 1 10); do
  if podman exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG; then
    break
  fi
  sleep 1
done

# ---------- 3. 测试源码 ----------
EN_SOURCE='Module test.hello.

Rule sayHello given name, produce:
  Return "Hello, " plus name.'

ZH_SOURCE='模块 测试.你好。

规则 打招呼 包含 名字，产出：
  返回 「你好，」 加 名字。'

DE_SOURCE='Modul test.hallo.

Regel begruessen gegeben name, liefert:
  gib zurueck "Hallo, " plus name.'

eval_source() {
  local source="$1"
  local locale="$2"
  curl -s -X POST "http://localhost:$APP_PORT/api/v1/policies/evaluate-source" \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: test-tenant" \
    -d "$(jq -nc \
      --arg s "$source" \
      --arg l "$locale" \
      '{source:$s, locale:$l, functionName:"sayHello", context:{name:"world"}}')"
}

# ---------- 4. 单场景执行器 ----------
start_app() {
  local enabled="$1"
  podman rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
  echo "[harness] starting app with ENABLED_LOCALES=$enabled ..."
  podman run -d --rm \
    --name "$APP_CONTAINER" \
    --network "$NETWORK" \
    -e ENABLED_LOCALES="$enabled" \
    -e QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://$PG_CONTAINER:5432/aster_policy" \
    -e QUARKUS_DATASOURCE_REACTIVE_URL="postgresql://$PG_CONTAINER:5432/aster_policy" \
    -e QUARKUS_DATASOURCE_USERNAME=postgres \
    -e QUARKUS_DATASOURCE_PASSWORD=postgres \
    -e QUARKUS_REDIS_HOSTS="redis://$REDIS_CONTAINER:6379" \
    -e QUARKUS_FLYWAY_MIGRATE_AT_START=true \
    -e ASTER_SECURITY_SIGNATURE_ENABLED=false \
    -e ASTER_SECURITY_RBAC_ENABLED=false \
    -e ASTER_SECURITY_EVENTS_ENABLED=false \
    -e ASTER_SECURITY_EVALUATE_SOURCE_PUBLIC=true \
    -e ASTER_GUARD_RECEIPT_SIGNING_KEYS="$RECEIPT_KEYS_TEST_ONLY" \
    -e ASTER_GUARD_RECEIPT_ACTIVE_KID=perf-only \
    -p "$APP_PORT:8080" \
    "$IMAGE" >/dev/null

  # readiness：调用真实业务端点（en 源码恒可解析），同时验证 HTTP 栈活了
  local probe_body
  probe_body="$(jq -nc \
    --arg s "$EN_SOURCE" \
    '{source:$s, locale:"en-US", functionName:"sayHello", context:{name:"probe"}}')"
  for i in $(seq 1 90); do
    if curl -fsS -o /dev/null \
         -X POST "http://localhost:$APP_PORT/api/v1/policies/evaluate-source" \
         -H "Content-Type: application/json" \
         -H "X-Tenant-Id: probe" \
         -d "$probe_body" 2>/dev/null; then
      echo "[harness] app ready after ${i}s"
      return 0
    fi
    sleep 1
  done
  echo "[harness] app did not become ready in 90s — dumping logs:" >&2
  podman logs "$APP_CONTAINER" | tail -80 >&2
  return 1
}

PASS=0
FAIL=0

assert_jar_present() {
  local locale="$1"
  local expected="$2"  # "yes" or "no"
  if podman exec "$APP_CONTAINER" sh -c "ls /work/lib/main/cloud.aster-lang.aster-lang-$locale-*.jar 2>/dev/null" >/dev/null 2>&1; then
    actual="yes"
  else
    actual="no"
  fi
  if [ "$actual" = "$expected" ]; then
    echo "  ✓ jar[$locale] present=$expected"
    PASS=$((PASS+1))
  else
    echo "  ✗ jar[$locale] expected=$expected actual=$actual" >&2
    FAIL=$((FAIL+1))
  fi
}

assert_eval_ok() {
  local label="$1"
  local source="$2"
  local locale="$3"
  local resp
  resp="$(eval_source "$source" "$locale")"
  if echo "$resp" | jq -e '.success == true' >/dev/null 2>&1; then
    echo "  ✓ evaluate[$label,$locale] success"
    PASS=$((PASS+1))
  else
    echo "  ✗ evaluate[$label,$locale] expected success, got: $resp" >&2
    FAIL=$((FAIL+1))
  fi
}

assert_eval_fails() {
  local label="$1"
  local source="$2"
  local locale="$3"
  local resp
  resp="$(eval_source "$source" "$locale")"
  if echo "$resp" | jq -e '.success != true' >/dev/null 2>&1; then
    echo "  ✓ evaluate[$label,$locale] correctly failed (lang pack absent)"
    PASS=$((PASS+1))
  else
    echo "  ✗ evaluate[$label,$locale] expected failure, got success" >&2
    FAIL=$((FAIL+1))
  fi
}

run_scenario() {
  local name="$1"
  local enabled="$2"
  local zh_expected="$3"   # success | fail
  local de_expected="$4"   # success | fail
  echo
  echo "==== Scenario: $name (ENABLED_LOCALES=$enabled) ===="
  start_app "$enabled" || return 1

  # 4a. 物理验证 jar 存在性符合期望
  case ",$enabled," in *",zh,"*) assert_jar_present zh yes ;; *) assert_jar_present zh no ;; esac
  case ",$enabled," in *",de,"*) assert_jar_present de yes ;; *) assert_jar_present de no ;; esac
  assert_jar_present en yes  # en 永远在

  # 4b. en 源码恒可执行
  assert_eval_ok "$name" "$EN_SOURCE" "en-US"

  # 4c. zh / de 按场景断言
  if [ "$zh_expected" = "success" ]; then
    assert_eval_ok "$name" "$ZH_SOURCE" "zh-CN"
  else
    assert_eval_fails "$name" "$ZH_SOURCE" "zh-CN"
  fi
  if [ "$de_expected" = "success" ]; then
    assert_eval_ok "$name" "$DE_SOURCE" "de-DE"
  else
    assert_eval_fails "$name" "$DE_SOURCE" "de-DE"
  fi
}

# ---------- 5. 跑四个场景 ----------
run_scenario "en-only"   "en"        fail    fail
run_scenario "en+zh"     "en,zh"     success fail
run_scenario "en+de"     "en,de"     fail    success
run_scenario "en+zh+de"  "en,zh,de"  success success

# ---------- 6. 汇总 ----------
echo
echo "================ SUMMARY ================"
echo "passed: $PASS"
echo "failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
