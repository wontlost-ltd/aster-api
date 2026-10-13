#!/usr/bin/env bash
# pin-closure-guard.sh 的表驱动测试（#341 / #343）。离线、无依赖。
# 用法：bash scripts/ci/pin-closure-guard.test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pin-closure-guard.sh"
FAILED=0

# case <名称> <期望退出码> <期望输出片段> KEY=VALUE...
case_() {
  local name="$1" want_code="$2" want_text="$3"; shift 3
  local out code
  set +e
  out="$(env -i PATH="$PATH" \
    EVENT=push REF=refs/heads/main TRAIN_ID= STALE=false CLIENT_ID=Iv23test \
    DEPLOY_RESULT=success PIN_RESULT=success PINNED=true FINAL_STALE= \
    "$@" bash "$SCRIPT" 2>&1)"
  code=$?
  set -e
  if [ "$code" = "$want_code" ] && [[ "$out" == *"$want_text"* ]]; then
    echo "  ✓ ${name}"
  else
    echo "  ✗ ${name}（期望 exit=${want_code} 含「${want_text}」，实际 exit=${code}）"
    printf '%s\n' "$out" | while IFS= read -r line; do echo "      ${line}"; done
    FAILED=1
  fi
}

echo "pin-closure-guard:"
case_ "push 且 PR 已开出：闭环成立"                0 "✓ 闭环成立"
case_ "列车 dispatch 且 PR 已开出：闭环成立"         0 "✓ 闭环成立" EVENT=workflow_dispatch TRAIN_ID=t-1
case_ "★列车 dispatch：job success 但开 PR 步骤被 Final stale gate 跳过 → 断裂" \
      1 "部署闭环断裂" EVENT=workflow_dispatch TRAIN_ID=t-1 PINNED=false FINAL_STALE=true
case_ "★列车断裂时提示重新触发"                     1 "请重新触发本次列车部署" EVENT=workflow_dispatch TRAIN_ID=t-1 PINNED=false FINAL_STALE=true
case_ "push：开 PR 前 main 前进 → 由新 commit 接手（警告，不失败）" \
      0 "由更新的 commit 的 deploy 接手" PINNED=false FINAL_STALE=true
case_ "★job success 但 pinned 为空（步骤被其它条件跳过）→ 断裂" 1 "部署闭环断裂" PINNED=
case_ "image-pin-pr skipped → 断裂并提示 if 条件不一致" 1 "if: 条件与本脚本" PIN_RESULT=skipped PINNED=
case_ "image-pin-pr failure → 断裂"                 1 "部署闭环断裂" PIN_RESULT=failure PINNED=
case_ "★该 pin 但未配 CLIENT_ID → 断裂（不再只警告）" 1 "IMAGE_PIN_APP_CLIENT_ID" CLIENT_ID= PIN_RESULT=skipped PINNED=
case_ "deploy job 过时（stale）→ 按设计不 pin"        0 "stale" STALE=true PIN_RESULT=skipped PINNED=
case_ "deploy 未成功 → 不归本 job 管"               0 "闭环判定不适用" DEPLOY_RESULT=failure PIN_RESULT=skipped PINNED=
case_ "手工无参 dispatch → 不该 pin"                0 "不该 pin" EVENT=workflow_dispatch TRAIN_ID= PIN_RESULT=skipped PINNED=
case_ "非 main 分支 push → 不该 pin"                0 "不该 pin" REF=refs/heads/feature PIN_RESULT=skipped PINNED=

if [ "$FAILED" = 0 ]; then
  echo "全部通过"
else
  echo "存在失败"
  exit 1
fi
