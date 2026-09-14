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
# ── 用法 ──
#   scripts/ci/verify-sibling-ref-pin.sh              # 检查
#   scripts/ci/verify-sibling-ref-pin.sh --self-test  # 反向自检：门禁必须能变红
set -euo pipefail

SETTINGS="${SETTINGS:-settings.gradle}"
ACTION="${ACTION:-.github/actions/setup-aster-build/action.yml}"

scan() {
  local settings="$1" action="$2"
  python3 - "$settings" "$action" <<'PY'
import re, sys
settings_path, action_path = sys.argv[1], sys.argv[2]

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
PY
}

if [[ "${1:-}" == "--self-test" ]]; then
  # ★反向自检：只断言「当前一致」的门禁，在解析器与文件结构脱节时会恒绿。
  #   这里注入一处不一致，要求门禁确实 exit 1。
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

  echo "── 自检 1/2：当前仓库状态必须通过 ──"
  scan "$SETTINGS" "$ACTION"

  echo "── 自检 2/2：注入版本不一致必须被抓到 ──"
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
  rc=0
  out=$(scan "$SETTINGS" "$tmp/action.yml" 2>&1) || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "✗ 自检失败：注入了版本不一致却没报错，门禁形同虚设" >&2
    echo "$out" >&2
    exit 1
  fi
  echo "$out" | sed 's/^/    /'
  echo "✓ 自检通过：门禁在不一致时确实变红（exit=${rc}）"
  exit 0
fi

scan "$SETTINGS" "$ACTION"
