#!/usr/bin/env bash
# open-image-pin-pr —— 源仓 CI 用：开/更新 image-pin PR 到 k3s（统一 digest pin A′ Phase 2）
#
# 见 wontlost-ltd/k3s#4。Codex 对抗式审查修正（81→）：token 用 extraheader 不进 .git/config；
# force-with-lease 用远端实际 SHA 作 lease 基线；gh pr list --state open 判存在；无 diff+无 open
# PR 时干净退出；校验目标 entry 唯一存在。
#
# 用法：open-image-pin-pr.sh <IMAGE> <BRANCH>
#   IMAGE  = 如 docker.io/wontlost/aster-api（必须在 k3s image-lock 里唯一存在）
#   BRANCH = 固定分支名，如 image-pin/aster-api
# 环境变量（必需）：
#   GH_TOKEN     k3s-scoped installation token（contents+PR write）
#   DIGEST       本次已验签 manifest-list digest（sha256:...）
#   SOURCE_SHA   github.sha
#   RUN_ID       github.run_id
# 环境变量（可选）：
#   K3S_REPO     默认 wontlost-ltd/k3s
#   LOCK_PATH    默认 apps/aster-lang/cloud/image-lock.yaml
#   YQ_VERSION   默认 v4.44.3
#   YQ_SHA256    yq_linux_amd64 的 sha256（提供则校验；强烈建议在 workflow 传入）
#   ENV_PATCH_PATH      第三写目标（Fork A）：待 patch 的 Deployment manifest 路径，如
#                       apps/aster-lang/runner/deployment.yaml。★不设则第三目标完全跳过
#                       （零改动铁律：现有 aster-api/migrate/cloud pin 路径不受影响）。
#   ENV_PATCH_SELECTOR  配合 ENV_PATCH_PATH：yq 选择器，需命中恰 1 个 env 项（含 .value 子键），
#                       如 '.spec.template.spec.containers[0].env[] | select(.name == "RUNNER_IMAGE_DIGEST")'
set -euo pipefail

# ── 顶部：新增可选第三写目标 env（Fork A：patch launcher Deployment 的 RUNNER_IMAGE_DIGEST env）──
# ★零改动铁律：不设 ENV_PATCH_PATH → 第三目标完全跳过，脚本行为等同现状（现有 aster-api/migrate pin 不受影响）。
ENV_PATCH_PATH="${ENV_PATCH_PATH:-}"                # 如 apps/aster-lang/runner/deployment.yaml
ENV_PATCH_SELECTOR="${ENV_PATCH_SELECTOR:-}"        # yq 选择器，选到 env 项（含 .value 子键）

# ── binding-mode 封闭枚举（full B）：选择 pin 的部署真相载体 ──
# ★零改动铁律：默认 kustomization → 现有 aster-api/migrate/launcher/cloud pin 行为不变（双写 image-lock+kustomization）。
#   kustomization：静态部署镜像——写 image-lock + kustomization（+ 可选第三 ENV_PATCH，但见下方互斥）。
#   env：运行时启动镜像（runner）——写 image-lock + ENV_PATCH（RUNNER_IMAGE_DIGEST env），**不写 kustomization**、
#        **跳 kcount gate**（runner 不在 kustomization.images）、**要求 ENV_PATCH_* present**。
PIN_DEPLOY_BINDING="${PIN_DEPLOY_BINDING:-kustomization}"
case "$PIN_DEPLOY_BINDING" in
  kustomization|env) : ;;
  *) echo "::error::PIN_DEPLOY_BINDING 非法值：${PIN_DEPLOY_BINDING}（须 kustomization|env）"; exit 2 ;;
esac

# patch_targets：写 image-lock（验签真相）+ kustomization（部署真相）+ 可选 deployment env（Fork A 第三目标）。
# 参数：$1=digest $2=source_sha $3=run_id。用全局 LOCK_PATH/KUSTOMIZATION_PATH/IMAGE/ENV_PATCH_*。
patch_targets() {
  local digest="$1" source_sha="$2" run_id="$3"
  # (1) image-lock：改本 entry 的 digest/sourceSha/runId（原 L70-74）。
  DIGEST="$digest" SOURCE_SHA="$source_sha" RUN_ID="$run_id" IMAGE="$IMAGE" yq -i '
    (.images[] | select(.image == strenv(IMAGE))).digest    = strenv(DIGEST)  |
    (.images[] | select(.image == strenv(IMAGE))).sourceSha = strenv(SOURCE_SHA) |
    (.images[] | select(.image == strenv(IMAGE))).runId     = strenv(RUN_ID)
  ' "$LOCK_PATH"
  # (2) kustomization：改本镜像 digest（原 L76-78）。★仅 kustomization 模式写；env 模式跳过
  #     （runner 不在 kustomization.images，其部署真相是下方 ENV_PATCH 的 RUNNER_IMAGE_DIGEST env）。
  if [[ "$PIN_DEPLOY_BINDING" == "kustomization" ]]; then
    DIGEST="$digest" IMAGE="$IMAGE" yq -i '
      (.images[] | select(.name == strenv(IMAGE))).digest = strenv(DIGEST)
    ' "$KUSTOMIZATION_PATH"
  fi
  # (3) ★可选第三写目标（Fork A）：patch 指定 Deployment env 的 value = digest。
  #     仅当 ENV_PATCH_PATH+ENV_PATCH_SELECTOR 都设时执行；否则完全跳过（零改动）。
  if [[ -n "$ENV_PATCH_PATH" && -n "$ENV_PATCH_SELECTOR" ]]; then
    [[ -f "$ENV_PATCH_PATH" ]] || { echo "::error::ENV_PATCH_PATH 不存在: $ENV_PATCH_PATH"; return 1; }
    # 校验选择器命中恰 1 项（防误 patch 多个 env / 漂移）。
    local n
    n="$(DIGEST="$digest" yq "[${ENV_PATCH_SELECTOR}] | length" "$ENV_PATCH_PATH")"
    [[ "$n" == "1" ]] || { echo "::error::ENV_PATCH_SELECTOR 命中 ${n} 项(需恰 1): $ENV_PATCH_SELECTOR"; return 1; }
    # ★命中项的 .name 必须是 RUNNER_IMAGE_DIGEST（Codex 抓：只校验「命中 1 项」不够——错配的
    #   selector 若恰命中别的 env（有 .value）会 patch 错 env，RUNNER_IMAGE_DIGEST 反而不更新。
    #   虽 k3s verifier 硬编码 RUNNER_IMAGE_DIGEST 会兜底拒（部署真相不一致），但脚本层 fail-fast
    #   给明确错误、避免产出注定被 verifier 拒的 PR（防御纵深 + 免浪费一轮）。env 部署真相载体名固定。
    local matched_name
    matched_name="$(DIGEST="$digest" yq "${ENV_PATCH_SELECTOR} | .name" "$ENV_PATCH_PATH")"
    [[ "$matched_name" == "RUNNER_IMAGE_DIGEST" ]] \
      || { echo "::error::ENV_PATCH_SELECTOR 命中的 env 名=${matched_name}（须 RUNNER_IMAGE_DIGEST）——env 部署真相载体固定为该 env，拒绝 patch 别的 env"; return 1; }
    DIGEST="$digest" yq -i "(${ENV_PATCH_SELECTOR}).value = strenv(DIGEST)" "$ENV_PATCH_PATH"
    echo "第三写目标已 patch: ${ENV_PATCH_PATH} ← ${digest}（env=RUNNER_IMAGE_DIGEST）"
  fi
}

# ── --source-only：供单测 source 本脚本只取函数定义，不跑主流程 ──
if [[ "${1:-}" == "--source-only" ]]; then return 0 2>/dev/null || exit 0; fi

# ★成对 XOR fail-closed（Codex 抓）：ENV_PATCH_PATH 与 ENV_PATCH_SELECTOR 必须**同时为空或同时非空**。
#   只设其一=配置漂移——会让 runner image-lock/kustomization 已更新而 launcher RUNNER_IMAGE_DIGEST env
#   未更新（digest 不一致）。故只设其一时**在任何写入前**立即失败，绝不静默跳过第三目标。
#   （放在 --source-only guard 之后：sourcing 取函数不触发；主流程首先校验。）
if { [[ -n "$ENV_PATCH_PATH" ]] && [[ -z "$ENV_PATCH_SELECTOR" ]]; } || { [[ -z "$ENV_PATCH_PATH" ]] && [[ -n "$ENV_PATCH_SELECTOR" ]]; }; then
  echo "::error::ENV_PATCH_PATH 与 ENV_PATCH_SELECTOR 必须成对设置（同时空或同时非空）——只设其一=配置漂移，拒绝"
  exit 2
fi

# ── binding-mode 互斥 fail-closed（full B，叠加在成对 XOR 之上）──
# env 模式：ENV_PATCH_* 必须 present（runner 的部署真相就是 RUNNER_IMAGE_DIGEST env；缺则 pin 不完整）。
# kustomization 模式：禁 ENV_PATCH_*（静态部署镜像的部署真相是 kustomization，混用 env-patch=配置漂移）。
if [[ "$PIN_DEPLOY_BINDING" == "env" ]]; then
  if [[ -z "$ENV_PATCH_PATH" || -z "$ENV_PATCH_SELECTOR" ]]; then
    echo "::error::PIN_DEPLOY_BINDING=env 但 ENV_PATCH_PATH/ENV_PATCH_SELECTOR 未成对设置——env 模式的部署真相是 RUNNER_IMAGE_DIGEST env，必须提供，拒绝"
    exit 2
  fi
else
  # kustomization 模式禁 ENV_PATCH_*（避免静态镜像误 patch 某 Deployment env）。
  if [[ -n "$ENV_PATCH_PATH" || -n "$ENV_PATCH_SELECTOR" ]]; then
    echo "::error::PIN_DEPLOY_BINDING=kustomization（默认）不得设 ENV_PATCH_*（env-patch 仅用于 PIN_DEPLOY_BINDING=env），拒绝"
    exit 2
  fi
fi

IMAGE="${1:?usage: open-image-pin-pr.sh <IMAGE> <BRANCH>}"
BRANCH="${2:?usage: open-image-pin-pr.sh <IMAGE> <BRANCH>}"
K3S_REPO="${K3S_REPO:-wontlost-ltd/k3s}"
LOCK_PATH="${LOCK_PATH:-apps/aster-lang/cloud/image-lock.yaml}"
KUSTOMIZATION_PATH="${KUSTOMIZATION_PATH:-apps/aster-lang/cloud/kustomization.yaml}"
YQ_VERSION="${YQ_VERSION:-v4.44.3}"

: "${GH_TOKEN:?需要 GH_TOKEN（k3s-scoped token）}"
: "${DIGEST:?需要 DIGEST}"
: "${SOURCE_SHA:?需要 SOURCE_SHA}"
: "${RUN_ID:?需要 RUN_ID}"
[[ "$DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "::error::DIGEST 形状非法：$DIGEST"; exit 1; }

# 向 CI 输出「本次是否需要等待迁移应用 + 具体 PR number」（Codex 审查必修:下游 Wait step
# 按本次明确的 pr_number 等待,不从历史 --state all 反推）。本地无 GITHUB_OUTPUT 时 no-op。
emit() { [[ -n "${GITHUB_OUTPUT:-}" ]] && printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT" || true; }

# ── 安装 yq（可选 checksum 校验）──
if ! command -v yq >/dev/null; then
  curl -fsSL -o /tmp/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64"
  if [[ -n "${YQ_SHA256:-}" ]]; then
    echo "${YQ_SHA256}  /tmp/yq" | sha256sum -c - || { echo "::error::yq checksum 校验失败"; exit 1; }
  fi
  sudo install -m 0755 /tmp/yq /usr/local/bin/yq
fi

# ── clone k3s：token 走 extraheader，不写进 remote URL（不落 .git/config）──
# ★ GitHub App installation token（ghs_*）over git HTTPS 用 **basic** auth（base64
#   x-access-token:TOKEN），非 bearer——bearer 不被 github.com git 端点接受，会退化成
#   提示 Username 而在 CI 失败（实测 fatal: could not read Username）。对齐 actions/checkout。
AUTH="AUTHORIZATION: basic $(printf 'x-access-token:%s' "${GH_TOKEN}" | base64 | tr -d '\n')"
git -c "http.https://github.com/.extraheader=${AUTH}" \
  clone --depth=1 "https://github.com/${K3S_REPO}.git" k3s
cd k3s
# 后续所有 remote 操作都带 extraheader，保持 token 不落盘。
git_c() { git -c "http.https://github.com/.extraheader=${AUTH}" "$@"; }

# ── 校验目标 entry 在 image-lock 中唯一存在 ──
count="$(IMAGE="$IMAGE" yq '.images | map(select(.image == strenv(IMAGE))) | length' "$LOCK_PATH")"
[[ "$count" == "1" ]] || { echo "::error::image-lock 中 ${IMAGE} 的 entry 数=${count} (需恰好 1)"; exit 1; }

# Phase 3 keystone：kustomization 里本镜像也必须唯一存在（双写目标）。★仅 kustomization 模式校验；
#   env 模式跳过（runner 是 env-bound，不在 kustomization.images，kcount 必为 0——跳过是正确非绕过）。
if [[ "$PIN_DEPLOY_BINDING" == "kustomization" ]]; then
  kcount="$(IMAGE="$IMAGE" yq '.images | map(select(.name == strenv(IMAGE))) | length' "$KUSTOMIZATION_PATH")"
  [[ "$kcount" == "1" ]] || { echo "::error::kustomization 中 ${IMAGE} 的 images entry 数=${kcount} (需恰好 1)"; exit 1; }
fi

# ── 从最新 origin/main 重建固定分支，写目标 entry（image-lock 验签真相 + kustomization 部署真相 +
#    可选第三写目标——见顶部 patch_targets()）──
git checkout -B "$BRANCH" origin/main
patch_targets "$DIGEST" "$SOURCE_SHA" "$RUN_ID"

open_pr_number() {
  gh pr list -R "$K3S_REPO" --head "$BRANCH" --base main --state open \
    --json number --jq '.[0].number // empty'
}

# ★env 模式：diff-check 不含 KUSTOMIZATION_PATH（未写它，不该纳入变更判定）；含 image-lock + ENV_PATCH。
#   kustomization 模式：含 image-lock + kustomization（+ 可选 ENV_PATCH，但互斥已禁 → 恒不含）。
if [[ "$PIN_DEPLOY_BINDING" == "env" ]]; then
  DIFF_TARGETS=("$LOCK_PATH" "$ENV_PATCH_PATH")
else
  DIFF_TARGETS=("$LOCK_PATH" "$KUSTOMIZATION_PATH")
fi
if git diff --quiet -- "${DIFF_TARGETS[@]}"; then
  # 无变更：main 已是本 digest。若恰有 open PR 则确保 auto-merge,否则干净退出（不 create）。
  pr="$(open_pr_number)"
  if [[ -n "$pr" ]]; then
    echo "image-lock 已最新,复用 open PR #$pr 确保 auto-merge"
    gh pr merge "$pr" -R "$K3S_REPO" --auto --squash --delete-branch
    emit wait_required true; emit pr_number "$pr"
  else
    echo "image-lock 已最新且无 open PR → 无需开 PR,退出"
    emit wait_required false; emit pr_number ""
  fi
  exit 0
fi

git config user.name  "aster-image-pin[bot]"
git config user.email "301590099+aster-image-pin[bot]@users.noreply.github.com"
# ★env 模式：git add image-lock + deployment(ENV_PATCH)，不 add kustomization（未写）；
#   kustomization 模式：git add image-lock + kustomization（ENV_PATCH 互斥已禁 → 恒不 add）。
if [[ "$PIN_DEPLOY_BINDING" == "env" ]]; then
  git add "$LOCK_PATH" "$ENV_PATCH_PATH"
else
  git add "$LOCK_PATH" "$KUSTOMIZATION_PATH"
fi
git commit -m "chore(image-pin): ${IMAGE##*/} → ${DIGEST} (sha ${SOURCE_SHA})"

# ── force-with-lease 用远端实际 SHA 作基线（防同 App 并发 run 互相覆盖）──
remote_sha="$(git_c ls-remote --heads origin "$BRANCH" | cut -f1 || true)"
if [[ -n "$remote_sha" ]]; then
  git_c push --force-with-lease="${BRANCH}:${remote_sha}" origin "$BRANCH"
else
  git_c push origin "$BRANCH"
fi

# ── 只查 open PR 决定复用/创建；create/merge 用 --match-head-commit 锁定本 run 的 head ──
head_sha="$(git rev-parse HEAD)"
pr="$(open_pr_number)"
if [[ -z "$pr" ]]; then
  # ★ 不在 create 时带 --label（label 缺失会让 create 整体失败，实测 could not add label）。
  #   先建 PR，再单独加 label（非致命：label 是 ruleset auto-merge 门控之一，缺则由人工/后续补）。
  gh pr create -R "$K3S_REPO" --base main --head "$BRANCH" \
    --title "chore(image-pin): ${IMAGE##*/} digest bump" \
    --body $'自动开启（源仓 CI）。\n\nimage: '"${IMAGE}"$'\ndigest: '"${DIGEST}"$'\nsourceSha: '"${SOURCE_SHA}"$'\nrunId: '"${RUN_ID}"$'\n\nstale/红：等待最新源仓 CI 重开，或对 latest main 手动 rerun（不允许旧 SHA 绕过 freshness）。'
  pr="$(open_pr_number)"
  gh pr edit "$pr" -R "$K3S_REPO" --add-label image-pin \
    || echo "::warning::加 image-pin label 失败（label 是否存在？）——PR 已建，label 需人工补"
else
  echo "复用 open PR #$pr"
fi
# enable auto-merge 失败必须 fail-loud（PR 开着不合＝发布链断）。
#
# ★但要先等 GitHub 侧 PR 的 headRefOid 追上刚 force-push 的 commit：
#   实测 push 完成(47.38s) → enable auto-merge(48.62s) 仅隔 1.2 秒，
#   GitHub 尚未刷新 PR head，于是报
#     Failed to add PR #N: expected head oid does not match the current head oid
#   （enablePullRequestAutoMerge）。PR 本身已正确开好/更新，纯属复制延迟，
#   但 job 会因此变红 —— 是**假红**，会掩盖真实的发布链故障。
#
#   故轮询等待 headRefOid == 本地 head_sha 再 enable。
#   ★不去掉 --match-head-commit：它是防「auto-merge 意外合并了别人后推的
#     commit」的关键防线，去掉换来的"稳定"是拿安全性换的。
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  remote_oid="$(gh pr view "$pr" -R "$K3S_REPO" --json headRefOid --jq .headRefOid 2>/dev/null || true)"
  [[ "$remote_oid" == "$head_sha" ]] && break
  echo "  等待 PR #${pr} head 刷新（尝试 ${attempt}/10；远端=${remote_oid:-?} 期望=${head_sha}）"
  sleep 3
done
if [[ "$remote_oid" != "$head_sha" ]]; then
  echo "::error::PR #${pr} 的 head 在 30s 内未刷新到 ${head_sha}（远端=${remote_oid:-?}）"
  echo "::error::  不降级为无 --match-head-commit 的 enable —— 那会放任 auto-merge 合并非本 run 的 commit。"
  exit 1
fi
gh pr merge "$pr" -R "$K3S_REPO" --auto --squash --delete-branch --match-head-commit "$head_sha"
echo "image-pin PR #${pr} 就绪 (auto-merge enabled, head=${head_sha})"
# 本次确有 digest 变更 + 开/复用了 PR → 下游必须等待迁移应用。
emit wait_required true; emit pr_number "$pr"
