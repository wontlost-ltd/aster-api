#!/usr/bin/env bash
# 复用制品的来源校验（ADR 0053 §5 / review H1）。stdin：`gh api repos/<repo>/actions/runs/<id>` 的 JSON。
# 只接受「本仓 main 分支上 deploy.yml 的 push / workflow_dispatch run」产出的 quarkus-app-<key>：
# 制品名谁都能起（PR、fork、其他 workflow 都能上传同名制品），只有来源 run 可信，「存在即已验证」才成立。
# env：REPO=owner/name（本仓）。退出码 0=可信，1=拒绝（调用方回落完整构建）。
set -euo pipefail
: "${REPO:?REPO 未设置}"
if jq -e --arg repo "$REPO" '
     .path == ".github/workflows/deploy.yml"
     and .head_branch == "main"
     and (.event == "push" or .event == "workflow_dispatch")
     and (.head_repository.full_name // "") == $repo' >/dev/null 2>&1; then
  exit 0
fi
echo "拒绝复用：来源 run 不是 ${REPO} main 分支上 deploy.yml 的 push/dispatch run" >&2
exit 1
