#!/usr/bin/env python3
"""FeedBucket instrumented-test result verifier.

Deterministic gate for .github/workflows/feedbucket-instrumented.yml.
Compares the JUnit XML emitted by :app:connectedGithubDebugAndroidTest
against the expected @Test methods counted from the committed instrumented
sources. An absent or empty XML is NEVER reinterpreted as a pass.

Usage:
    python3 scripts/verify_instrumented_results.py [results_dir] [src_dir]

Defaults:
    results_dir = app/build/outputs/androidTest-results/connected
    src_dir     = app/src/androidTest/java
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_RESULTS = "app/build/outputs/androidTest-results/connected"
DEFAULT_SRC = "app/src/androidTest/java"


def expected_counts(src_dir: str) -> dict:
    """Count @Test annotations per top-level class file under src_dir."""
    expected = {}
    for path in sorted(glob.glob(os.path.join(src_dir, "**", "*.kt"), recursive=True)):
        simple = os.path.splitext(os.path.basename(path))[0]
        with open(path, encoding="utf-8") as fh:
            n = sum(1 for line in fh if line.lstrip().startswith("@Test"))
        if n:
            expected[simple] = n
    return expected


def parse_results(results_dir: str) -> dict:
    """Return {class_simple_name: {"tests": n, "failures": n, "errors": n, "skipped": n}}."""
    found = {}
    for path in sorted(glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True)):
        tree = ET.parse(path)
        for suite in tree.getroot().iter("testsuite"):
            for tc in suite.iter("testcase"):
                cls = tc.get("classname", "")
                simple = cls.rsplit(".", 1)[-1] if cls else "(no-classname)"
                agg = found.setdefault(simple, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
                agg["tests"] += 1
                agg["failures"] += len(list(tc.iter("failure")))
                agg["errors"] += len(list(tc.iter("error")))
                agg["skipped"] += len(list(tc.iter("skipped")))
    return found


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS
    src_dir = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_SRC

    expected = expected_counts(src_dir)
    if not expected:
        print(f"FAIL: no @Test methods found under {src_dir} (source scan broken)")
        return 1

    xmls = glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True)
    if not xmls:
        print(f"FAIL: no instrumentation XML under {results_dir} "
              f"(aborted runner or tests never executed)")
        return 1

    found = parse_results(results_dir)
    total = sum(a["tests"] for a in found.values())
    if total == 0:
        print(f"FAIL: XML present under {results_dir} but total tests == 0 "
              f"(empty XML is not a pass)")
        return 1

    problems = []
    for cls, want in expected.items():
        got = found.get(cls)
        if got is None:
            problems.append(f"missing class {cls}: expected {want} tests, none ran")
        elif got["tests"] != want:
            problems.append(f"incomplete class {cls}: expected {want} tests, ran {got['tests']}")
        if got:
            for kind in ("failures", "errors", "skipped"):
                if got[kind]:
                    problems.append(f"{cls}: {got[kind]} {kind}")

    print(f"XML files: {len(xmls)} under {results_dir}")
    print(f"total testcases: {total}")
    for cls in sorted(set(expected) | set(found)):
        print(f"  {cls}: expected={expected.get(cls, '-')} "
              f"ran={found.get(cls, {}).get('tests', 0)}")
    for cls, agg in sorted(found.items()):
        if cls not in expected:
            print(f"  NOTE: unexpected class in results: {cls} ({agg['tests']} tests)")

    if problems:
        print("FAIL:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"PASS: {total} testcases across {len(found)} classes match committed sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
