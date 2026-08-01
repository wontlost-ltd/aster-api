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

# ★不要写死 machine 名：`podman machine init` 默认创建的是
#   **podman-machine-default**，而此处原先默认 `podman-machine`。
#   名字对不上时 `podman machine inspect` 恒返回空 → 状态判定为 unknown →
#   supervisor 卡在「启动超时 → 重试」的死循环里，**runner 永远起不来**，
#   而 launchctl 看到的进程是活的（exit 0），从表面看不出问题。
#   （2026-08-01 实测：日志里连续数十次「podman machine 'podman-machine'
#     状态=unknown」，而实际存在的是 podman-machine-default。）
#   改为：显式指定 > 唯一存在的 machine > 传统默认名。
_detect_machine() {
  [[ -n "${PODMAN_MACHINE:-}" ]] && { echo "$PODMAN_MACHINE"; return; }
  local names
  names="$(podman machine list --format '{{.Name}}' 2>/dev/null)"
  # 仅有一个 machine 时直接用它，避免名字约定漂移
  if [[ "$(echo "$names" | grep -c . )" == "1" ]]; then
    echo "$names"; return
  fi
  # 多个时优先官方默认名
  if echo "$names" | grep -qx 'podman-machine-default'; then
    echo 'podman-machine-default'; return
  fi
  echo 'podman-machine'
}
MACHINE="$(_detect_machine)"
IMAGE="${RUNNER_IMAGE:-localhost/aster-runner:2.336.0}"
ORG="${ORG_NAME:-wontlost-ltd}"
GROUP="${RUNNER_GROUP:-local-mac}"
RUNNER_NAME="${RUNNER_NAME:-pang-linux-podman}"
LABELS="${RUNNER_LABELS:-self-hosted,linux,ARM64,podman,local-linux}"
CONTAINER="${CONTAINER_NAME:-aster-linux-runner}"
# ★容器内存上限：多 runner 并行时必须设，否则一个 job 的 OOM 会波及另一个。
#   实测单个 aster-api job 峰值约 6GiB（java 进程 anon-rss 4.45GiB +
#   Testcontainers 的 postgres/redis + Gradle daemon），故默认 8g 留余量。
#   ★VM 总内存需 ≥ 2×MEMORY + 4GiB（buff/cache 与 VM 自身）。
MEMORY="${RUNNER_MEMORY:-8g}"
SOCK="${PODMAN_SOCKET:-/run/podman/podman.sock}"
KEYCHAIN_SERVICE="${KEYCHAIN_SERVICE:-aster-runner-pat}"
# 记录本实例上一轮注册到的 runner id（防 reap 误删自己）。按 RUNNER_NAME 区分，
# 使多实例互不干扰。
SELF_ID_FILE="${TMPDIR:-/tmp}/aster-runner-self-id-${RUNNER_NAME}"

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

# 清理**本实例**遗留的陈旧 runner 记录。
#
# 背景：--ephemeral 正常跑完会自我注销；但容器被强杀（podman rm -f、断电、
# machine 崩）时来不及注销，GitHub 侧会残留一条记录，其 session 短时间内仍被
# 视为活跃，导致新容器报
#   `A session for this runner already exists.` + `Runner connect error: Conflict`
#
# ★但实测（2026-08-02）该 Conflict **会自愈**：runner 自带重试，约 98 秒后
#   `Runner reconnected` → `Listening for Jobs`。且 entrypoint.sh 用了
#   `config.sh --replace`，注册层的同名冲突由 CLI 自己处理
#   （日志可见 "A runner exists with the same name" → "Successfully replaced"）。
#   所以 reap 不是必需品，只是加速收敛的优化。
#
# ★★为何必须限定"只删比本轮更早的记录"（2026-08-02 多实例事故）：
#   初版无条件删所有同名记录。跑第二个 supervisor 后出现**幽灵 runner**——
#   s1 删掉 id=277 → 拉起容器 → 容器注册并重连 → s1 下一轮又删同名记录，
#   这次删掉的是**自己刚注册的那条**。结果：容器认为自己 "Listening for Jobs"，
#   而 GitHub 侧已无该记录，永远收不到 job，且没有任何报错。
#   （实测：容器 Up、日志正常，`gh api .../runners` 里却查无此 runner。）
#
#   修法：GitHub 的 runners API **不返回任何时间字段**（只有 busy/id/labels/
#   name/os/status/version，已实测），故无法按时间过滤。改用两道防线：
#     (1) 跳过 busy=true 的记录——那是正在跑 job 的实例，删它会让 job 失去 runner；
#     (2) 记住**上一轮拉起容器后观察到的自己的 id**，本轮不再删它
#         （LAST_SELF_ID）。这样"删掉自己刚注册的记录"这条路径被堵死。
#   同名不同机本就不该发生（RUNNER_NAME 唯一标识一个实例）。
reap_stale_runner() {
  local pat="$1" ids
  ids="$(curl -fsSL \
      -H "Authorization: Bearer ${pat}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/orgs/${ORG}/actions/runners?per_page=100" 2>/dev/null \
    | RUNNER_NAME="$RUNNER_NAME" SELF_ID="${LAST_SELF_ID:-}" python3 -c '
import json, os, sys
name = os.environ["RUNNER_NAME"]
self_id = os.environ.get("SELF_ID", "").strip()
try:
    data = json.load(sys.stdin)
except Exception:
    raise SystemExit
for r in data.get("runners", []):
    if r.get("name") != name:
        continue
    # busy 的记录一律不动：那是**正在跑 job** 的实例（可能就是自己），
    # 删掉会让运行中的 job 失去 runner。
    if r.get("busy"):
        continue
    # 不删上一轮确认属于自己的那条——否则会把刚注册好的记录抹掉，
    # 造成"容器在监听但 GitHub 查无此 runner"的幽灵状态。
    if self_id and str(r.get("id")) == self_id:
        continue
    print(r.get("id"), r.get("status"))
' 2>/dev/null)"
  [[ -z "$ids" ]] && return 0
  local id status
  while read -r id status; do
    [[ -z "$id" ]] && continue
    log "清理本实例陈旧记录 id=${id}（status=${status}）——加速 session 收敛"
    curl -fsSL -X DELETE \
      -H "Authorization: Bearer ${pat}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/orgs/${ORG}/actions/runners/${id}" >/dev/null 2>&1 \
      || log "  警告：删除 id=${id} 失败（权限不足？）；Conflict 仍会自愈，只是慢些"
  done <<< "$ids"
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
    # ★区分"项不存在"与"项存在但值为空"——后者是 add-generic-password 交互式输入时
    #   没粘贴成功造成的（提示串会挤在一行，容易误以为已输入）。两种情况都会让
    #   get_pat 返回空，但修复动作不同：前者要新增，后者要用 -U 覆盖。
    if security find-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" >/dev/null 2>&1; then
      log "错误：keychain 项 '${KEYCHAIN_SERVICE}' 存在但**值为空**（很可能交互式输入时没粘上）。"
      log "  用 -U 覆盖重存：security add-generic-password -a \"\$USER\" -s ${KEYCHAIN_SERVICE} -U -w"
      log "  或用非交互方式（避免提示串挤一行）：printf '%s' '<PAT>' | security add-generic-password -a \"\$USER\" -s ${KEYCHAIN_SERVICE} -U -w"
    else
      log "错误：keychain 里没有 '${KEYCHAIN_SERVICE}'。先跑：security add-generic-password -a \"\$USER\" -s ${KEYCHAIN_SERVICE} -w"
    fi
    sleep 60; continue
  fi

  # 先清陈旧同名记录，再取令牌注册（顺序重要：注册时若旧 session 还在就会 Conflict）。
  LAST_SELF_ID="$(cat "$SELF_ID_FILE" 2>/dev/null || true)"
  reap_stale_runner "$pat"

  tok="$(mint_token "$pat")"
  unset pat
  if [[ -z "$tok" ]]; then
    log "取注册令牌失败（PAT 过期/无 admin:org/网络问题？），${backoff}s 后重试"
    sleep "$backoff"; backoff=$(( backoff * 2 > 300 ? 300 : backoff * 2 )); continue
  fi

  backoff=5   # 成功取到令牌即重置退避

  # 后台记录本轮容器注册到的 id：容器起来后约 10-20s 会出现在 API 里。
  # 记住它，下一轮 reap 就不会误删自己（见 reap_stale_runner 的说明）。
  (
    sleep 25
    LAST_SELF_ID="$(curl -fsSL \
        -H "Authorization: Bearer $(get_pat)" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/orgs/${ORG}/actions/runners?per_page=100" 2>/dev/null \
      | RUNNER_NAME="$RUNNER_NAME" python3 -c '
import json, os, sys
name = os.environ["RUNNER_NAME"]
try:
    data = json.load(sys.stdin)
except Exception:
    raise SystemExit
for r in data.get("runners", []):
    if r.get("name") == name:
        print(r.get("id")); break
' 2>/dev/null)"
    [[ -n "$LAST_SELF_ID" ]] && echo "$LAST_SELF_ID" > "$SELF_ID_FILE"
  ) &

  log "拉起 ephemeral runner 容器…"
  # --rm + --ephemeral：跑完一个 job 即注销退出，下一轮拉起全新实例，
  # 使 job 之间不共享文件系统残留。前台运行（不加 -d），容器退出即本轮结束。
  podman machine ssh "$MACHINE" "sudo podman rm -f ${CONTAINER} >/dev/null 2>&1; \
    sudo podman run --rm --name ${CONTAINER} \
      --security-opt label=disable \
      `# ★--network host 必需（2026-07-30 实测）：services: 容器由宿主 podman 以` \
      `# -p 5432:5432 启动，端口发布在 **VM** 的网络命名空间。托管 runner 的 job` \
      `# 直接跑在 VM 上故 localhost:5432 可达；而本 runner 若用默认 bridge 模式`  \
      `# 会有自己的 netns，localhost 指向容器自身 → 连不上 service，DB 测试全红`  \
      `# （实测 bridge=no response / host=accepting connections）。共享 VM 网络后`  \
      `# localhost 语义与托管 runner 一致。` \
      --network host \
      `# 内存上限：防止一个 runner 的 job 撑爆 VM 波及另一个（多 runner 场景必需）。` \
      --memory ${MEMORY} \
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
