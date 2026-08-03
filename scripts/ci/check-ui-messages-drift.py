#!/usr/bin/env python3
"""ui-messages classpath 副本 ↔ 语言包源 漂移检测。

背景（2026-08-03 实测踩坑）：cloud 取文案的顺序是
「Workers KV → 后端 /api/v1/messages/<locale> → 内嵌 npm 包」——**后端优先**，
内嵌包只是 fail-open 兜底。而 aster-api 的 src/main/resources/ui-messages/*.json
是各语言包 SPI 源的 classpath 副本。语言包加了 key 只发 npm、忘了同步这里，
后端就仍返回旧集 → 前端显示裸 key（如 policies.versions.detail.tabs.source）。

★仓内已有 UiMessagesClasspathSiblingParityTest 守同一条不变式，但它只在
  **aster-api 有 PR** 时才跑。真实漏洞正是「改了语言包却根本不碰 aster-api」——
  没有 PR，守门自然没机会执行。本脚本从时间维度补这个缺口（nightly 每晚跑）。

退出码：0 = 一致；1 = 漂移（调用方据此开 issue）；2 = 用法/环境错误。
漂移明细写到 stdout（Markdown 列表），供 workflow 塞进 issue 正文。
"""

from __future__ import annotations

import filecmp
import json
import sys
from pathlib import Path

# classpath 副本(locale id) → 语言包源相对路径。
# ★与 UiMessagesClasspathSiblingParityTest 的映射保持一致：改一处必须同步另一处。
SOURCES: dict[str, str] = {
    "en-US": "aster-lang-locales/locales/en/src/main/resources/ui-messages/en-US.json",
    "zh-CN": "aster-lang-locales/locales/zh/src/main/resources/ui-messages/zh-CN.json",
    "de-DE": "aster-lang-locales/locales/de/src/main/resources/ui-messages/de-DE.json",
    "hi-IN": "aster-lang-hi/src/main/resources/ui-messages/hi-IN.json",
}

COPY_DIR = "aster-api/src/main/resources/ui-messages"


def leaf_count(path: Path) -> int | None:
    """叶子 key 数（仅用于把差异说清楚，解析失败不影响判定）。"""
    try:
        def walk(node: object) -> int:
            if isinstance(node, dict):
                return sum(walk(v) for v in node.values())
            return 1
        return walk(json.loads(path.read_text(encoding="utf-8")))
    except Exception:
        return None


def main(root: Path) -> int:
    drift: list[str] = []
    checked = 0

    for locale, rel_src in SOURCES.items():
        src = root / rel_src
        dst = root / COPY_DIR / f"{locale}.json"

        if not src.exists():
            # 兄弟仓没 checkout（本地跑）→ 跳过而非误报。
            print(f"::warning::语言包源缺失，跳过 {locale}：{src}", file=sys.stderr)
            continue
        checked += 1

        if not dst.exists():
            drift.append(f"- `{locale}`：aster-api 缺少 classpath 副本 `{COPY_DIR}/{locale}.json`")
            continue

        # 逐字节比对——与守门测试同口径（不止 key 集合，值/顺序/格式都要一致）。
        if not filecmp.cmp(src, dst, shallow=False):
            a, b = leaf_count(src), leaf_count(dst)
            detail = f"源 {a} 键 vs aster-api 副本 {b} 键" if a and b else "内容不一致"
            drift.append(f"- `{locale}`：{detail}（**不逐字节一致**）")

    if checked == 0:
        print("::error::四语源全部缺失——兄弟仓未 checkout？", file=sys.stderr)
        return 2

    if drift:
        print("\n".join(drift))
        return 1

    print(f"✅ {checked} 个 locale 的 classpath 副本与语言包源逐字节一致")
    return 0


if __name__ == "__main__":
    # 默认工作目录 = CI 里各仓并列 checkout 的父目录。
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    sys.exit(main(base))
