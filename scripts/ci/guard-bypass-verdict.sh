#!/usr/bin/env bash
# 必需检查 `Guard bypass suite (#466)` 的判定：汇总 build-local / build-hosted 两条路径上
# 「Guard bypass suite (#466)」步骤的 outcome（ci-build.yml 作业输出 guard-bypass）。
#
# 用法：guard-bypass-verdict.sh <local-outcome> <hosted-outcome> [docs-only]
#   任一路径为 success → 0；否则 1（fail-closed：skipped / cancelled / 空串一律视为未通过）。
#   docs-only 为 true（changes 作业判定只动了仓库文档）且两条路径都没跑（outcome 为空 / skipped）→ 0：
#   套件与本次改动无关，按设计不跑。docs-only 时只要有一条路径跑了，就仍按上一条规则判定。
#   本地 failure 而托管 success（回落语义同 ci-result，回落是为 runner 故障设计的）→ 仍为 0，
#   但输出 ::warning::，不让「本地红、托管绿」悄悄消失。
set -euo pipefail
LOCAL="${1-}"
HOSTED="${2-}"
DOCS_ONLY="${3-}"
echo "guard-bypass: local=${LOCAL:-<none>} hosted=${HOSTED:-<none>} docs-only=${DOCS_ONLY:-<none>}"
if [[ "$LOCAL" == "success" || "$HOSTED" == "success" ]]; then
  if [[ "$LOCAL" == "failure" ]]; then
    echo "::warning::Guard 绕过套件本地失败、托管通过，请查看 build-local 日志"
  fi
  echo "Guard 绕过套件通过"
  exit 0
fi
if [[ "$DOCS_ONLY" == "true" && ( -z "$LOCAL" || "$LOCAL" == "skipped" ) && ( -z "$HOSTED" || "$HOSTED" == "skipped" ) ]]; then
  echo "Guard 绕过套件未运行：只动了仓库文档，按设计通过"
  exit 0
fi
echo "::error::Guard 绕过套件未通过（任一路径都未得到 success；skipped 视为失败）"
exit 1
