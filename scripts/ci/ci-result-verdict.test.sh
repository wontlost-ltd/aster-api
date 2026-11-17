#!/usr/bin/env bash
# ci-result-verdict.sh 的表驱动测试（门禁必须能变红）。离线、无依赖。
# 用法：bash scripts/ci/ci-result-verdict.test.sh
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ci-result-verdict.sh"
FAILED=0
PASSED=0
TOTAL=0

case_() {  # 名称 local hosted guard docs-only 期望退出码 [输出须含]
  local out code
  TOTAL=$((TOTAL + 1))
  set +e
  out=$(env -i PATH="$PATH" LOCAL="$2" HOSTED="$3" GUARD_BYPASS="$4" DOCS_ONLY="$5" bash "$SCRIPT" 2>&1); code=$?
  set -e
  if [ "$code" != "$6" ]; then
    echo "  ✗ $1（期望 $6，实际 ${code}：${out}）"; FAILED=1; return
  fi
  if [ -n "${7-}" ] && [[ "$out" != *"$7"* ]]; then
    echo "  ✗ $1（输出缺少「$7」：${out}）"; FAILED=1; return
  fi
  PASSED=$((PASSED + 1))
  echo "  ✓ $1"
}
echo "ci-result-verdict:"
case_ "本地通过"                               success skipped success false 0
case_ "本地跳过、托管通过"                     skipped success success false 0
case_ "本地失败、托管通过（回落）"             failure success success ""    0
case_ "两路均失败"                             failure failure success false 1
case_ "Guard 检查未通过 → 失败（即使构建绿）"  success skipped failure false 1 "Guard 绕过套件"
case_ "两路均 skipped 且非 docs-only → 失败"   skipped skipped success false 1
case_ "两路均 skipped、docs-only 探测失败（空）→ 失败" skipped skipped success "" 1
case_ "docs-only：两路均 skipped → 通过"       skipped skipped success true  0 "只动了仓库文档"
case_ "docs-only 但 Guard 检查失败 → 失败"     skipped skipped failure true  1
case_ "docs-only 但托管失败（不该发生）→ 失败" skipped failure success true  1
case_ "docs-only 但托管被取消 → 失败"          skipped cancelled success true 1
echo "ci-result-verdict: ${PASSED}/${TOTAL} 通过"
[ "$FAILED" = 0 ] || exit 1
