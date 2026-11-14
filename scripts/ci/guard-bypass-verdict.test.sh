#!/usr/bin/env bash
# guard-bypass-verdict.sh 的表驱动测试（#466 A7，门禁必须能变红）。离线、无依赖。
# 用法：bash scripts/ci/guard-bypass-verdict.test.sh
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/guard-bypass-verdict.sh"
FAILED=0
PASSED=0
TOTAL=0

case_() {  # 名称 local hosted 期望退出码 [输出须含]
  local out code
  TOTAL=$((TOTAL + 1))
  set +e
  out=$(bash "$SCRIPT" "$2" "$3" 2>&1); code=$?
  set -e
  if [ "$code" != "$4" ]; then
    echo "  ✗ $1（期望 $4，实际 ${code}：${out}）"; FAILED=1; return
  fi
  if [ -n "${5-}" ] && [[ "$out" != *"$5"* ]]; then
    echo "  ✗ $1（输出缺少「$5」：${out}）"; FAILED=1; return
  fi
  if [ -z "${5-}" ] && [[ "$out" == *"::warning::"* ]]; then
    echo "  ✗ $1（不应有告警：${out}）"; FAILED=1; return
  fi
  PASSED=$((PASSED + 1))
  echo "  ✓ $1"
}
echo "guard-bypass-verdict:"
case_ "本地通过、托管未跑"         success ""      0
case_ "本地未跑、托管通过"         ""      success 0
case_ "两路均失败"                 failure failure 1
case_ "本地 skipped、托管未跑"     skipped ""      1
case_ "两路均未跑（空串）"         ""      ""      1
case_ "本地失败、托管通过：告警"   failure success 0 "::warning::"
echo "guard-bypass-verdict: ${PASSED}/${TOTAL} 通过"
[ "$FAILED" = 0 ] || exit 1
