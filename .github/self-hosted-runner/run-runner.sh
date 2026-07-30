#!/usr/bin/env bash
# 本地 Linux self-hosted runner 的守护循环。
#
# 每个 runner 容器以 --ephemeral 注册：跑完一个 job 即退出并自动从 org 注销，
# 本脚本随后拉起一个全新实例。好处是 job 之间不共享任何文件系统残留
# （self-hosted runner 最大的固有风险就是状态跨 job 残留），代价是每个 job 前
# 有约 10s 注册开销——相对 api CI 的 12 分钟可忽略。
#
# 用法（凭据只从环境读，绝不写进文件/镜像）：
#     export RUNNER_PAT=<有 admin:org 的 PAT>          # 只用于换取短时注册令牌
#     ./run-runner.sh
# 或用一次性令牌单跑一轮：
#     REG_TOKEN=<registration token> ./run-runner.sh
#
# 停止：Ctrl-C，或 `podman machine ssh podman-machine 'sudo podman rm -f aster-linux-runner'`
set -euo pipefail

MACHINE="${PODMAN_MACHINE:-podman-machine}"
IMAGE="${RUNNER_IMAGE:-localhost/aster-runner:2.336.0}"
ORG="${ORG_NAME:-wontlost-ltd}"
GROUP="${RUNNER_GROUP:-local-mac}"
NAME_PREFIX="${RUNNER_NAME_PREFIX:-pang-linux-podman}"
LABELS="${RUNNER_LABELS:-self-hosted,linux,ARM64,podman,local-linux}"
CONTAINER="${CONTAINER_NAME:-aster-linux-runner}"

# 宿主 podman socket——services:/Testcontainers 的容器都由它创建（sibling 模式，
# 不在 runner 容器内嵌套 daemon）。
SOCK="${PODMAN_SOCKET:-/run/podman/podman.sock}"

need_token() {
  if [[ -n "${REG_TOKEN:-}" ]]; then echo "$REG_TOKEN"; return; fi
  if [[ -z "${RUNNER_PAT:-}" ]]; then
    echo "错误：需 RUNNER_PAT（换取短时注册令牌）或 REG_TOKEN（一次性令牌）" >&2
    exit 1
  fi
  curl -fsSL -X POST \
    -H "Authorization: Bearer ${RUNNER_PAT}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/orgs/${ORG}/actions/runners/registration-token" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
}

cleanup() {
  echo ">> 停止 runner 容器…"
  podman machine ssh "$MACHINE" "sudo podman rm -f ${CONTAINER}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo ">> 守护循环启动（Ctrl-C 停止）。镜像=${IMAGE} org=${ORG} group=${GROUP}"
round=0
while true; do
  round=$((round + 1))
  tok="$(need_token)"
  # 单轮用完即弃的一次性令牌只有 1h 有效期；每轮重新取，避免长跑后令牌过期。
  echo ">> [第 ${round} 轮] 拉起 ephemeral runner…"
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
      -v ${SOCK}:/var/run/docker.sock \
      -e ORG_NAME='${ORG}' \
      -e RUNNER_GROUP='${GROUP}' \
      -e RUNNER_NAME='${NAME_PREFIX}' \
      -e RUNNER_LABELS='${LABELS}' \
      -e REG_TOKEN='${tok}' \
      ${IMAGE}" || echo ">> 本轮容器退出（可能是跑完一个 job，正常）"
  sleep 2
done
