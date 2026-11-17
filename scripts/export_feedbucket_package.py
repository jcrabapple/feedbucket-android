#!/usr/bin/env python3
"""FeedBucket v1 export package tool (S32a, Wave G).

Produces `feedbucket-export-v1.sqlite`: a VERSIONED, SECRET-FREE package from
a FeedBucket web database. Versioned in THIS repo, OUTSIDE the live web repo
(D14) — the source database is opened READ-ONLY and provably unmodified.

Usage:
    python3 scripts/export_feedbucket_package.py <source.db> <out-package.db>

Pipeline:
  1. sha256 the source file bytes (before and after; gate proves unchanged).
  2. Open the source `file:...?mode=ro&immutable=1` and set
     `PRAGMA query_only=ON` — the live service DB is never written.
  3. Snapshot via the SQLite Online Backup API (Connection.backup) into a
     temp file so the source handle can be closed immediately.
  4. Build the package from the snapshot: ONLY the allowlisted tables,
     copied via EXPLICIT COLUMN LISTS via `CREATE TABLE ... AS SELECT` (no
     constraints, no users table, no user_id columns). `users` is never
     opened or referenced.
  5. Write package_meta(schemaVersion=1, createdAt, sourceSha256, countsJson).
  6. Output gates: PRAGMA integrity_check == 'ok'; allowlist assertion (no
     table or column outside the allowlist + package_meta); secret scan (no
     password_hash / starred_token column anywhere; no value matching a
     session-token shape).
  7. Source-unmodified gate: sha256(source) after == before.

Exits non-zero with a precise message on any gate failure; the output file
is removed on failure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

SCHEMA_VERSION = 1

# S32a allowlist — explicit column lists per table. `users` is deliberately
# ABSENT: it is never opened, referenced, or copied. user_id columns on
# saved_searches are excluded for the same reason.
ALLOWLIST: dict[str, list[str]] = {
    "subscriptions": [
        "id", "feed_url", "site_url", "title", "muted", "show_updates",
        "last_fetched", "etag",
    ],
    "entries": [
        "id", "feed_id", "external_id", "title", "url", "author", "summary",
        "content", "original_content", "extracted", "published", "created_at",
    ],
    "unread": ["entry_id"],
    "starred": ["entry_id"],
    "updated": ["entry_id", "updated_at"],
    "recently_read": ["entry_id", "read_at"],
    "tags": ["id", "name"],
    "subscription_tags": ["feed_id", "tag_id"],
    "saved_searches": ["id", "name", "query", "created_at"],
    "actions": [
        "id", "action_type", "title", "query", "all_feeds", "feed_ids",
        "tag_ids", "effects", "created_at",
    ],
    # optional in the source; copied when present
    "user_prefs": [
        "sort", "default_view", "mark_read_on_open", "starred_view",
        "links_new_tab", "article_links_new_tab", "disable_image_proxy",
        "reading_font", "auto_fetch_full", "hide_updated", "theme",
        "font_size", "entry_width", "entries_density", "mastodon_instance",
        "show_unread_count", "hide_recently_read", "mark_as_read_confirmation",
        "starred_feed_enabled",
    ],
}

FORBIDDEN_COLUMN_NAMES = {"password_hash", "starred_token"}

# Session-token shape: long alphanumeric-plus-punctuation runs that look like
# itsdangerous/HMAC session material (web security.py signs with a secret key;
# signed tokens are base64-ish with '.' separators and 16+ chars per segment).
TOKEN_VALUE_RE = re.compile(r"[A-Za-z0-9_-]{20,}(\.[A-Za-z0-9_-]{16,})+")


class GateError(Exception):
    """A precise, user-facing gate failure message."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def open_source_readonly(path: Path) -> sqlite3.Connection:
    uri = f"file:{path}?mode=ro&immutable=1"
    conn = sqlite3.connect(uri, uri=True)
    conn.execute("PRAGMA query_only=ON")
    return conn


def snapshot_via_backup(src: sqlite3.Connection, dest_path: Path) -> None:
    dest = sqlite3.connect(dest_path)
    try:
        src.backup(dest)
    finally:
        dest.close()


def table_columns(conn: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in conn.execute(f"PRAGMA table_info({table})")]


def build_package(snapshot: Path, out: Path, source_sha: str) -> dict[str, int]:
    if out.exists():
        out.unlink()
    pkg = sqlite3.connect(out)
    pkg.execute("ATTACH DATABASE ? AS snapshot", (str(snapshot),))
    counts: dict[str, int] = {}
    try:
        for table, columns in ALLOWLIST.items():
            present = [row[1] for row in pkg.execute(f"PRAGMA snapshot.table_info({table})")]
            if not present:
                if table == "user_prefs":
                    continue  # optional table
                raise GateError(f"required table '{table}' missing from source")
            missing = [c for c in columns if c not in present]
            if missing:
                raise GateError(
                    f"table '{table}' missing required columns: {missing}"
                )
            cols = ", ".join(columns)
            # CREATE TABLE ... AS SELECT: constraint-free copy of exactly the
            # allowlisted columns (no users FK, no user_id).
            pkg.execute(
                f'CREATE TABLE "{table}" AS SELECT {cols} FROM snapshot."{table}"'
            )
            counts[table] = pkg.execute(
                f'SELECT COUNT(*) FROM "{table}"'
            ).fetchone()[0]
        pkg.execute(
            "CREATE TABLE package_meta ("
            " schemaVersion INTEGER NOT NULL, createdAt TEXT NOT NULL,"
            " sourceSha256 TEXT NOT NULL, countsJson TEXT NOT NULL)"
        )
        pkg.execute(
            "INSERT INTO package_meta(schemaVersion, createdAt, sourceSha256,"
            " countsJson) VALUES (?, ?, ?, ?)",
            (
                SCHEMA_VERSION,
                datetime.now(UTC).isoformat(),
                source_sha,
                json.dumps(counts, sort_keys=True),
            ),
        )
        pkg.commit()
        return counts
    finally:
        pkg.close()


def gate_integrity(pkg_path: Path) -> None:
    pkg = sqlite3.connect(pkg_path)
    try:
        rows = [r[0] for r in pkg.execute("PRAGMA integrity_check")]
    finally:
        pkg.close()
    if rows != ["ok"]:
        raise GateError(f"integrity_check failed: {rows[:5]}")


def gate_allowlist(pkg_path: Path) -> None:
    pkg = sqlite3.connect(pkg_path)
    try:
        tables = {
            r[0]
            for r in pkg.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
    finally:
        pkg.close()
    expected = set(ALLOWLIST) | {"package_meta"}
    extra = tables - expected
    missing = set(ALLOWLIST) - tables - {"user_prefs"}
    if extra or missing:
        raise GateError(f"allowlist violation: extra={sorted(extra)} missing={sorted(missing)}")
    # Column-level assertion: every column of every table is allowlisted.
    pkg = sqlite3.connect(pkg_path)
    try:
        for table in tables - {"package_meta"}:
            cols = {r[1] for r in pkg.execute(f"PRAGMA table_info({table})")}
            allowed = set(ALLOWLIST.get(table, []))
            outside = cols - allowed
            if outside:
                raise GateError(f"columns outside allowlist in {table}: {sorted(outside)}")
    finally:
        pkg.close()


def gate_secret_scan(pkg_path: Path) -> None:
    pkg = sqlite3.connect(pkg_path)
    try:
        tables = [
            r[0]
            for r in pkg.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        ]
        for table in tables:
            for (_, name, *_rest) in pkg.execute(f"PRAGMA table_info({table})"):
                if name in FORBIDDEN_COLUMN_NAMES:
                    raise GateError(
                        f"secret column '{name}' present in package table '{table}'"
                    )
        # Value-level scan for session-token-shaped strings.
        for table in tables:
            cols = [r[1] for r in pkg.execute(f"PRAGMA table_info({table})")]
            for col in cols:
                hits = pkg.execute(
                    f'SELECT "{col}" FROM "{table}" WHERE "{col}" IS NOT NULL'
                ).fetchall()
                for (value,) in hits:
                    if isinstance(value, str) and TOKEN_VALUE_RE.search(value):
                        raise GateError(
                            f"token-shaped value in {table}.{col}: {value[:12]}..."
                        )
    finally:
        pkg.close()


def export_package(source: Path, out: Path) -> int:
    if not source.exists():
        print(f"error: source not found: {source}", file=sys.stderr)
        return 2
    before = sha256_file(source)
    try:
        with tempfile.TemporaryDirectory(prefix="fbk-export-") as tmp:
            snap_path = Path(tmp) / "snapshot.db"
            src = open_source_readonly(source)
            try:
                snapshot_via_backup(src, snap_path)
            finally:
                src.close()
            build_package(snap_path, out, before)
            gate_integrity(out)
            gate_allowlist(out)
            gate_secret_scan(out)
        after = sha256_file(source)
        if after != before:
            out.unlink(missing_ok=True)
            raise GateError("source database modified during export")
    except GateError:
        out.unlink(missing_ok=True)
        raise
    print(f"package written: {out}")
    print(f"source sha256: {before} (unmodified)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args(argv)
    try:
        return export_package(args.source, args.out)
    except GateError as exc:
        print(f"gate failure: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
