"""Acceptance #8a — S32a v1 export tool tests (stdlib unittest, no deps).

Runs scripts/export_feedbucket_package.py against synthetic web-schema DBs
built by scripts/make_test_feedbucket_db.py and asserts every output gate:
integrity_check, exact allowlist, package_meta counts == source counts,
secret scan clean (users/password_hash/starred_token never exported), and
the source database provably unmodified (D14).

Run standalone:  python3 -m unittest app/src/test/python/test_export_feedbucket_package.py
"""

from __future__ import annotations

import hashlib
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
MAKE_DB = REPO_ROOT / "scripts" / "make_test_feedbucket_db.py"
EXPORT_TOOL = REPO_ROOT / "scripts" / "export_feedbucket_package.py"

ALLOWLISTED_TABLES = {
    "subscriptions", "entries", "unread", "starred", "updated",
    "recently_read", "tags", "subscription_tags", "saved_searches",
    "actions", "user_prefs",
}
SECRET_COLUMNS = {"password_hash", "starred_token"}


def run_tool(source: Path, out: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(EXPORT_TOOL), str(source), str(out)],
        capture_output=True,
        text=True,
    )


class ExportPackageToolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.tmp = tempfile.TemporaryDirectory(prefix="fbk-tool-test-")
        cls.root = Path(cls.tmp.name)
        cls.source = cls.root / "fixture.db"
        cls.package = cls.root / "fixture-v1.sqlite"
        subprocess.run(
            [sys.executable, str(MAKE_DB), "raw", str(cls.source)],
            check=True,
            capture_output=True,
        )
        cls.sha_before = hashlib.sha256(cls.source.read_bytes()).hexdigest()
        cls.result = run_tool(cls.source, cls.package)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.tmp.cleanup()

    def test_exit_zero_and_package_exists(self) -> None:
        self.assertEqual(0, self.result.returncode, self.result.stderr)
        self.assertTrue(self.package.exists())

    def test_integrity_check_ok(self) -> None:
        conn = sqlite3.connect(self.package)
        self.assertEqual(["ok"], [r[0] for r in conn.execute("PRAGMA integrity_check")])
        conn.close()

    def test_allowlist_exact(self) -> None:
        conn = sqlite3.connect(self.package)
        tables = {
            r[0]
            for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        conn.close()
        self.assertEqual(ALLOWLISTED_TABLES | {"package_meta"}, tables)
        self.assertNotIn("users", tables)

    def test_explicit_column_lists_no_user_columns(self) -> None:
        conn = sqlite3.connect(self.package)
        try:
            for table in ALLOWLISTED_TABLES:
                cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
                self.assertFalse(
                    cols & SECRET_COLUMNS,
                    f"secret columns leaked into {table}: {cols & SECRET_COLUMNS}",
                )
                self.assertNotIn("user_id", cols, f"user_id leaked into {table}")
        finally:
            conn.close()

    def test_package_meta_counts_equal_source_counts(self) -> None:
        import json

        pkg = sqlite3.connect(self.package)
        meta = pkg.execute(
            "SELECT schemaVersion, sourceSha256, countsJson FROM package_meta"
        ).fetchone()
        counts = json.loads(meta[2])
        src = sqlite3.connect(self.source)
        for table in sorted(ALLOWLISTED_TABLES):
            expected = src.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            self.assertEqual(expected, counts[table], f"count mismatch: {table}")
        src.close()
        pkg.close()
        self.assertEqual(1, meta[0])
        self.assertEqual(self.sha_before, meta[1])

    def test_source_database_unmodified(self) -> None:
        sha_after = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.assertEqual(self.sha_before, sha_after)

    def test_invalid_schema_rejected_without_output(self) -> None:
        broken = self.root / "broken.db"
        broken_out = self.root / "broken.sqlite"
        subprocess.run(
            [sys.executable, str(MAKE_DB), "raw", str(broken), "--invalid-schema"],
            check=True,
            capture_output=True,
        )
        result = run_tool(broken, broken_out)
        self.assertEqual(1, result.returncode)
        self.assertIn("starred", result.stderr)
        self.assertFalse(broken_out.exists())

    def test_mixed_storage_class_token_detected(self) -> None:
        # Regression (review round 1, finding 1): a column whose FIRST
        # non-null value is an integer (entries.extracted = 0) but a LATER
        # row carries token-shaped TEXT must still be scanned value-by-value.
        # The old typeof-probe implementation skipped the whole column and
        # shipped the token with rc=0.
        mixed = self.root / "mixed.db"
        mixed_out = self.root / "mixed.sqlite"
        subprocess.run(
            [sys.executable, str(MAKE_DB), "raw", str(mixed)],
            check=True,
            capture_output=True,
        )
        token = "abcdefghijklmnopqrstuvwxyz0123456789.ABCDEFGHIJKLMNOP"
        conn = sqlite3.connect(mixed)
        try:
            conn.execute(
                "UPDATE entries SET extracted = ? WHERE id = 102", (token,)
            )
            conn.commit()
        finally:
            conn.close()

        result = run_tool(mixed, mixed_out)
        self.assertEqual(1, result.returncode, result.stderr + result.stdout)
        self.assertIn("token-shaped value", result.stderr)
        self.assertFalse(mixed_out.exists())


if __name__ == "__main__":
    unittest.main()
