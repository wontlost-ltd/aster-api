#!/usr/bin/env bash
# 构建复用键（ADR 0053 §4/§5）：aster-api 的 quarkus-app / 镜像只由下列输入决定——
#   aster-api 自身 commit + 7 个兄弟仓（composite includeBuild 的源码）的 commit + JDK 主版本。
# 任一不同 → key 不同 → 不复用、重新 build&test。相同 → 已验证过（测试通过后才上传 / 推送）
# 的产物可直接复用：quarkus-app-<key> 制品，或镜像 tag build-<key>（按 digest 晋升）。
#
# 两种模式算出的 key 必须一致（有测试）：
#   remote  不检出源码，用 git ls-remote 解析兄弟仓 sibling-ref tag 指向的 commit（reuse-probe 用，几秒）
#   local   读已检出兄弟仓的 HEAD（build-and-test 用：断言「实际构建的源码」就是 key 声明的那份）
#
# 兄弟仓清单与 sibling-ref 缺省值都从 setup-aster-build/action.yml 读，不在此重复维护。
#
# env：
#   ACTION_YML     缺省 .github/actions/setup-aster-build/action.yml
#   SIBLING_REF    缺省取 action.yml 里 sibling-ref 的 default
#   API_SHA        缺省 git rev-parse HEAD
#   JAVA_VERSION   缺省取 action.yml 里 java-version 的 default（构建实际用的 JDK；不在此硬编码）
#   SIBLINGS_ROOT  local 模式：兄弟仓检出的父目录（缺省 ..）
#   REMOTE_BASE    remote 模式：仓库 URL 前缀（缺省 https://github.com；测试用 file://）
#   GH_TOKEN       remote 模式可选：私有兄弟仓时用（只经 http.extraHeader，不进 URL）
#   MANIFEST_OUT   可选：把 key 的全部输入写到该文件（审计 / 溯源）
# 输出：stdout 一行 32 位 hex key；失败非零退出（fail-closed：拿不准就不给 key = 不复用）。
set -euo pipefail

MODE="${1:?usage: build-reuse-key.sh remote|local}"
ACTION_YML="${ACTION_YML:-.github/actions/setup-aster-build/action.yml}"
SIBLINGS_ROOT="${SIBLINGS_ROOT:-..}"
REMOTE_BASE="${REMOTE_BASE:-https://github.com}"
API_SHA="${API_SHA:-$(git rev-parse HEAD)}"

# sibling-ref 缺省：inputs.sibling-ref 块里第一条 default。
if [ -z "${SIBLING_REF:-}" ]; then
  SIBLING_REF=$(awk '/^  sibling-ref:/{f=1} f&&/default:/{gsub(/["'"'"' ]/,"",$2); print $2; exit}' "$ACTION_YML")
fi
[ -n "$SIBLING_REF" ] || { echo "无法从 $ACTION_YML 解析 sibling-ref" >&2; exit 1; }
if [ -z "${JAVA_VERSION:-}" ]; then
  JAVA_VERSION=$(awk '/^  java-version:/{f=1} f&&/default:/{gsub(/["'"'"' ]/,"",$2); print $2; exit}' "$ACTION_YML")
fi
[ -n "$JAVA_VERSION" ] || { echo "无法从 $ACTION_YML 解析 java-version" >&2; exit 1; }

SIBLINGS=$(sed -n 's#^ *repository: *\([A-Za-z0-9_.-]*/[A-Za-z0-9_.-]*\) *$#\1#p' "$ACTION_YML" | sort -u)
[ -n "$SIBLINGS" ] || { echo "无法从 $ACTION_YML 解析兄弟仓清单" >&2; exit 1; }

auth=()
[ -n "${GH_TOKEN:-}" ] && auth=(-c "http.extraHeader=Authorization: Basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')")

sha_of() {  # owner/name
  local full="$1" name="${1#*/}" out sha
  if [ "$MODE" = local ]; then
    git -C "$SIBLINGS_ROOT/$name" rev-parse HEAD
  else
    # annotated tag 取 peel 后的 ^{}（commit），lightweight tag 直接是 commit。
    out=$(git ${auth[@]+"${auth[@]}"} ls-remote "$REMOTE_BASE/$full.git" "refs/tags/$SIBLING_REF" "refs/tags/$SIBLING_REF^{}")
    sha=$(awk '$2 ~ /\^\{\}$/ {print $1}' <<<"$out")
    [ -n "$sha" ] || sha=$(awk 'NR==1{print $1}' <<<"$out")
    [ -n "$sha" ] || { echo "$full 无 tag $SIBLING_REF" >&2; return 1; }
    echo "$sha"
  fi
}

manifest=$(
  echo "format 1"
  echo "api $API_SHA"
  echo "java $JAVA_VERSION"
  echo "sibling-ref $SIBLING_REF"
  for s in $SIBLINGS; do echo "${s#*/} $(sha_of "$s")"; done
)
# 子 shell 失败不会自动传出来，逐行校验每个兄弟仓都有 40 位 SHA。
n_sib=$(wc -w <<<"$SIBLINGS" | tr -d ' ')
n_ok=$(grep -cE '^[A-Za-z0-9_.-]+ [0-9a-f]{40}$' <<<"$manifest" || true)
# api 行也匹配该模式，故期望 = 兄弟仓数 + 1。
[ "$n_ok" -eq $((n_sib + 1)) ] || { echo "兄弟仓 commit 解析不全：" >&2; echo "$manifest" >&2; exit 1; }

[ -n "${MANIFEST_OUT:-}" ] && echo "$manifest" >"$MANIFEST_OUT"
if command -v sha256sum >/dev/null; then hash=(sha256sum); else hash=(shasum -a 256); fi
printf '%s\n' "$manifest" | "${hash[@]}" | cut -c1-32
