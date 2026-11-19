#!/usr/bin/env bash
# verify-sibling-ref-pin.sh 的离线测试。无网络，仅需 bash + python3。
#
# 重点锁住一件事：--self-test 的「注入不一致」用例**不得**向 stdout/stderr 输出
# GitHub workflow command（::error:: / ::warning:: …）。runner 会先 trim 行首空白再
# 识别命令，所以哪怕缩进过的 `    ::error::` 也会在 PR 上生成一条真实的 error 注解
# ——#527 上那条「版本不一致 v1.0.48」就是自检注入的假错误，误导了人工判断。
# 只有真实的不一致才允许发 ::error::。
#
# 用法：bash scripts/ci/verify-sibling-ref-pin.test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-sibling-ref-pin.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT
FAILED=0
# runner 识别 workflow command 的形状：trim 后以 :: 开头
ANNOTATION_RE='^[[:space:]]*::[A-Za-z-]+( |::)'

# 生成一对夹具：settings.gradle 的 platform 版本 $2、sibling-ref 默认值 $3
fixture() {
  local dir="$ROOT/$1"; mkdir -p "$dir"
  cat > "$dir/settings.gradle" <<EOF
dependencyResolutionManagement {
  versionCatalogs { libs { from('io.aster:aster-lang-platform:$2') } }
}
EOF
  cat > "$dir/action.yml" <<EOF
name: setup-aster-build
inputs:
  java-version:
    default: "25"
  sibling-ref:
    description: 兄弟仓 tag
    default: "v$3"
runs:
  using: composite
  steps: []
EOF
  echo "$dir"
}

# check <名称> <期望退出码> <期望含片段> <是否允许注解 yes|no> <夹具目录> [脚本参数...]
check() {
  local name="$1" want_code="$2" want_text="$3" allow_ann="$4" dir="$5"; shift 5
  local out code ok=1
  set +e
  out="$(SETTINGS="$dir/settings.gradle" ACTION="$dir/action.yml" bash "$SCRIPT" "$@" 2>&1)"
  code=$?
  set -e
  [ "$code" = "$want_code" ] || ok=0
  [[ "$out" == *"$want_text"* ]] || ok=0
  if [ "$allow_ann" = no ] && grep -qE "$ANNOTATION_RE" <<<"$out"; then ok=0; fi
  if [ "$allow_ann" = yes ] && ! grep -qE "$ANNOTATION_RE" <<<"$out"; then ok=0; fi
  if [ "$ok" = 1 ]; then
    echo "  ✓ ${name}"
  else
    echo "  ✗ ${name}（期望 exit=${want_code} 含「${want_text}」注解=${allow_ann}，实际 exit=${code}）"
    # 回显失败输出时同样中和行首 ::，测试自身也不得制造假注解
    printf '%s\n' "$out" | sed -E 's/^[[:space:]]*::/[::]/; s/^/      /'
    FAILED=1
  fi
}

same="$(fixture same 1.0.52 1.0.52)"
drift="$(fixture drift 1.0.53 1.0.52)"
# 引擎下限 1.0.52（ADR 0060 §5.5 第 5 条）：两处一致但低于下限
below="$(fixture below 1.0.51 1.0.51)"
# 下限按数值比较：1.0.100 > 1.0.52（按字符串比会被误拒）
numeric="$(fixture numeric 1.0.100 1.0.100)"
major="$(fixture major 2.0.0 2.0.0)"

echo "verify-sibling-ref-pin:"
check "一致：通过"                                 0 "✓ 两处 platform 版本一致" no  "$same"
check "★真实不一致：exit 1 并发 ::error:: 注解"     1 "::error::版本不一致"     yes "$drift"
check "★--self-test：注入用例被抓到，打印 ✓ caught" 0 "✓ caught"                no  "$same" --self-test
check "★--self-test：全程不发任何 workflow 注解"    0 "✓ 自检通过"              no  "$same" --self-test
check "--self-test 但仓库本身不一致：真实错误照常注解" 1 "::error::版本不一致"     yes "$drift" --self-test
check "★低于引擎下限：exit 1 并发下限注解"          1 "::error::引擎 pin 1.0.51 低于下限 1.0.52" yes "$below"
check "--self-test 但仓库本身低于下限：真实错误照常注解" 1 "::error::引擎 pin 1.0.51 低于下限 1.0.52" yes "$below" --self-test
check "★--self-test：下限注入用例被抓到"            0 "✓ caught：低于下限的 pin 1.0.51" no "$same" --self-test
check "下限按数值比较：1.0.100 通过"                0 "✓ 引擎 pin 不低于下限 1.0.52" no "$numeric"
check "主版本升级：2.0.0 通过"                      0 "✓ 引擎 pin 不低于下限 1.0.52" no "$major"

if [ "$FAILED" = 0 ]; then
  echo "全部通过"
else
  echo "存在失败"
  exit 1
fi
