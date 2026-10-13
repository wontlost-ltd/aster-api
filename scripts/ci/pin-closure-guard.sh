#!/usr/bin/env bash
# 部署闭环自检：本次「该 pin」时，要求 image-pin PR 真的开出来了（platform#82，#341）。
#
# 只看 job 级结论不够：image-pin-pr 的步骤可以被 if: 静默跳过而 job 仍是 success
# （Final stale gate 发现 main 已前进时就是这样），那时一个 PR 都没开、集群仍跑旧版本。
# 所以这里看 image-pin-pr 导出的 pinned——它只在「开 PR」那一步真的成功时为 true。
#
# 输入（环境变量，由 deploy.yml 的 pin-closure-guard job 传入）：
#   EVENT / REF / TRAIN_ID     触发信息
#   STALE                      deploy job 的 stale（仅 push 事件会判定）
#   CLIENT_ID                  vars.IMAGE_PIN_APP_CLIENT_ID（开 PR 用的 App 凭据）
#   DEPLOY_RESULT / PIN_RESULT 两个 job 的结论
#   PINNED                     image-pin-pr 输出：开 PR 步骤成功为 true
#   FINAL_STALE                image-pin-pr 输出：Final stale gate 判定过时为 true
# 退出码：0 闭环成立或按设计不 pin；1 闭环断裂。
set -euo pipefail

echo "event=${EVENT} ref=${REF} trainId='${TRAIN_ID:-}' stale=${STALE:-}"
echo "deploy=${DEPLOY_RESULT} image-pin-pr=${PIN_RESULT} pinned=${PINNED:-} finalStale=${FINAL_STALE:-}"

# deploy 自己没成功时不归本 job 管（它已经红了，别叠加噪音）
if [ "${DEPLOY_RESULT}" != "success" ]; then
  echo "deploy 未成功（${DEPLOY_RESULT}）——闭环判定不适用，跳过"
  exit 0
fi

# 过时提交：deploy 已跳过推镜像，本就不该 pin
if [ "${STALE:-}" = "true" ]; then
  echo "::warning::本次 github.sha 已非 origin/main tip（stale），按设计不开 pin PR。集群不会换到本次镜像，由更新的 commit 的 deploy 接手。"
  exit 0
fi

# ── 本次是否「该 pin」──────────────────────────────────────────
due=false
why=""
if [ "${EVENT}" = "push" ] && [ "${REF}" = "refs/heads/main" ]; then
  due=true; why="push 到 main"
elif [ "${EVENT}" = "workflow_dispatch" ] && [ -n "${TRAIN_ID:-}" ]; then
  due=true; why="发布列车 dispatch (trainId=${TRAIN_ID})"
fi

if [ "${due}" != "true" ]; then
  echo "本次不该 pin（手工无参 dispatch）——集群不换版本属预期"
  exit 0
fi

# 该 pin 却没有凭据：开不出 PR，集群不会换版本——这是断裂，不是「原因正当」（#343）
if [ -z "${CLIENT_ID:-}" ]; then
  echo "::error::部署闭环断裂：本次该 pin（${why}），但未配置 vars.IMAGE_PIN_APP_CLIENT_ID，开不出 pin PR（见 k3s SETUP.md）。"
  exit 1
fi

# ── 该 pin：要求 PR 真的开出来了 ──────────────────────────────
if [ "${PIN_RESULT}" = "success" ] && [ "${PINNED:-}" = "true" ]; then
  echo "✓ 闭环成立：${why} → image-pin PR 已开出"
  exit 0
fi

# 开 PR 前 main 又前进了：push 事件由更新的 commit 的 deploy 接手；列车 dispatch 没有人接手
if [ "${PIN_RESULT}" = "success" ] && [ "${FINAL_STALE:-}" = "true" ] && [ "${EVENT}" = "push" ]; then
  echo "::warning::开 pin PR 前 main 已前进（Final stale gate），本次不 pin，由更新的 commit 的 deploy 接手。"
  exit 0
fi

echo "::error::部署闭环断裂：本次该 pin（${why}），但 image-pin PR 没有开出（image-pin-pr=${PIN_RESULT} pinned=${PINNED:-} finalStale=${FINAL_STALE:-}）。"
echo "::error::镜像已构建并推送，但 k3s image-lock **未更新** —— 集群仍跑旧版本。"
if [ "${PIN_RESULT}" = "skipped" ]; then
  echo "::error::image-pin-pr 被 skipped：它的 if: 条件与本脚本的「该 pin」判定不一致（改一处必改两处）。"
fi
if [ "${FINAL_STALE:-}" = "true" ]; then
  echo "::error::发布列车运行期间 main 已前进，Final stale gate 跳过了开 PR；请重新触发本次列车部署。"
fi
exit 1
