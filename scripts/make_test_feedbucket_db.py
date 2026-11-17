#!/usr/bin/env python3
"""Synthetic FeedBucket web-schema fixture generator (Wave G, S32a/D16).

Builds SQLite databases matching the LIVE web schema (feedbucket/db.py SCHEMA)
with SYNTHETIC data only — never a copy of a real user database (D16).

Usage:
    python3 scripts/make_test_feedbucket_db.py raw <out.db> [--invalid-schema]
    python3 scripts/make_test_feedbucket_db.py package <out.db> <out-package.db>

`raw` writes the canonical 3-feed / 7-entry fixture:
  * 3 subscriptions (one muted, one with two tags, one with none)
  * 7 entries across the feeds with unread/starred/updated/recently_read state
  * 2 tags + subscription_tags rows
  * 2 saved searches (1 standard-use, 1 mute-query) and 2 action rules
    (1 standard, 1 mute)
  * users row with FAKE password_hash/starred_token so the export tool's
    secret gates are exercised against the real column names
  * user_prefs row

`--invalid-schema` omits the `starred` table (schema-checker rejection path).

`package` runs `scripts/export_feedbucket_package.py` on the generated raw
fixture and writes a v1 export package next to it.

All timestamps/ids are fixed so expected counts in
MigrationImporterTest / ExportPackageToolTest are hand-computable.
"""

from __future__ import annotations

import argparse
import sqlite3
import subprocess
import sys
from pathlib import Path

# Mirrors feedbucket/db.py SCHEMA (read-only reference, D14). Keep in sync
# with the web repo when its schema changes.
WEB_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_created_at TEXT NOT NULL,
    starred_token TEXT
);
CREATE TABLE IF NOT EXISTS user_prefs (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    sort TEXT,
    default_view TEXT,
    mark_read_on_open INTEGER,
    starred_view INTEGER,
    links_new_tab INTEGER,
    article_links_new_tab INTEGER NOT NULL DEFAULT 0,
    disable_image_proxy INTEGER,
    reading_font TEXT NOT NULL DEFAULT 'sans',
    auto_fetch_full INTEGER NOT NULL DEFAULT 1,
    hide_updated INTEGER NOT NULL DEFAULT 0,
    theme TEXT NOT NULL DEFAULT 'auto',
    font_size INTEGER NOT NULL DEFAULT 5,
    entry_width TEXT NOT NULL DEFAULT 'default',
    entries_density TEXT NOT NULL DEFAULT 'default',
    mastodon_instance TEXT NOT NULL DEFAULT '',
    show_unread_count INTEGER NOT NULL DEFAULT 0,
    hide_recently_read INTEGER NOT NULL DEFAULT 0,
    mark_as_read_confirmation INTEGER NOT NULL DEFAULT 1,
    starred_feed_enabled INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS subscriptions (
    id INTEGER PRIMARY KEY,
    feed_url TEXT NOT NULL UNIQUE,
    site_url TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    muted INTEGER NOT NULL DEFAULT 0,
    show_updates INTEGER NOT NULL DEFAULT 1,
    last_fetched TEXT,
    etag TEXT
);
CREATE TABLE IF NOT EXISTS entries (
    id INTEGER PRIMARY KEY,
    feed_id INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    url TEXT NOT NULL DEFAULT '',
    author TEXT NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    original_content TEXT,
    extracted INTEGER NOT NULL DEFAULT 0,
    published TEXT,
    created_at TEXT NOT NULL,
    UNIQUE(feed_id, external_id)
);
CREATE TABLE IF NOT EXISTS unread (
    entry_id INTEGER PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS starred (
    entry_id INTEGER PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS updated (
    entry_id INTEGER PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS recently_read (
    entry_id INTEGER PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    read_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tags (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS subscription_tags (
    feed_id INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY(feed_id, tag_id)
);
CREATE TABLE IF NOT EXISTS saved_searches (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    query TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS actions (
    id INTEGER PRIMARY KEY,
    action_type TEXT NOT NULL DEFAULT 'standard'
        CHECK (action_type IN ('standard', 'mute')),
    title TEXT NOT NULL DEFAULT '',
    query TEXT NOT NULL DEFAULT '',
    all_feeds INTEGER NOT NULL DEFAULT 1,
    feed_ids TEXT NOT NULL DEFAULT '',
    tag_ids TEXT NOT NULL DEFAULT '',
    effects TEXT NOT NULL DEFAULT 'mark_read',
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS saved_searches_user ON saved_searches(user_id, name);
CREATE INDEX IF NOT EXISTS entries_feed_published ON entries(feed_id, published DESC);
CREATE INDEX IF NOT EXISTS entries_created ON entries(created_at DESC);
"""

FEEDS = [
    # (id, feed_url, site_url, title, muted, show_updates)
    (1, "https://alpha.example/feed.xml", "https://alpha.example", "Alpha Blog", 0, 1),
    (2, "https://beta.example/rss", "https://beta.example", "Beta News", 1, 0),
    (3, "https://gamma.example/atom.xml", "", "Gamma Lab", 0, 1),
]

TAGS = [(1, "tech"), (2, "science")]

SUBSCRIPTION_TAGS = [(1, 1), (1, 2)]  # feed 1 -> tech, science

# (id, feed_id, external_id, title, url, author, summary, content,
#  original_content, extracted, published, created_at)
ENTRIES = [
    (101, 1, "guid-a1", "Alpha One", "https://alpha.example/one",
     "Ann Author", "Summary one", "<p>Full body one</p>",
     "<p>Original body one</p>", 0, "2026-10-01T09:00:00+00:00",
     "2026-10-01T09:05:00+00:00"),
    (102, 1, "guid-a2", "Alpha Two", "https://alpha.example/two",
     "", "Summary two", "<p>Body two</p>", None, 1,
     "2026-10-02T09:00:00+00:00", "2026-10-02T09:05:00+00:00"),
    (103, 1, "guid-a3", "Alpha Three", "https://alpha.example/three",
     "Ann Author", "Summary three", "<p>Body three</p>", None, 0,
     None, "2026-10-03T09:05:00+00:00"),
    (201, 2, "guid-b1", "Beta One", "https://beta.example/one",
     "Bob", "Beta summary one", "<p>Beta body one</p>", None, 0,
     "2026-10-01T12:00:00+00:00", "2026-10-01T12:05:00+00:00"),
    (202, 2, "guid-b2", "Beta Two", "https://beta.example/two",
     "", "Beta summary two", "<p>Beta body two</p>",
     "<p>Original beta two</p>", 1, "2026-10-02T12:00:00+00:00",
     "2026-10-02T12:05:00+00:00"),
    (301, 3, "guid-g1", "Gamma One", "https://gamma.example/one",
     "", "Gamma summary one", "<p>Gamma body one</p>", None, 0,
     "2026-10-01T15:00:00+00:00", "2026-10-01T15:05:00+00:00"),
    (302, 3, "guid-g2", "Gamma Two", "https://gamma.example/two",
     "Gus", "Gamma summary two", "<p>Gamma body two</p>", None, 0,
     "2026-10-02T15:00:00+00:00", "2026-10-02T15:05:00+00:00"),
]

UNREAD = [(101,), (103,), (201,), (301,)]
STARRED = [(102,), (202,)]
UPDATED = [(103, "2026-10-05T10:00:00+00:00")]
RECENTLY_READ = [(102, "2026-10-04T18:30:00+00:00"), (201, "2026-10-04T19:00:00+00:00")]

SAVED_SEARCHES = [
    (1, "Unread tech", "tag:tech", "2026-09-01T00:00:00+00:00"),
    (2, "Alpha mentions", "alpha sort:desc", "2026-09-02T00:00:00+00:00"),
]

ACTIONS = [
    (1, "standard", "Mark beta read", "beta", 1, "", "", "mark_read",
     "2026-09-03T00:00:00+00:00"),
    (2, "mute", "Mute gamma", "gamma", 0, "3", "", "mark_read",
     "2026-09-04T00:00:00+00:00"),
]

# Deliberately FAKE — exercises the export gates against the real column
# names without any real credential material (D16).
FAKE_PASSWORD_HASH = "pbkdf2:sha256:600000$fakefakefake01$" + "ab" * 32
FAKE_STARRED_TOKEN = "fake-starred-token-0123456789abcdef"


def build_raw(path: Path, invalid_schema: bool = False) -> None:
    if path.exists():
        path.unlink()
    conn = sqlite3.connect(path)
    try:
        conn.executescript(WEB_SCHEMA)
        if invalid_schema:
            # Rejection fixture: a required allowlisted table is absent.
            conn.execute("DROP TABLE starred")
        conn.executemany(
            "INSERT INTO users(id, username, password_hash, password_created_at,"
            " starred_token) VALUES (1, 'synthetic', ?, '2026-01-01T00:00:00+00:00', ?)",
            [(FAKE_PASSWORD_HASH, FAKE_STARRED_TOKEN)],
        )
        conn.execute(
            "INSERT INTO user_prefs(user_id, sort, default_view, mark_read_on_open,"
            " starred_view, links_new_tab, theme) VALUES (1, 'DESC', 'unread', 1, 1, 0, 'auto')"
        )
        conn.executemany(
            "INSERT INTO subscriptions(id, feed_url, site_url, title, muted,"
            " show_updates) VALUES (?, ?, ?, ?, ?, ?)",
            FEEDS,
        )
        conn.executemany(
            "INSERT INTO entries(id, feed_id, external_id, title, url, author,"
            " summary, content, original_content, extracted, published, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ENTRIES,
        )
        conn.executemany("INSERT INTO unread(entry_id) VALUES (?)", UNREAD)
        if not invalid_schema:
            conn.executemany("INSERT INTO starred(entry_id) VALUES (?)", STARRED)
        conn.executemany(
            "INSERT INTO updated(entry_id, updated_at) VALUES (?, ?)", UPDATED
        )
        conn.executemany(
            "INSERT INTO recently_read(entry_id, read_at) VALUES (?, ?)", RECENTLY_READ
        )
        conn.executemany("INSERT INTO tags(id, name) VALUES (?, ?)", TAGS)
        conn.executemany(
            "INSERT INTO subscription_tags(feed_id, tag_id) VALUES (?, ?)",
            SUBSCRIPTION_TAGS,
        )
        conn.executemany(
            "INSERT INTO saved_searches(id, user_id, name, query, created_at)"
            " VALUES (?, 1, ?, ?, ?)",
            SAVED_SEARCHES,
        )
        conn.executemany(
            "INSERT INTO actions(id, action_type, title, query, all_feeds, feed_ids,"
            " tag_ids, effects, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ACTIONS,
        )
        conn.commit()
    finally:
        conn.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    raw = sub.add_parser("raw", help="write the synthetic raw web-schema DB")
    raw.add_argument("out", type=Path)
    raw.add_argument("--invalid-schema", action="store_true",
                     help="omit the starred table (rejection fixture)")
    pkg = sub.add_parser("package", help="raw fixture + v1 export package")
    pkg.add_argument("out", type=Path)
    pkg.add_argument("package_out", type=Path)
    args = parser.parse_args(argv)

    if args.cmd == "raw":
        build_raw(args.out, invalid_schema=args.invalid_schema)
        print(f"wrote {args.out}")
        return 0

    build_raw(args.out)
    print(f"wrote {args.out}")
    tool = Path(__file__).with_name("export_feedbucket_package.py")
    subprocess.run(
        [sys.executable, str(tool), str(args.out), str(args.package_out)],
        check=True,
    )
    print(f"wrote {args.package_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
