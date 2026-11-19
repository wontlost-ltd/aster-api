#!/usr/bin/env bash
# 门禁：setup-aster-build 的 sibling-ref 默认值必须与 settings.gradle 的
# platform catalog 版本一致。
#
# ══ 为什么需要 ════════════════════════════════════════════════════
#
# 本仓的 platform 版本写在**两个**地方：
#
#   settings.gradle                             from('...aster-lang-platform:X')
#   .github/actions/setup-aster-build/action.yml   sibling-ref default: "vX"
#
# 前者声明「要哪个 catalog」，后者决定「checkout 哪个 tag 的兄弟仓源码并
# publishToMavenLocal」。两者必须同版本，否则 mavenLocal 里放的是 vA 的 catalog、
# 而 settings.gradle 要 B —— 直接 `Could not find aster-lang-platform:B`。
#
# 1.0.29 发版实测踩到：生态 bump 只改了 settings.gradle（那是 release-plan 的
# drift gate 唯一检查的位置），sibling-ref 仍停在 v1.0.28。于是 **release train
# 的 12 个制品全部发布成功后，最后一步 aster-api 部署失败**——代价是发一半的车。
#
# drift gate 查不到这里：它只认 settings 的 pin，不知道本仓 CI 还有第二个版本源。
#
# ══ 引擎下限（ADR 0060 §5.5 第 5 条）══════════════════════════════
#
# cloud 放宽别名规则 v2（汉字单词结构词别名）之后，已存的这类版本只有 1.0.52 起的
# 引擎能复验通过；aster-api 回退到更低的 pin，这些版本执行时一律 fail-closed。
# 因此两处一致之外，pin 还不得低于 MIN_ENGINE_VERSION。下限写成常量而不是环境
# 变量：门禁不给调用方留关掉它的开关。集群层面的镜像回滚不经过 CI，只能靠 runbook。
#
# ── 用法 ──
#   scripts/ci/verify-sibling-ref-pin.sh              # 检查
#   scripts/ci/verify-sibling-ref-pin.sh --self-test  # 反向自检：门禁必须能变红
set -euo pipefail

# 引擎 pin 下限：低于它的 pin 一律拒绝（见文件头「引擎下限」）
MIN_ENGINE_VERSION="1.0.52"

SETTINGS="${SETTINGS:-settings.gradle}"
ACTION="${ACTION:-.github/actions/setup-aster-build/action.yml}"

scan() {
  local settings="$1" action="$2"
  python3 - "$settings" "$action" "$MIN_ENGINE_VERSION" <<'PY'
import re, sys
settings_path, action_path, min_ver = sys.argv[1], sys.argv[2], sys.argv[3]

s = open(settings_path, encoding='utf-8').read()
m = re.search(r"aster-lang-platform:(\d+\.\d+\.\d+)", s)
if not m:
    print(f"!! 未能从 {settings_path} 解析出 platform 版本 —— "
          f"锚点可能被改动，拒绝放行", file=sys.stderr)
    sys.exit(2)
settings_ver = m.group(1)

a = open(action_path, encoding='utf-8').read()
# 只取 sibling-ref 段内的 default，避免撞上其它输入的 default
blk = re.search(r'^  sibling-ref:\s*$(.*?)(?=^  \S|\Z)', a, re.S | re.M)
if not blk:
    print(f"!! 未在 {action_path} 找到 sibling-ref 输入 —— 拒绝放行", file=sys.stderr)
    sys.exit(2)
d = re.search(r'default:\s*"v?(\d+\.\d+\.\d+)"', blk.group(1))
if not d:
    print(f"!! sibling-ref 段内未找到形如 default: \"vX.Y.Z\" 的默认值 —— "
          f"拒绝放行", file=sys.stderr)
    sys.exit(2)
action_ver = d.group(1)

print(f"settings.gradle platform={settings_ver}  sibling-ref default=v{action_ver}")
if settings_ver != action_ver:
    print(f"::error::版本不一致：settings.gradle 要 catalog {settings_ver}，"
          f"但 sibling-ref 默认 checkout v{action_ver} 的兄弟仓源码。"
          f"mavenLocal 里将没有 {settings_ver} 的 catalog，构建必然失败。"
          f"两处必须同时 bump。", file=sys.stderr)
    sys.exit(1)
print("✓ 两处 platform 版本一致")

def as_tuple(v):
    return tuple(int(x) for x in v.split("."))

if as_tuple(settings_ver) < as_tuple(min_ver):
    print(f"::error::引擎 pin {settings_ver} 低于下限 {min_ver}（ADR 0060 §5.5 第 5 条）："
          f"汉字单词别名版本只能由 {min_ver} 起的引擎复验，回退会让这些版本执行失败。"
          f"pin 不得降到下限以下。", file=sys.stderr)
    sys.exit(1)
print(f"✓ 引擎 pin 不低于下限 {min_ver}")
PY
}

if [[ "${1:-}" == "--self-test" ]]; then
  # ★反向自检：只断言「当前一致」的门禁，在解析器与文件结构脱节时会恒绿。
  #   这里注入一处不一致，要求门禁确实 exit 1。
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

  echo "── 自检 1/3：当前仓库状态必须通过 ──"
  scan "$SETTINGS" "$ACTION"

  echo "── 自检 2/3：注入版本不一致必须被抓到 ──"
  cp "$ACTION" "$tmp/action.yml"
  python3 - "$tmp/action.yml" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
m = re.search(r'(default:\s*")v(\d+)\.(\d+)\.(\d+)(")', s)
assert m, '!! 自检锚点未命中，变异未落地'
# 把 patch 位 +1，制造与 settings.gradle 的不一致
bumped = f'{m.group(1)}v{m.group(2)}.{m.group(3)}.{int(m.group(4)) + 1}{m.group(5)}'
open(p, 'w', encoding='utf-8').write(s[:m.start()] + bumped + s[m.end():])
PY
  # ★注入用例的输出必须整体捕获、断言后**中和**再回显：runner 会 trim 行首空白再识别
  #   workflow command，原样（哪怕缩进）回显 `::error::` 都会在 PR 上生成一条假的 error
  #   注解（#527 上的「v1.0.48 版本不一致」即此）。只有真实不一致才允许发 ::error::。
  rc=0
  out=$(scan "$SETTINGS" "$tmp/action.yml" 2>&1) || rc=$?
  # 必须是「检出不一致」（exit 1 且带不一致注解），exit 2 的解析失败不算抓到
  if [[ $rc -ne 1 || "$out" != *"::error::版本不一致"* ]]; then
    echo "✗ 自检失败：注入了版本不一致，门禁未按预期报不一致（exit=${rc}），门禁形同虚设" >&2
    echo "$out" | sed -E 's/^[[:space:]]*::/[::]/; s/^/    /' >&2
    exit 1
  fi
  echo "$out" | sed -E 's/^[[:space:]]*::error::/[注入用例，预期报错] /; s/^/    /'
  echo "✓ caught：注入的版本不一致被门禁抓到（exit=${rc}，注解已抑制）"

  echo "── 自检 3/3：两处一致但低于引擎下限必须被抓到 ──"
  # 两处同时改成「下限的 patch 位 -1」：一致性检查通过，只剩下限能拦住
  below=$(python3 -c 'import sys; a,b,c=sys.argv[1].split("."); print(f"{a}.{b}.{int(c)-1}")' "$MIN_ENGINE_VERSION")
  python3 - "$SETTINGS" "$ACTION" "$tmp/settings.below" "$tmp/action.below" "$below" <<'PY'
import re, sys
src_s, src_a, dst_s, dst_a, below = sys.argv[1:6]
s = open(src_s, encoding='utf-8').read()
s, n = re.subn(r"(aster-lang-platform:)\d+\.\d+\.\d+", lambda m: m.group(1) + below, s, count=1)
assert n == 1, '!! 自检锚点未命中（settings），变异未落地'
open(dst_s, 'w', encoding='utf-8').write(s)
a = open(src_a, encoding='utf-8').read()
a, n = re.subn(r'(^  sibling-ref:\s*$.*?default:\s*")v\d+\.\d+\.\d+(")',
               lambda m: m.group(1) + 'v' + below + m.group(2), a, count=1, flags=re.S | re.M)
assert n == 1, '!! 自检锚点未命中（action），变异未落地'
open(dst_a, 'w', encoding='utf-8').write(a)
PY
  rc=0
  out=$(scan "$tmp/settings.below" "$tmp/action.below" 2>&1) || rc=$?
  # 必须是「低于下限」被拒（exit 1 且带下限注解）；不一致或解析失败都不算抓到
  if [[ $rc -ne 1 || "$out" != *"::error::引擎 pin ${below} 低于下限 ${MIN_ENGINE_VERSION}"* ]]; then
    echo "✗ 自检失败：注入了低于下限的 pin ${below}，门禁未按预期拒绝（exit=${rc}），下限形同虚设" >&2
    echo "$out" | sed -E 's/^[[:space:]]*::/[::]/; s/^/    /' >&2
    exit 1
  fi
  echo "$out" | sed -E 's/^[[:space:]]*::error::/[注入用例，预期报错] /; s/^/    /'
  echo "✓ caught：低于下限的 pin ${below} 被门禁抓到（exit=${rc}，注解已抑制）"
  echo "✓ 自检通过：门禁在不一致与低于下限时都确实变红"
  exit 0
fi

scan "$SETTINGS" "$ACTION"
