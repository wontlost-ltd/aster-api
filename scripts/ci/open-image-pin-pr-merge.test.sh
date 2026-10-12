#!/usr/bin/env bash
# open-image-pin-pr.sh 合并门控的离线测试（issue #506）。
#
# 用假的 gh 回放 check-runs 响应（经真实 jq 执行脚本里的 --jq 表达式），并记录 gh pr merge 的参数。
# 不触发 clone/push：脚本以 --source-only 只加载函数。
# 用法：bash scripts/ci/open-image-pin-pr-merge.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/open-image-pin-pr.sh"
command -v jq >/dev/null || { echo "需要 jq"; exit 1; }

FAILED=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILED=1; }

HEAD_SHA="1111111111111111111111111111111111111111"
APP_ID=4253193

# 生成一条 check-runs 响应：参数为若干 "app_id:status:conclusion"。
check_runs_json() {
  local runs="" sep="" i=0
  for spec in "$@"; do
    IFS=: read -r app status conclusion <<<"$spec"
    [[ "$conclusion" == "-" ]] && conclusion=null || conclusion="\"$conclusion\""
    runs+="${sep}{\"name\":\"verify-image-pin\",\"app\":{\"id\":${app}},\"status\":\"${status}\",\"conclusion\":${conclusion},\"started_at\":\"2026-10-12T10:00:0${i}Z\"}"
    sep=","; i=$((i + 1))
  done
  printf '{"total_count":%d,"check_runs":[%s]}' "$#" "$runs"
}

# 建一个假的 gh：check-runs 请求依次弹出 $FAKE_DIR/responses 里的行，pr merge 记入 $FAKE_DIR/merges。
FAKE_DIRS=()
trap 'rm -rf "${FAKE_DIRS[@]}"' EXIT
setup_fake_gh() {
  FAKE_DIR="$(mktemp -d)"
  FAKE_DIRS+=("$FAKE_DIR")
  : > "$FAKE_DIR/merges"
  cat > "$FAKE_DIR/gh" <<'GH'
#!/usr/bin/env bash
set -euo pipefail
dir="$(dirname "$0")"
if [[ "$1" == "api" && "$2" == *"/check-runs"* ]]; then
  jq_expr=""
  while [[ $# -gt 0 ]]; do [[ "$1" == "--jq" ]] && { jq_expr="$2"; break; }; shift; done
  line="$(head -n1 "$dir/responses")"
  # 最后一条响应保持不变（模拟此后状态不再变化）。
  [[ "$(wc -l < "$dir/responses")" -gt 1 ]] && sed -i.bak '1d' "$dir/responses"
  if [[ "$line" == ERR:* ]]; then echo "${line#ERR:}" >&2; exit 1; fi
  printf '%s' "$line" | jq -r "$jq_expr"
  exit 0
fi
if [[ "$1" == "pr" && "$2" == "merge" ]]; then
  echo "$*" >> "$dir/merges"
  exit 0
fi
echo "unexpected gh call: $*" >&2; exit 2
GH
  chmod +x "$FAKE_DIR/gh"
  export PATH="$FAKE_DIR:$PATH"
}

run_case() {
  local responses_file="$1"
  setup_fake_gh
  cp "$responses_file" "$FAKE_DIR/responses"
  (
    export K3S_REPO=wontlost-ltd/k3s VERIFY_POLL_SECONDS=0 VERIFY_TIMEOUT_SECONDS="${CASE_TIMEOUT:-5}"
    # shellcheck source=/dev/null
    source "$TARGET_SCRIPT" --source-only
    merge_when_verified 696 "$HEAD_SHA"
  ) > "$FAKE_DIR/out" 2>&1 && echo 0 > "$FAKE_DIR/rc" || echo $? > "$FAKE_DIR/rc"
}

tmp="$(mktemp -d)"

echo "=== 1. 检查从未出现→进行中→通过：等待后合并，不用 --auto，锁定 head ==="
{ check_runs_json; echo; check_runs_json "$APP_ID:in_progress:-"; echo; check_runs_json "$APP_ID:completed:success"; echo; } > "$tmp/r1"
run_case "$tmp/r1"
[[ "$(cat "$FAKE_DIR/rc")" == 0 ]] && pass "返回 0" || fail "返回 $(cat "$FAKE_DIR/rc")：$(cat "$FAKE_DIR/out")"
grep -q -- "pr merge 696 -R wontlost-ltd/k3s --squash --delete-branch --match-head-commit $HEAD_SHA" "$FAKE_DIR/merges" \
  && pass "按锁定的 head 直接合并" || fail "合并参数不对：$(cat "$FAKE_DIR/merges")"
grep -q -- "--auto" "$FAKE_DIR/merges" && fail "不应使用 --auto" || pass "未使用 --auto"

echo "=== 2. 验签失败：不合并，返回非 0 ==="
{ check_runs_json "$APP_ID:completed:failure"; echo; } > "$tmp/r2"
run_case "$tmp/r2"
[[ "$(cat "$FAKE_DIR/rc")" != 0 ]] && pass "返回非 0" || fail "失败时不应返回 0"
[[ ! -s "$FAKE_DIR/merges" ]] && pass "未合并" || fail "验签失败仍合并了"
grep -q "未通过" "$FAKE_DIR/out" && pass "错误信息说明未通过" || fail "缺少错误信息：$(cat "$FAKE_DIR/out")"

echo "=== 3. 检查一直不出现：超时，不合并 ==="
{ check_runs_json; echo; } > "$tmp/r3"
CASE_TIMEOUT=1 run_case "$tmp/r3"
[[ "$(cat "$FAKE_DIR/rc")" != 0 ]] && pass "超时返回非 0" || fail "超时不应返回 0"
[[ ! -s "$FAKE_DIR/merges" ]] && pass "未合并" || fail "超时仍合并了"
grep -q "超时" "$FAKE_DIR/out" && pass "错误信息说明超时" || fail "缺少超时信息：$(cat "$FAKE_DIR/out")"

echo "=== 4. 其它 App 的同名检查通过：不算数，不合并 ==="
{ check_runs_json "15368:completed:success"; echo; } > "$tmp/r4"
CASE_TIMEOUT=1 run_case "$tmp/r4"
[[ ! -s "$FAKE_DIR/merges" ]] && pass "其它来源的 success 不触发合并" || fail "被其它 App 的同名检查冒充通过"

echo "=== 5. 同一 head 上先失败后重跑通过：以最新一次为准 ==="
{ check_runs_json "$APP_ID:completed:failure" "$APP_ID:completed:success"; echo; } > "$tmp/r5"
run_case "$tmp/r5"
[[ "$(cat "$FAKE_DIR/rc")" == 0 && -s "$FAKE_DIR/merges" ]] && pass "取最新一次结果并合并" || fail "未按最新结果处理：$(cat "$FAKE_DIR/out")"

echo "=== 6. 检查被取消：不合并 ==="
{ check_runs_json "$APP_ID:completed:cancelled"; echo; } > "$tmp/r6"
run_case "$tmp/r6"
[[ ! -s "$FAKE_DIR/merges" && "$(cat "$FAKE_DIR/rc")" != 0 ]] && pass "取消视为未通过" || fail "取消时不应合并"
grep -q "未通过" "$FAKE_DIR/out" && ! grep -q "超时" "$FAKE_DIR/out" && pass "立即判未通过而非等到超时" || fail "取消应立即失败：$(cat "$FAKE_DIR/out")"

echo "=== 7a. 读取检查返回 403（如缺 checks 权限）：立即失败并带出原始错误，不误报超时 ==="
{ echo "ERR:gh: Resource not accessible by integration (HTTP 403)"; } > "$tmp/r7"
CASE_TIMEOUT=30 run_case "$tmp/r7"
[[ "$(cat "$FAKE_DIR/rc")" != 0 && ! -s "$FAKE_DIR/merges" ]] && pass "不合并且失败" || fail "403 时不应合并"
grep -q "HTTP 403" "$FAKE_DIR/out" && ! grep -q "超时" "$FAKE_DIR/out" && pass "报出 403 原因而非超时" || fail "403 被吞：$(cat "$FAKE_DIR/out")"

echo "=== 7b. 暂时性错误（HTTP 502）后恢复：继续重试并在通过后合并 ==="
{ echo "ERR:gh: HTTP 502"; check_runs_json "$APP_ID:completed:success"; echo; } > "$tmp/r8"
run_case "$tmp/r8"
[[ "$(cat "$FAKE_DIR/rc")" == 0 && -s "$FAKE_DIR/merges" ]] && pass "5xx 可重试，恢复后合并" || fail "5xx 不应直接失败：$(cat "$FAKE_DIR/out")"

echo "=== 7d. 限流（429，及以 403 返回的 rate limit）：继续重试，恢复后合并 ==="
{ echo "ERR:gh: HTTP 429"; echo "ERR:gh: API rate limit exceeded for installation ID 1 (HTTP 403)"; check_runs_json "$APP_ID:completed:success"; echo; } > "$tmp/r10"
run_case "$tmp/r10"
[[ "$(cat "$FAKE_DIR/rc")" == 0 && -s "$FAKE_DIR/merges" ]] && pass "限流可重试，恢复后合并" || fail "限流不应立即失败：$(cat "$FAKE_DIR/out")"

echo "=== 7c. 持续 5xx：重试至超时，不合并，超时信息带出最后错误 ==="
{ echo "ERR:gh: Service Unavailable (HTTP 503)"; } > "$tmp/r9"
CASE_TIMEOUT=1 run_case "$tmp/r9"
[[ "$(cat "$FAKE_DIR/rc")" != 0 && ! -s "$FAKE_DIR/merges" ]] && pass "不合并且失败" || fail "持续 5xx 时不应合并"
grep -q "超时" "$FAKE_DIR/out" && grep -q "HTTP 503" "$FAKE_DIR/out" && pass "超时信息带出最后错误" || fail "缺少最后错误：$(cat "$FAKE_DIR/out")"

echo "=== 7. 脚本中不再出现 gh pr merge --auto ==="
if grep -nE '^[^#]*gh pr merge[^#]*--auto' "$TARGET_SCRIPT"; then fail "仍有 --auto 合并"; else pass "无 --auto 合并"; fi

rm -rf "$tmp"
if [[ "$FAILED" == 0 ]]; then echo "全部通过"; else echo "存在失败"; exit 1; fi
