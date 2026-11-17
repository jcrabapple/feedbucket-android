#!/usr/bin/env bash
# FeedBucket canonical wave gate.
# Usage: bash scripts/gate.sh <wave-letter>
# Runs: memory check, :app:testGithubDebugUnitTest, :app:assembleGithubDebug,
# :app:lintGithubDebug, scripts/lint_delta.py. Prints PASS/FAIL + exit code.
set -uo pipefail

WAVE="${1:?usage: bash scripts/gate.sh <wave>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/home/jason/.local/share/jbr}"

echo "=== FeedBucket gate: wave $WAVE ==="
date
echo "--- memory ---"
free -h
AVAIL_GIB=$(free -g | awk '/^Mem:/ {print $7}')
if [ "$AVAIL_GIB" -lt 8 ]; then
    echo "FAIL: available memory ${AVAIL_GIB} GiB < 8 GiB"
    exit 1
fi
echo "memory OK (${AVAIL_GIB} GiB available)"

if pgrep -f GradleDaemon >/dev/null 2>&1; then
    echo "WARN: a GradleDaemon process exists (proceeding with --no-daemon)"
fi

GRADLE=(./gradlew --no-daemon --no-parallel --max-workers=1)
STATUS=0

echo "--- :app:testGithubDebugUnitTest ---"
if "${GRADLE[@]}" :app:testGithubDebugUnitTest && python3 - << 'PYEOF'
import glob, sys, xml.etree.ElementTree as ET
total = fails = 0
classes = 0
for f in glob.glob("app/build/test-results/testGithubDebugUnitTest/*.xml"):
    tree = ET.parse(f)
    classes += 1
    for tc in tree.getroot().iter("testcase"):
        total += 1
        if list(tc.iter("failure")) or list(tc.iter("error")):
            fails += 1
print(f"tests: {total} run, {fails} failed across {classes} classes")
sys.exit(1 if fails else 0)
PYEOF
then
    : # unit tests green
else
    echo "FAIL: unit tests"
    STATUS=1
fi

echo "--- :app:assembleGithubDebug ---"
if ! "${GRADLE[@]}" :app:assembleGithubDebug; then
    echo "FAIL: assemble"
    STATUS=1
fi

echo "--- :app:lintGithubDebug ---"
if ! "${GRADLE[@]}" :app:lintGithubDebug; then
    echo "FAIL: lint task"
    STATUS=1
fi

echo "--- lint delta ---"
if ! python3 scripts/lint_delta.py; then
    echo "FAIL: lint delta"
    STATUS=1
fi

"${GRADLE[@]}" --stop >/dev/null 2>&1 || true

if [ "$STATUS" -eq 0 ]; then
    echo "=== GATE wave $WAVE: PASS ==="
else
    echo "=== GATE wave $WAVE: FAIL ==="
fi
exit $STATUS
