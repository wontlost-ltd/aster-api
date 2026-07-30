#!/usr/bin/env python3
"""从 org runner 列表 JSON（stdin）判定本地 runner 是否可用。

输出 `local` 或 `hosted` 到 stdout。

判定规则：存在至少一个 runner 同时满足
  * status == online
  * busy == false —— 本地通常只有一个 runner，排在别人后面不如直接走托管
  * 标签集**完全覆盖** WANT（环境变量，逗号分隔）
否则输出 hosted。

任何解析异常都输出 hosted：探测器本身绝不能成为 CI 的故障点，宁可花额度
也不要让流水线卡死。
"""
from __future__ import annotations

import json
import os
import sys


def decide(payload: str, want: set[str]) -> str:
    try:
        data = json.loads(payload)
    except (ValueError, TypeError):
        return "hosted"
    if not isinstance(data, dict):
        return "hosted"
    for runner in data.get("runners") or []:
        if not isinstance(runner, dict):
            continue
        if runner.get("status") != "online" or runner.get("busy"):
            continue
        have = {
            lbl.get("name")
            for lbl in (runner.get("labels") or [])
            if isinstance(lbl, dict)
        }
        if want and want <= have:
            return "local"
    return "hosted"


def main() -> int:
    want = {s.strip() for s in os.environ.get("WANT", "").split(",") if s.strip()}
    print(decide(sys.stdin.read(), want))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
