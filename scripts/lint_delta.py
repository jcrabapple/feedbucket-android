#!/usr/bin/env python3
"""FeedBucket lint delta gate.

Compares a fresh lint report against the committed reference baseline
(docs/research/lint-baseline.xml). Exit 0 iff no NEW Error-severity
findings (keyed by id + file + message hash) and total Errors <= baseline
count. Prints an added/removed table.

Usage:
    python3 scripts/lint_delta.py [report.xml] [baseline.xml]

Defaults: app/build/reports/lint-results-githubDebug.xml and
docs/research/lint-baseline.xml.
"""

import hashlib
import sys
import xml.etree.ElementTree as ET

DEFAULT_REPORT = "app/build/reports/lint-results-githubDebug.xml"
DEFAULT_BASELINE = "docs/research/lint-baseline.xml"


def findings(path: str) -> dict:
    """Return {key: count} of Error-severity issues keyed by (id, file, message-hash)."""
    tree = ET.parse(path)
    root = tree.getroot()
    out = {}
    for issue in root.iter("issue"):
        if issue.get("severity") != "Error":
            continue
        id_ = issue.get("id", "")
        message = issue.get("message", "")
        msg_hash = hashlib.sha256(message.encode("utf-8")).hexdigest()[:12]
        locations = list(issue.iter("location"))
        if locations:
            for loc in locations:
                key = (id_, loc.get("file", ""), msg_hash)
                out[key] = out.get(key, 0) + 1
        else:
            key = (id_, "", msg_hash)
            out[key] = out.get(key, 0) + 1
    return out


def main() -> int:
    report = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPORT
    baseline_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_BASELINE

    try:
        base = findings(baseline_path)
    except OSError as e:
        print(f"FAIL: cannot read baseline {baseline_path}: {e}")
        return 2
    try:
        fresh = findings(report)
    except OSError as e:
        print(f"FAIL: cannot read report {report}: {e}")
        return 2

    added = {k: n for k, n in fresh.items() if base.get(k, 0) < n}
    removed = {k: n for k, n in base.items() if fresh.get(k, 0) < n}

    total_base = sum(base.values())
    total_fresh = sum(fresh.values())

    print(f"baseline Errors: {total_base}  fresh Errors: {total_fresh}")
    if added:
        print("\nADDED (new errors):")
        for (id_, file, msg_hash), n in sorted(added.items()):
            print(f"  + {id_} {file} #{msg_hash} x{n}")
    if removed:
        print("\nREMOVED (fixed vs baseline):")
        for (id_, file, msg_hash), n in sorted(removed.items()):
            print(f"  - {id_} {file} #{msg_hash} x{n}")

    if added or total_fresh > total_base:
        print("\nLINT DELTA: FAIL")
        return 1
    print("\nLINT DELTA: PASS (no new errors, total <= baseline)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
