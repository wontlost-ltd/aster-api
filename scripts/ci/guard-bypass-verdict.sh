#!/usr/bin/env bash
# 必需检查 `Guard bypass suite (#466)` 的判定：汇总 build-local / build-hosted 两条路径上
# 「Guard bypass suite (#466)」步骤的 outcome（ci-build.yml 作业输出 guard-bypass）。
#
# 用法：guard-bypass-verdict.sh <local-outcome> <hosted-outcome>
#   任一路径为 success → 0；否则 1（fail-closed：skipped / cancelled / 空串一律视为未通过）。
#   本地 failure 而托管 success（回落语义同 ci-result，回落是为 runner 故障设计的）→ 仍为 0，
#   但输出 ::warning::，不让「本地红、托管绿」悄悄消失。
set -euo pipefail
LOCAL="${1-}"
HOSTED="${2-}"
echo "guard-bypass: local=${LOCAL:-<none>} hosted=${HOSTED:-<none>}"
if [[ "$LOCAL" == "success" || "$HOSTED" == "success" ]]; then
  if [[ "$LOCAL" == "failure" ]]; then
    echo "::warning::Guard 绕过套件本地失败、托管通过，请查看 build-local 日志"
  fi
  echo "Guard 绕过套件通过"
  exit 0
fi
echo "::error::Guard 绕过套件未通过（任一路径都未得到 success；skipped 视为失败）"
exit 1
