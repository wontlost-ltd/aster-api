#!/usr/bin/env bash
# ui-messages 漂移的 issue 上报/关闭。
#
# 抽成脚本而非内联进 workflow：正文含反引号与 Markdown，内联到 YAML 里极易被
# shell/YAML 双重转义搞坏（本次首版就踩了这个坑）。脚本可本地直接跑验证。
#
# 用法：report-ui-messages-drift.sh open|close
# 环境：GH_TOKEN（issues:write）、TITLE、DETAIL（open 时的漂移明细 Markdown）
set -euo pipefail

MODE="${1:?usage: report-ui-messages-drift.sh open|close}"
: "${TITLE:?TITLE 未设置}"
REPO="${GITHUB_REPOSITORY:-wontlost-ltd/aster-api}"

# 已存在的同名 open issue（避免每晚刷屏开新 issue）。
existing="$(gh issue list --repo "$REPO" --state open \
  --search "\"$TITLE\" in:title" --json number --jq '.[0].number // empty')"

case "$MODE" in
  open)
    : "${DETAIL:?DETAIL 未设置}"
    body="$(cat <<EOF
aster-api 的 \`src/main/resources/ui-messages/*.json\` 是各语言包 SPI 源的
**classpath 副本**，当前已漂移：

${DETAIL}

## 影响

cloud 取文案是「Workers KV → 后端 \`/api/v1/messages/<locale>\` → 内嵌 npm 包」，
**后端优先**、内嵌只是兜底。后端返回旧集时前端会显示**裸 key**
（例：\`policies.versions.detail.tabs.source\`）。

## 修法

把语言包源**逐字节**复制到 aster-api 对应路径，开 PR 合并并部署
（部署后 image-pin PR 会自动更新 k3s digest，ArgoCD 随后滚动）：

\`\`\`bash
cp aster-lang-locales/locales/en/src/main/resources/ui-messages/en-US.json \\
   aster-api/src/main/resources/ui-messages/en-US.json
# zh/de 同理；hi 源在 aster-lang-hi/src/main/resources/ui-messages/hi-IN.json
\`\`\`

> 本 issue 由 nightly 的 \`ui-messages-drift\` job 自动创建/更新；
> 同步后下次 nightly 会自动关闭。
EOF
)"
    if [ -n "$existing" ]; then
      gh issue comment "$existing" --repo "$REPO" --body "$body"
      echo "已在 issue #${existing} 追加最新漂移明细"
    else
      # label 可能不存在 → 退回无 label 创建（不因缺 label 让守卫失效）。
      gh issue create --repo "$REPO" --title "$TITLE" --body "$body" --label i18n 2>/dev/null \
        || gh issue create --repo "$REPO" --title "$TITLE" --body "$body"
    fi
    ;;
  close)
    if [ -n "$existing" ]; then
      gh issue close "$existing" --repo "$REPO" \
        --comment "四语 classpath 副本已与语言包源逐字节一致，自动关闭。"
      echo "已关闭 issue #${existing}"
    else
      echo "无待关闭的漂移 issue"
    fi
    ;;
  *)
    echo "未知模式：$MODE（应为 open|close）" >&2
    exit 2
    ;;
esac
