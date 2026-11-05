#!/usr/bin/env bash
# build-reuse-key.sh 的测试（ADR 0053 §4/§5）。离线：用本地裸仓模拟兄弟仓。
# 用法：bash scripts/ci/build-reuse-key.test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-reuse-key.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FAILED=0
ok()  { echo "  ✓ $1"; }
bad() { echo "  ✗ $1"; FAILED=1; }

# 假 action.yml：脚本须从这里读兄弟仓清单与 sibling-ref 缺省值（单一事实源）。
cat >"$TMP/action.yml" <<'YML'
inputs:
  sibling-ref:
    default: "v9.9.9"
runs:
  steps:
    - uses: actions/checkout@v7
      with:
        repository: aster-cloud/sib-a
    - uses: actions/checkout@v7
      with:
        repository: aster-cloud/sib-b
YML

# 远端（裸仓）+ 本地检出（工作区），sib-a 用 annotated tag 验证 peel。
mkdir -p "$TMP/remote/aster-cloud" "$TMP/work"
for s in sib-a sib-b; do
  git init -q "$TMP/src-$s"
  git -C "$TMP/src-$s" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init
  if [ "$s" = sib-a ]; then git -C "$TMP/src-$s" -c user.name=t -c user.email=t@t tag -a -m r v9.9.9
  else git -C "$TMP/src-$s" tag v9.9.9; fi
  git clone -q --bare "$TMP/src-$s" "$TMP/remote/aster-cloud/$s.git"
  git clone -q "$TMP/remote/aster-cloud/$s.git" "$TMP/work/$s"
  git -C "$TMP/work/$s" checkout -q v9.9.9
done

run() {  # mode, extra env...
  local mode="$1"; shift
  env ACTION_YML="$TMP/action.yml" REMOTE_BASE="file://$TMP/remote" SIBLINGS_ROOT="$TMP/work" \
    API_SHA=1111111111111111111111111111111111111111 "$@" bash "$SCRIPT" "$mode"
}

echo "build-reuse-key:"
R=$(run remote 2>/dev/null) || true
L=$(run local 2>/dev/null) || true
[[ "$R" =~ ^[0-9a-f]{32}$ ]] && ok "remote 模式输出 32 位 hex key" || bad "remote key 格式不对：'$R'"
[ -n "$R" ] && [ "$R" = "$L" ] && ok "remote（ls-remote tag，含 annotated peel）与 local（检出 HEAD）key 一致" \
  || bad "remote/local key 不一致：$R vs $L"

L2=$(run local API_SHA=2222222222222222222222222222222222222222 2>/dev/null) || true
[ -n "$L2" ] && [ "$L2" != "$L" ] && ok "aster-api SHA 变化 → key 变化" || bad "API_SHA 变化 key 未变"

git -C "$TMP/work/sib-b" -c user.name=t -c user.email=t@t commit -q --allow-empty -m drift
L3=$(run local 2>/dev/null) || true
[ -n "$L3" ] && [ "$L3" != "$L" ] && ok "兄弟仓 commit 变化 → key 变化（防复用错源码的产物）" || bad "兄弟仓变化 key 未变"

if run remote SIBLING_REF=v0.0.0 >/dev/null 2>&1; then bad "tag 不存在时应失败（fail-closed，不得产出 key）"
else ok "兄弟仓 tag 不存在 → 非零退出"; fi

M="$TMP/manifest.txt"
run local MANIFEST_OUT="$M" >/dev/null 2>&1 || true
grep -q '^sib-a [0-9a-f]\{40\}$' "$M" 2>/dev/null && grep -q '^api 1111111111111111111111111111111111111111$' "$M" \
  && ok "MANIFEST_OUT 写出 key 的全部输入（审计/溯源）" || bad "manifest 不完整：$(cat "$M" 2>/dev/null)"

[ "$FAILED" = 0 ] || { echo "build-reuse-key: FAILED"; exit 1; }
echo "build-reuse-key: all passed"
