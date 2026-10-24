#!/usr/bin/env bash
# open-image-pin-pr.sh 主流程测试：锁住「调用点」——两条路径都必须先查 verify-image-pin 再合并。
#
# 只测函数挡不住 #506 这类回归：调用点换成不等验签的直接合并，函数测试照样全绿。
# 这里跑完整脚本：真实 git + 本地裸仓库扮演 k3s（url.insteadOf 把 clone/push 指向它），
# 只伪造 gh，并按顺序记录每次调用。需要 git、jq、yq。
# 用法：bash scripts/ci/open-image-pin-pr-flow.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/open-image-pin-pr.sh"
for bin in git jq yq; do command -v "$bin" >/dev/null || { echo "需要 $bin"; exit 1; }; done

FAILED=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILED=1; }

IMAGE="docker.io/wontlost/aster-cloud-migrate"
BRANCH="image-pin/aster-cloud-migrate"
OLD_DIGEST="sha256:$(printf 'a%.0s' {1..64})"
NEW_DIGEST="sha256:$(printf 'b%.0s' {1..64})"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

# 建 k3s 裸仓库：main 上 pin 的是 digest=$1（sourceSha=$2、runId=$3，缺省为与本次运行不同的种子值）。
make_k3s() {
  local digest="$1" src="${2:-seed}" run="${3:-0}" work="$ROOT/seed"
  rm -rf "$ROOT/k3s.git" "$work"
  git init -q --bare -b main "$ROOT/k3s.git"
  git init -q -b main "$work"
  mkdir -p "$work/apps/aster-lang/cloud"
  cat > "$work/apps/aster-lang/cloud/image-lock.yaml" <<YAML
version: 1
images:
  - image: ${IMAGE}
    digest: ${digest}
    sourceSha: ${src}
    runId: "${run}"
YAML
  cat > "$work/apps/aster-lang/cloud/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
images:
  - name: ${IMAGE}
    digest: ${digest}
YAML
  git -C "$work" add -A
  git -C "$work" -c user.name=t -c user.email=t@t commit -qm seed
  git -C "$work" push -q "$ROOT/k3s.git" main
}

# 假 gh：所有调用按顺序记入 calls.log。
#   OPEN_PR    已开着的 PR 号（空=无）；pr create 后变为 700
#   CHECK      verify-image-pin 的结论（success / failure）
make_fake_gh() {
  mkdir -p "$ROOT/bin"
  cat > "$ROOT/bin/gh" <<'GH'
#!/usr/bin/env bash
set -euo pipefail
state="$FAKE_STATE"
printf '%s\n' "$*" | tr '\n' '~' >> "$state/calls.log"; echo >> "$state/calls.log"
case "$1 $2" in
  "pr list")   cat "$state/open_pr" ;;
  "pr create") echo 700 > "$state/open_pr"; echo "https://example.test/pull/700" ;;
  "pr edit")   : ;;
  "pr view")   git --git-dir="$FAKE_BARE" rev-parse "refs/heads/$FAKE_BRANCH" ;;
  "pr merge")
    # 可选注入：前 N 次合并模拟规则集尚未登记 required check（aster-cloud#546）
    [[ -f "$state/merge_fail_times" ]] || echo "${MERGE_FAIL_TIMES:-0}" > "$state/merge_fail_times"
    if (( $(cat "$state/merge_fail_times") > 0 )); then
      echo $(( $(cat "$state/merge_fail_times") - 1 )) > "$state/merge_fail_times"
      echo 'GraphQL: Required status check "verify-image-pin" is expected. (mergePullRequest)' >&2; exit 1
    fi
    : ;;
  api\ *)
    jq_expr=""; while [[ $# -gt 0 ]]; do [[ "$1" == "--jq" ]] && { jq_expr="$2"; break; }; shift; done
    printf '{"check_runs":[{"name":"verify-image-pin","app":{"id":4253193},"status":"completed","conclusion":"%s","started_at":"2026-10-12T10:00:00Z"}]}' \
      "$(cat "$state/check")" | jq -r "$jq_expr" ;;
  *) echo "unexpected gh call: $*" >&2; exit 2 ;;
esac
GH
  chmod +x "$ROOT/bin/gh"
}

# 跑一次脚本。参数：已开 PR 号（可空）、验签结论。
run_script() {
  local open_pr="$1" check="$2"
  export FAKE_STATE="$ROOT/state"; rm -rf "$FAKE_STATE"; mkdir -p "$FAKE_STATE"
  echo "$open_pr" > "$FAKE_STATE/open_pr"; echo "$check" > "$FAKE_STATE/check"; : > "$FAKE_STATE/calls.log"
  export FAKE_BARE="$ROOT/k3s.git" FAKE_BRANCH="$BRANCH"
  # 把对 GitHub 的 clone/push 重定向到本地裸仓库
  printf '[url "%s"]\n\tinsteadOf = https://github.com/wontlost-ltd/k3s.git\n[user]\n\tname = t\n\temail = t@t\n' \
    "$ROOT/k3s.git" > "$ROOT/gitconfig"
  rm -rf "$ROOT/run"; mkdir -p "$ROOT/run"
  (
    cd "$ROOT/run"
    export GIT_CONFIG_GLOBAL="$ROOT/gitconfig" PATH="$ROOT/bin:$PATH"
    export GH_TOKEN=fake DIGEST="$NEW_DIGEST" SOURCE_SHA=cafe RUN_ID=42 GITHUB_OUTPUT="$FAKE_STATE/out"
    export VERIFY_POLL_SECONDS=0 VERIFY_TIMEOUT_SECONDS=2
    bash "$TARGET_SCRIPT" "$IMAGE" "$BRANCH"
  ) > "$FAKE_STATE/stdout" 2>&1 && echo 0 > "$FAKE_STATE/rc" || echo $? > "$FAKE_STATE/rc"
}

calls() { cat "$FAKE_STATE/calls.log"; }
# 找不到时输出空串（不能让 grep 的非零退出码在 set -e 下中断测试、吞掉断言输出）。
line_of() { { grep -n -- "$1" "$FAKE_STATE/calls.log" || true; } | head -1 | cut -d: -f1; }
branch_head() { git --git-dir="$ROOT/k3s.git" rev-parse "refs/heads/$BRANCH"; }

# 断言：先查 check-runs 再合并，合并锁定分支 head，且不用 --auto。
assert_verified_merge() {
  local api merge head
  api="$(line_of 'check-runs')"; merge="$(line_of '^pr merge')"; head="$(branch_head)"
  [[ -n "$api" && -n "$merge" && "$api" -lt "$merge" ]] && pass "先查 verify-image-pin 再合并" \
    || fail "调用顺序不对：$(calls | tr '\n' '|')"
  grep -q -- "commits/${head}/check-runs" "$FAKE_STATE/calls.log" && pass "查询的是分支 head 的检查" \
    || fail "查询的 sha 不是分支 head ${head}"
  grep -q -- "^pr merge .*--match-head-commit ${head}" "$FAKE_STATE/calls.log" && pass "合并锁定分支 head" \
    || fail "合并未锁定 head：$(grep '^pr merge' "$FAKE_STATE/calls.log")"
  grep -q -- "^pr merge .*--auto" "$FAKE_STATE/calls.log" && fail "不应使用 --auto" || pass "未使用 --auto"
}

make_fake_gh

echo "=== A. 有改动、无 open PR：建 PR → 验签 → 合并 ==="
make_k3s "$OLD_DIGEST"; run_script "" success
[[ "$(cat "$FAKE_STATE/rc")" == 0 ]] && pass "返回 0" || fail "返回 $(cat "$FAKE_STATE/rc")：$(tail -5 "$FAKE_STATE/stdout")"
[[ -n "$(line_of '^pr create')" ]] && pass "创建了 PR" || fail "未创建 PR"
assert_verified_merge
[[ "$(git --git-dir="$ROOT/k3s.git" show "refs/heads/$BRANCH:apps/aster-lang/cloud/kustomization.yaml" | yq '.images[0].digest')" == "$NEW_DIGEST" ]] \
  && pass "分支上已写入新 digest" || fail "分支内容不对"

echo "=== B. 有改动、复用 open PR：更新描述 → 验签 → 合并 ==="
make_k3s "$OLD_DIGEST"; run_script 696 success
[[ "$(cat "$FAKE_STATE/rc")" == 0 ]] && pass "返回 0" || fail "返回 $(cat "$FAKE_STATE/rc")：$(tail -5 "$FAKE_STATE/stdout")"
[[ -z "$(line_of '^pr create')" ]] && pass "未重复建 PR" || fail "不应新建 PR"
grep -q -- "^pr edit 696 .*--body .*${NEW_DIGEST}" "$FAKE_STATE/calls.log" && pass "描述更新为本次 digest" \
  || fail "复用时未更新描述：$(calls | tr '\n' '|')"
assert_verified_merge

echo "=== C. 无改动、有 open PR：验签 → 合并该 PR ==="
make_k3s "$NEW_DIGEST" cafe 42
# open PR 的分支是 main 之上的独立提交（pin 文件与 main 相同）：真实 PR 的 head 永远不是 main，
# 这样才能区分「查/合并的是 PR head」与「误用了本地 HEAD 或 main」。
pr_work="$ROOT/prbranch"; rm -rf "$pr_work"
git clone -q "$ROOT/k3s.git" "$pr_work"
echo "note" > "$pr_work/NOTE"
git -C "$pr_work" add NOTE
git -C "$pr_work" -c user.name=t -c user.email=t@t commit -qm "pr-only commit"
git -C "$pr_work" push -q origin "HEAD:refs/heads/$BRANCH"
pr_head="$(git -C "$pr_work" rev-parse HEAD)"; main_sha="$(git --git-dir="$ROOT/k3s.git" rev-parse main)"
run_script 696 success
[[ "$(cat "$FAKE_STATE/rc")" == 0 ]] && pass "返回 0" || fail "返回 $(cat "$FAKE_STATE/rc")：$(tail -5 "$FAKE_STATE/stdout")"
grep -q "复用 open PR #696，等验签通过后合并" "$FAKE_STATE/stdout" && pass "走的是无改动路径" || fail "未走无改动路径：$(tail -3 "$FAKE_STATE/stdout")"
[[ "$pr_head" != "$main_sha" ]] && grep -q -- "commits/${pr_head}/check-runs" "$FAKE_STATE/calls.log" \
  && pass "查询的是 PR head（非 main）的检查" || fail "未按 PR head ${pr_head} 查询：$(calls | tr '\n' '|')"
grep -q -- "^pr merge 696 .*--match-head-commit ${pr_head}" "$FAKE_STATE/calls.log" && pass "合并锁定 PR head" \
  || fail "合并未锁定 PR head：$(grep '^pr merge' "$FAKE_STATE/calls.log")"
[[ -z "$(line_of '^pr create')" ]] && pass "未建 PR" || fail "无改动时不应建 PR"
assert_verified_merge

echo "=== D. 无改动、无 open PR：既不查验签也不合并 ==="
make_k3s "$NEW_DIGEST" cafe 42; run_script "" success
[[ "$(cat "$FAKE_STATE/rc")" == 0 ]] && pass "返回 0" || fail "返回 $(cat "$FAKE_STATE/rc")"
grep -q "image-lock 已最新且无 open PR" "$FAKE_STATE/stdout" && pass "走的是无改动路径" || fail "未走无改动路径"
[[ -z "$(line_of '^pr merge')" && -z "$(line_of 'check-runs')" ]] && pass "无多余调用" || fail "不应合并：$(calls | tr '\n' '|')"

echo "=== G. 验签已过但规则集滞后：首次合并报 is expected，重试后合并成功 ==="
make_k3s "$OLD_DIGEST"
MERGE_FAIL_TIMES=2 MERGE_RETRY_SECONDS=0 run_script "" success && pass "脚本成功" || fail "脚本失败"
[[ "$(grep -c '^pr merge' "$FAKE_STATE/calls.log")" == "3" ]] && pass "合并重试了两次后成功" \
  || fail "合并调用次数异常：$(grep -c '^pr merge' "$FAKE_STATE/calls.log")"

echo "=== E. 有改动、验签失败：不合并，脚本失败 ==="
make_k3s "$OLD_DIGEST"; run_script "" failure
[[ "$(cat "$FAKE_STATE/rc")" != 0 ]] && pass "返回非 0" || fail "验签失败时不应成功"
[[ -z "$(line_of '^pr merge')" ]] && pass "未合并" || fail "验签失败仍合并了"

if [[ "$FAILED" == 0 ]]; then echo "全部通过"; else echo "存在失败"; exit 1; fi
