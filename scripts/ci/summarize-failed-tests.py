#!/usr/bin/env python3
"""把 JUnit XML 里的失败/错误用例汇总成 Markdown，写到 stdout。

用途：替代原先的 dorny/test-reporter 步骤。那个 action 是 ci.yml 里唯一需要
`checks: write` 权限的东西，而在 public 仓 + fork PR 场景下，把"写 check 结论"
的权限交给运行 PR 可控代码的 job，等于允许伪造绿色检查结论。去掉写权限比
"报告好看"更重要，故改为把摘要打进 GITHUB_STEP_SUMMARY（只写本 job 自己的日志，
不需要任何仓库级权限）。

用法：
    python3 scripts/ci/summarize-failed-tests.py <测试结果根目录>...

只列失败/错误；全绿时输出一行说明。完整 XML 仍由 test-results 制品提供。
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_MSG = 200
MAX_CASES_PER_SUITE = 25


def suite_report(xml_path: Path) -> list[str]:
    try:
        root = ET.parse(xml_path).getroot()
    except (ET.ParseError, OSError):
        # 损坏/截断的 XML 不应让摘要步骤本身失败——它只是诊断输出。
        return []

    failures = int(root.get("failures") or 0)
    errors = int(root.get("errors") or 0)
    if failures == 0 and errors == 0:
        return []

    lines = [f"- **{root.get('name')}** failures={failures} errors={errors}"]
    shown = 0
    for case in root.iter("testcase"):
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        if bad is None:
            continue
        if shown >= MAX_CASES_PER_SUITE:
            lines.append(f"  - …（其余已省略，完整结果见 test-results 制品）")
            break
        msg = " ".join((bad.get("message") or "").split())[:MAX_MSG]
        lines.append(f"  - `{case.get('name')}` — {msg}")
        shown += 1
    return lines


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path("build/test-results")]
    out: list[str] = []
    scanned = 0
    for root in roots:
        if not root.exists():
            continue
        for xml_path in sorted(root.rglob("TEST-*.xml")):
            scanned += 1
            out.extend(suite_report(xml_path))

    print("### 失败用例摘要")
    if not scanned:
        print("（未找到测试结果 XML——测试可能未执行，请检查上游步骤）")
    elif not out:
        print(f"（扫描 {scanned} 个结果文件，无失败用例）")
    else:
        print("\n".join(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
