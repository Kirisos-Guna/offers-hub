"""SQLite storage for offers."""
import json
import sqlite3
import threading
import time

from config import DB_PATH, KEEP_DAYS
from offer_parser import dedupe_key

_lock = threading.Lock()
_conn = sqlite3.connect(DB_PATH, check_same_thread=False)
_conn.row_factory = sqlite3.Row
_conn.executescript(
    """
    CREATE TABLE IF NOT EXISTS offers (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        source_key  TEXT UNIQUE NOT NULL,   -- tg:<chat>:<msg> or dc:<product>
        dedupe_key  TEXT NOT NULL,
        source      TEXT NOT NULL,          -- channel / site name
        title       TEXT NOT NULL,
        tag         TEXT,
        store       TEXT NOT NULL,
        price       REAL,
        mrp         REAL,
        discount    INTEGER,
        image       TEXT,
        url         TEXT NOT NULL,
        links       TEXT NOT NULL,
        text        TEXT NOT NULL,
        posted_at   INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_offers_posted ON offers(posted_at DESC);
    CREATE INDEX IF NOT EXISTS idx_offers_dedupe ON offers(dedupe_key, posted_at);
    -- Where each channel's scheduled sync left off.
    CREATE TABLE IF NOT EXISTS channels (
        link     TEXT PRIMARY KEY,
        peer_id  INTEGER NOT NULL,
        title    TEXT NOT NULL,
        last_id  INTEGER NOT NULL DEFAULT 0
    );
    """
)

SORTS = {
    "latest": "posted_at DESC, id DESC",
    "discount": "COALESCE(discount, -1) DESC, posted_at DESC",
    "price": "price IS NULL, price ASC, posted_at DESC",
}


def upsert_offer(source_key: str, source: str, offer: dict, posted_at: int, image: str | None) -> bool:
    """Inserts a new offer. Returns False when it's a repost of a deal seen in the last 2 days."""
    key = dedupe_key(offer)
    with _lock:
        existing = _conn.execute("SELECT id FROM offers WHERE source_key = ?", (source_key,)).fetchone()
        if existing is None:
            dup = _conn.execute(
                "SELECT id FROM offers WHERE dedupe_key = ? AND ABS(posted_at - ?) < 172800",
                (key, posted_at),
            ).fetchone()
            if dup:
                return False
        _conn.execute(
            """
            INSERT INTO offers (source_key, dedupe_key, source, title, tag, store, price, mrp, discount,
                                image, url, links, text, posted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_key) DO UPDATE SET
                dedupe_key = excluded.dedupe_key, title = excluded.title, tag = excluded.tag,
                store = excluded.store, price = excluded.price, mrp = excluded.mrp,
                discount = excluded.discount, image = COALESCE(excluded.image, offers.image),
                url = excluded.url, links = excluded.links, text = excluded.text
            """,
            (source_key, key, source, offer["title"], offer.get("tag"), offer["store"], offer.get("price"),
             offer.get("mrp"), offer.get("discount"), image, offer["url"], json.dumps(offer["links"]),
             offer["text"], posted_at),
        )
        _conn.commit()
    return True


def has_offer(source_key: str) -> bool:
    with _lock:
        return _conn.execute("SELECT 1 FROM offers WHERE source_key = ?", (source_key,)).fetchone() is not None


def delete_offers(source_keys: list[str]) -> None:
    with _lock:
        _conn.executemany("DELETE FROM offers WHERE source_key = ?", [(k,) for k in source_keys])
        _conn.commit()


def purge_old() -> int:
    cutoff = int(time.time()) - KEEP_DAYS * 86400
    with _lock:
        cur = _conn.execute("DELETE FROM offers WHERE posted_at < ?", (cutoff,))
        _conn.commit()
        return cur.rowcount


def _row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["links"] = json.loads(d["links"])
    d.pop("dedupe_key", None)
    return d


def list_offers(store: str | None, q: str | None, min_discount: int | None, sort: str,
                offset: int, limit: int) -> list[dict]:
    where, args = [], []
    if store:
        where.append("store = ?")
        args.append(store)
    if q:
        for word in q.split():
            where.append("(title LIKE ? OR text LIKE ?)")
            args += [f"%{word}%", f"%{word}%"]
    if min_discount:
        where.append("discount >= ?")
        args.append(min_discount)
    sql = "SELECT * FROM offers"
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += f" ORDER BY {SORTS.get(sort, SORTS['latest'])} LIMIT ? OFFSET ?"
    args += [limit, offset]
    with _lock:
        return [_row_to_dict(r) for r in _conn.execute(sql, args).fetchall()]


def all_offers() -> list[dict]:
    with _lock:
        rows = _conn.execute(f"SELECT * FROM offers ORDER BY {SORTS['latest']}").fetchall()
    return [_row_to_dict(r) for r in rows]


def referenced_images() -> set[str]:
    with _lock:
        return {r[0] for r in _conn.execute("SELECT image FROM offers WHERE image IS NOT NULL")}


def get_channel(link: str) -> dict | None:
    with _lock:
        row = _conn.execute("SELECT * FROM channels WHERE link = ?", (link,)).fetchone()
    return dict(row) if row else None


def save_channel(link: str, peer_id: int, title: str, last_id: int) -> None:
    with _lock:
        _conn.execute(
            """INSERT INTO channels (link, peer_id, title, last_id) VALUES (?, ?, ?, ?)
               ON CONFLICT(link) DO UPDATE SET peer_id = excluded.peer_id, title = excluded.title,
                                               last_id = MAX(channels.last_id, excluded.last_id)""",
            (link, peer_id, title, last_id),
        )
        _conn.commit()


def close() -> None:
    with _lock:
        _conn.close()


def store_counts() -> list[dict]:
    with _lock:
        rows = _conn.execute(
            "SELECT store, COUNT(*) AS count FROM offers GROUP BY store ORDER BY count DESC"
        ).fetchall()
    return [{"name": r["store"], "count": r["count"]} for r in rows]
