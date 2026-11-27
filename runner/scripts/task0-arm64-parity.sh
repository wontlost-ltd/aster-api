#!/usr/bin/env bash
# Task 0 风险门：真构建 arm64 runner 镜像，真跑固定 corpus，
# 与 aster-api 权威对照 byte-identical（★排除 toolchainId，只比 canonical* + replayabilityStatus + traceHash）。
#
# ★对照语义（诚实边界）：
#   expected = aster-api（host/生产 REST 路径）的 ReplayMetadata（由 gen-expected.sh 预生成）；
#   candidate = runner（arm64 容器/stock Zulu JRE + Truffle 解释器）的 envelope.replayMetadata。
#   Task 0 证的是「runner 打包+容器+arm64+stock-JRE 环境相对 aster-api 有无分叉」——含 runner 复用
#   :replay 是否在这些环境下仍产同结果。★不是算法独立性（那要 TS 二引擎，spec 已定非本 MVP）。
#   toolchainId 天然不同（runner build= 与 aster-api 不同），故 byte-compare 显式排除该字段。
#
# 退出 0 = 门通过（stock JRE+Truffle 真跑 + byte-identical）；
# 非 0 = 门失败（stock JRE 跑不起 Truffle / arm64 构建失败 / 有分叉）→ 回头改 A/B（可能须 GraalVM JDK 基镜像）。
#
# ── 参数化（Slice-2 D-2 CI 复用同一比对逻辑）──────────────────────────────────
# 默认（无参数）= Task 0 开发者本地手跑：podman 本地 build arm64 镜像再比对（与 Slice-1 byte-identical）。
# `--no-build`  跳过 installDist + build，直接用外部 $IMAGE（CI 已 push+签的 @DIGEST）比对，不本地构建。
# 环境变量：
#   RUNTIME  容器运行时（podman|docker），默认 podman（本地手跑）；CI 传 docker。
#   IMAGE    镜像引用，默认 runner:task0（本地 build tag）；
#            --no-build 时须传外部镜像（如 wontlost/aster-replay-runner@sha256:...）。
# 用法：
#   本地手跑（不变）:   bash task0-arm64-parity.sh
#   CI（对 push 的 digest）: IMAGE="wontlost/aster-replay-runner@${DIGEST}" RUNTIME=docker \
#                            bash task0-arm64-parity.sh --no-build
set -euo pipefail

cd "$(dirname "$0")/.."   # runner/
ROOT="$(cd ../ && pwd)"   # aster-api/
CORPUS_DIR="src/test/resources/parity-corpus"

# ── 参数解析：--no-build 关本地构建，用外部 $IMAGE。默认保持 Slice-1 本地行为不变。──
BUILD=1
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        *) echo "未知参数：${arg}（仅支持 --no-build）"; exit 2 ;;
    esac
done

# RUNTIME/IMAGE 默认值＝Slice-1 本地手跑行为（byte-identical）；CI 用 env 覆盖。
RUNTIME="${RUNTIME:-podman}"
IMAGE="${IMAGE:-runner:task0}"

command -v "$RUNTIME" >/dev/null 2>&1 || { echo "❌ 容器运行时不可用：$RUNTIME"; exit 2; }

# 前置：expected.json 必须已由 gen-expected.sh（aster-api 权威路径）产出。
missing_expected=0
for req in "$CORPUS_DIR"/*.req.json; do
    name="$(basename "$req" .req.json)"
    if [ ! -f "$CORPUS_DIR/$name.expected.json" ]; then
        echo "❌ 缺 $name.expected.json —— 先跑 scripts/gen-expected.sh 产权威基线"
        missing_expected=1
    fi
done
[ "$missing_expected" -eq 0 ] || { echo "== Task 0 门前置失败：expected.json 未生成 =="; exit 2; }

if [ "$BUILD" -eq 1 ]; then
    echo "== 1. installDist（产 build/install/runner，供 Dockerfile COPY）=="
    (cd "$ROOT" && ./gradlew :runner:installDist -q)

    echo "== 2. build arm64 image（真 arm64，arch 断言在 Dockerfile 内 fail-closed）=="
    "$RUNTIME" build --platform linux/arm64 -t "$IMAGE" .
else
    echo "== 1-2. --no-build：跳过本地构建，直接用外部镜像 ${IMAGE}（${RUNTIME}）=="
fi

# 毫秒时间戳：bash ≥5 用 EPOCHREALTIME；macOS 自带 bash 3.2 与 BSD date 不支持毫秒，退回秒级。
now_ms() {
    if [ -n "${EPOCHREALTIME:-}" ]; then
        local us="${EPOCHREALTIME/[.,]/}"
        echo $(( us / 1000 ))
    else
        echo $(( $(date +%s) * 1000 ))
    fi
}

echo "== 3. 真跑 corpus，逐个 byte-compare（容器内 stock Zulu JRE 25 + Truffle 解释器）=="
# 只比 5 个 replay-critical 字段（排除 toolchainId）：canonicalInputHash/canonicalOutputHash/
# canonicalizationVersion/replayabilityStatus/traceHash。jq -S 归一键序，逐字节相等才算 parity。
FIELDS='{canonicalInputHash, canonicalOutputHash, canonicalizationVersion, replayabilityStatus, traceHash}'
fail=0
for req in "$CORPUS_DIR"/*.req.json; do
    name="$(basename "$req" .req.json)"

    # runner 真跑：容器内 stdin JSON → stdout envelope（最后一行完整 JSON）。
    # runner 非 SUCCESS 时 exit≠0；`|| true` 防 set -e + pipefail 在此直接退出，吞掉下面的错误 envelope 诊断。
    # 逐份记录容器墙钟（含 JVM 冷启动），供判断 runner 慢与真实回归（#617）。
    started_ms="$(now_ms)"
    runner_out="$("$RUNTIME" run --rm -i --platform linux/arm64 "$IMAGE" < "$req" | tail -n 1)" || true
    elapsed_ms=$(( $(now_ms) - started_ms ))
    echo "⏱ ${name}: 容器墙钟 ${elapsed_ms}ms（含 JVM 冷启动）"
    runner_outcome="$(echo "$runner_out" | jq -r '.outcome // "MISSING"')"
    if [ "$runner_outcome" != "SUCCESS" ]; then
        echo "❌ RUNNER ERROR: ${name}（容器内非 SUCCESS，stock-JRE/Truffle 可能跑不起）"
        echo "$runner_out" | jq '.' || echo "$runner_out"
        fail=1
        continue
    fi

    runner_norm="$(echo "$runner_out" | jq -S ".replayMetadata | $FIELDS")"
    expected_norm="$(jq -S "$FIELDS" "$CORPUS_DIR/$name.expected.json")"

    if [ "$runner_norm" != "$expected_norm" ]; then
        echo "❌ PARITY DIVERGENCE: $name"
        diff <(echo "$expected_norm") <(echo "$runner_norm") || true
        fail=1
    else
        echo "✅ $name byte-identical（toolchainId 已排除）"
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "== Task 0 门失败：runner 在 stock JRE/arm64 下与 aster-api 有分叉 → 回头改 A/B =="
    exit 1
fi
echo "== Task 0 门通过：stock Zulu JRE 25 + Truffle 解释器真跑 + byte-identical =="
