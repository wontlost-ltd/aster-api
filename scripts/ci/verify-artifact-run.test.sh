#!/usr/bin/env bash
# verify-artifact-run.sh 的表驱动测试（ADR 0053 §5 / review H1）。离线、只依赖 jq。
# 用法：bash scripts/ci/verify-artifact-run.test.sh
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-artifact-run.sh"
FAILED=0
GOOD='{"id":7,"path":".github/workflows/deploy.yml","head_branch":"main","event":"push","conclusion":null,"head_repository":{"full_name":"wontlost-ltd/aster-api"}}'

case_() {  # 名称 期望退出码 jq 改写
  local out code
  set +e
  out=$(jq -c "$3" <<<"$GOOD" | REPO=wontlost-ltd/aster-api bash "$SCRIPT" 2>&1); code=$?
  set -e
  if [ "$code" = "$2" ]; then echo "  ✓ $1"; else echo "  ✗ $1（期望 $2，实际 ${code}：${out}）"; FAILED=1; fi
}
echo "verify-artifact-run:"
case_ "main 上 deploy.yml 的 push run：接受"                0 '.'
case_ "main 上 deploy.yml 的 workflow_dispatch run：接受"   0 '.event="workflow_dispatch"'
case_ "PR run：拒绝"                                       1 '.event="pull_request"'
case_ "fork 仓的 run：拒绝"                                 1 '.head_repository.full_name="evil/aster-api"'
case_ "非 deploy.yml（ci.yml）：拒绝"                       1 '.path=".github/workflows/ci.yml"'
case_ "非 main 分支：拒绝"                                  1 '.head_branch="feat/x"'
case_ "缺 head_repository：拒绝"                            1 'del(.head_repository)'
set +e; echo 'not json' | REPO=wontlost-ltd/aster-api bash "$SCRIPT" >/dev/null 2>&1; c=$?; set -e
[ "$c" = 1 ] && echo "  ✓ 非法 JSON：拒绝" || { echo "  ✗ 非法 JSON 应拒绝（${c}）"; FAILED=1; }
[ "$FAILED" = 0 ] || { echo "verify-artifact-run: FAILED"; exit 1; }
echo "verify-artifact-run: all passed"
