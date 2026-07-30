#!/usr/bin/env bash
# 容器化 self-hosted runner 入口：注册 → 运行 → 退出即注销。
#
# 采用 --ephemeral：每跑完一个 job 就退出并从 org 注销，由外层 systemd/循环重新拉起
# 一个干净实例。这样 job 之间不共享文件系统残留，接近托管 runner 的一次性语义
# （self-hosted runner 最大的风险就是状态跨 job 残留）。
set -euo pipefail

: "${RUNNER_SCOPE:=org}"
: "${ORG_NAME:?必须设置 ORG_NAME（如 wontlost-ltd）}"
: "${RUNNER_GROUP:=local-mac}"
: "${RUNNER_LABELS:=self-hosted,linux,ARM64,podman,local-linux}"
: "${RUNNER_NAME:=$(hostname)-linux-$$}"

# 注册令牌：优先用一次性 REG_TOKEN（有效期 1h）；否则用 PAT 现取一个。
# ★不接受把长期 PAT 写进镜像；PAT 只在启动瞬间用于换取短时 registration token。
if [[ -z "${REG_TOKEN:-}" ]]; then
  if [[ -z "${RUNNER_PAT:-}" ]]; then
    echo "错误：需提供 REG_TOKEN（一次性注册令牌）或 RUNNER_PAT（用于换取令牌）" >&2
    exit 1
  fi
  echo ">> 用 PAT 换取短时 registration token…"
  REG_TOKEN="$(curl -fsSL -X POST \
    -H "Authorization: Bearer ${RUNNER_PAT}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/orgs/${ORG_NAME}/actions/runners/registration-token" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
fi

cleanup() {
  echo ">> 注销 runner…"
  ./config.sh remove --token "${REG_TOKEN}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo ">> 注册 runner name=${RUNNER_NAME} group=${RUNNER_GROUP} labels=${RUNNER_LABELS}"
./config.sh \
  --unattended \
  --replace \
  --ephemeral \
  --url "https://github.com/${ORG_NAME}" \
  --token "${REG_TOKEN}" \
  --name "${RUNNER_NAME}" \
  --runnergroup "${RUNNER_GROUP}" \
  --labels "${RUNNER_LABELS}" \
  --work /actions-runner/_work

# 验证宿主 podman socket 可用——不可用就别假装能跑（否则 services/Testcontainers
# 会在 job 中途以难懂的错误失败，而回落逻辑看到的是"job 失败"而非"runner 不可用"）。
if ! docker version >/dev/null 2>&1; then
  echo "错误：无法访问 ${DOCKER_HOST}——请确认已挂载宿主 podman socket" >&2
  exit 1
fi
echo ">> 容器运行时就绪：$(docker version --format '{{.Server.Version}}')"

exec ./run.sh
