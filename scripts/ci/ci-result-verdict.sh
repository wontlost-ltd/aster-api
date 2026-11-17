#!/usr/bin/env bash
# 必需检查 `ci-result` 的判定：汇总 build-local / build-hosted 两条路径与 Guard 绕过套件检查。
#
# 输入（环境变量，由 ci.yml 的 ci-result job 传入）：
#   LOCAL / HOSTED   两条构建路径的 job 结论（success / failure / cancelled / skipped）
#   GUARD_BYPASS     `Guard bypass suite (#466)` 作业的结论
#   DOCS_ONLY        changes 作业的 docs-only 输出（'true' = 只动了仓库文档）；探测失败时为空
# 规则：
#   - Guard 绕过套件检查不是 success → 失败；
#   - 任一路径 success → 通过（回落语义：本地 runner 故障时托管兜底）；
#   - DOCS_ONLY=true 且两条路径都是 skipped → 通过（按设计不构建）；
#   - 其余一律失败（fail-closed：两条路径都没成功，且不是因为 docs-only 而没跑）。
# 退出码：0 通过，1 失败。
set -euo pipefail
LOCAL="${LOCAL-}"
HOSTED="${HOSTED-}"
GUARD_BYPASS="${GUARD_BYPASS-}"
DOCS_ONLY="${DOCS_ONLY-}"
echo "build-local=${LOCAL:-<none>}  build-hosted=${HOSTED:-<none>}  guard-bypass=${GUARD_BYPASS:-<none>}  docs-only=${DOCS_ONLY:-<none>}"
if [[ "$GUARD_BYPASS" != "success" ]]; then
  echo "::error::CI 失败——Guard 绕过套件检查未通过（guard-bypass=${GUARD_BYPASS:-<none>}）"
  exit 1
fi
if [[ "$LOCAL" == "success" || "$HOSTED" == "success" ]]; then
  echo "✅ CI 通过（至少一条路径成功）"
  exit 0
fi
if [[ "$DOCS_ONLY" == "true" && "$LOCAL" == "skipped" && "$HOSTED" == "skipped" ]]; then
  echo "✅ CI 通过（只动了仓库文档，按设计不构建、不测试）"
  exit 0
fi
echo "::error::CI 失败——本地与托管两条路径均未成功（local=${LOCAL:-<none>} hosted=${HOSTED:-<none>} docs-only=${DOCS_ONLY:-<none>}）"
exit 1
