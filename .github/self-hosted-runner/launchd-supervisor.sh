#!/usr/bin/env bash
# launchd 守护入口：确保 podman machine 在跑，然后循环拉起 ephemeral runner 容器。
#
# 与 run-runner.sh 的区别：本脚本面向 launchd（开机自启、无交互终端），故
#   1. 自己负责把 podman machine 拉起来——实测本机没有 podman 的 LaunchAgent，
#      machine 只在手动或 Podman Desktop 启动时才跑，开机后默认是停的。
#      不做这一步的话 launchd 项每次开机都会静默失败。
#   2. 凭据从 keychain 读，不依赖交互式 shell 的环境变量。
#   3. 所有输出走 stdout/stderr，由 launchd 重定向到日志文件。
#
# 凭据准备（一次性，token 不落盘明文）：
#     security add-generic-password -a "$USER" -s aster-runner-pat -w
#   （回车后交互式输入 PAT；需 admin:org 权限，仅用于换取短时注册令牌）
#
# 安装/卸载见 README。

set -uo pipefail

MACHINE="${PODMAN_MACHINE:-podman-machine}"
IMAGE="${RUNNER_IMAGE:-localhost/aster-runner:2.336.0}"
ORG="${ORG_NAME:-wontlost-ltd}"
GROUP="${RUNNER_GROUP:-local-mac}"
RUNNER_NAME="${RUNNER_NAME:-pang-linux-podman}"
LABELS="${RUNNER_LABELS:-self-hosted,linux,ARM64,podman,local-linux}"
CONTAINER="${CONTAINER_NAME:-aster-linux-runner}"
SOCK="${PODMAN_SOCKET:-/run/podman/podman.sock}"
KEYCHAIN_SERVICE="${KEYCHAIN_SERVICE:-aster-runner-pat}"

# launchd 的默认 PATH 极窄（/usr/bin:/bin:/usr/sbin:/sbin），不含任何第三方安装位置。
# ★本机 podman 实测在 /opt/podman/bin（Podman.app 安装器路径），**不是** Homebrew 的
#   /opt/homebrew/bin——把 PATH 写成后者会让本脚本在开机时"command not found"而静默失败。
#   故把常见位置全列上，并在下面显式断言 podman 可用。
export PATH="/opt/podman/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"; }

# fail-loud：launchd 环境下最常见的故障就是 PATH 里找不到 podman，而那会表现为
# 一个空日志 + 反复重启，极难排查。故开头就断言并把实际 PATH 打出来。
if ! command -v podman >/dev/null 2>&1; then
  log "错误：PATH 中找不到 podman。当前 PATH=${PATH}"
  log "  提示：本机实测 podman 在 /opt/podman/bin；若你换了安装方式，请更新本脚本顶部的 PATH。"
  exit 1
fi
log "podman: $(command -v podman) ($(podman --version 2>/dev/null))"

get_pat() {
  security find-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null
}

ensure_machine() {
  local state
  state="$(podman machine inspect "$MACHINE" --format '{{.State}}' 2>/dev/null)"
  if [[ "$state" == "running" ]]; then
    return 0
  fi
  log "podman machine '$MACHINE' 状态=${state:-unknown}，尝试启动…"
  podman machine start "$MACHINE" >/dev/null 2>&1
  # 起 VM 可能要几十秒；轮询而非固定 sleep。
  for _ in $(seq 1 60); do
    [[ "$(podman machine inspect "$MACHINE" --format '{{.State}}' 2>/dev/null)" == "running" ]] && {
      log "podman machine 已就绪"
      return 0
    }
    sleep 2
  done
  log "错误：podman machine 启动超时"
  return 1
}

mint_token() {
  local pat="$1"
  curl -fsSL -X POST \
    -H "Authorization: Bearer ${pat}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/orgs/${ORG}/actions/runners/registration-token" \
    2>/dev/null \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])' 2>/dev/null
}

cleanup() {
  log "收到退出信号，清理 runner 容器…"
  podman machine ssh "$MACHINE" "sudo podman rm -f ${CONTAINER}" >/dev/null 2>&1
  exit 0
}
trap cleanup INT TERM

log "守护启动：org=${ORG} group=${GROUP} image=${IMAGE}"

# 退避：连续失败时逐步拉长间隔，避免开机瞬间或 API 故障时疯狂重试刷爆日志/触发限流。
backoff=5
while true; do
  if ! ensure_machine; then
    log "machine 不可用，${backoff}s 后重试"
    sleep "$backoff"; backoff=$(( backoff * 2 > 300 ? 300 : backoff * 2 )); continue
  fi

  pat="$(get_pat)"
  if [[ -z "$pat" ]]; then
    log "错误：keychain 里没有 '${KEYCHAIN_SERVICE}'。先跑：security add-generic-password -a \"\$USER\" -s ${KEYCHAIN_SERVICE} -w"
    sleep 60; continue
  fi

  tok="$(mint_token "$pat")"
  unset pat
  if [[ -z "$tok" ]]; then
    log "取注册令牌失败（PAT 过期/无 admin:org/网络问题？），${backoff}s 后重试"
    sleep "$backoff"; backoff=$(( backoff * 2 > 300 ? 300 : backoff * 2 )); continue
  fi

  backoff=5   # 成功取到令牌即重置退避

  log "拉起 ephemeral runner 容器…"
  # --rm + --ephemeral：跑完一个 job 即注销退出，下一轮拉起全新实例，
  # 使 job 之间不共享文件系统残留。前台运行（不加 -d），容器退出即本轮结束。
  podman machine ssh "$MACHINE" "sudo podman rm -f ${CONTAINER} >/dev/null 2>&1; \
    sudo podman run --rm --name ${CONTAINER} \
      --security-opt label=disable \
      -v ${SOCK}:/var/run/docker.sock \
      -e ORG_NAME='${ORG}' \
      -e RUNNER_GROUP='${GROUP}' \
      -e RUNNER_NAME='${RUNNER_NAME}' \
      -e RUNNER_LABELS='${LABELS}' \
      -e REG_TOKEN='${tok}' \
      ${IMAGE}" 2>&1
  unset tok
  log "runner 容器退出（跑完一个 job 或被中断），2s 后重建"
  sleep 2
done
