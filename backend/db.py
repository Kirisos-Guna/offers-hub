"""SQLite storage for offers."""
import json
import sqlite3
import threading
import time

from categories import categorize
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
    -- What the live listener has pushed to Firebase (see firebase_publisher.py).
    CREATE TABLE IF NOT EXISTS published (
        source_key  TEXT PRIMARY KEY,
        hash        TEXT NOT NULL,          -- of the document as last written
        image_blob  TEXT,                   -- Storage object holding the deal's image
        notified    INTEGER NOT NULL DEFAULT 0
    );
    """
)

# Columns added after the first release.
_COLUMNS = {
    # 1 = title is only a price/promo word and still needs a product name, 2 = name lookup done.
    "weak_title": "INTEGER NOT NULL DEFAULT 0",
    "category": "TEXT NOT NULL DEFAULT 'Other'",
    # The channel edited the post to say the deal is over.
    "expired": "INTEGER NOT NULL DEFAULT 0",
}
_existing = {r[1] for r in _conn.execute("PRAGMA table_info(offers)")}
for _name, _type in _COLUMNS.items():
    if _name not in _existing:
        _conn.execute(f"ALTER TABLE offers ADD COLUMN {_name} {_type}")
_conn.commit()

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
                if offer.get("expired"):  # a sister channel says the deal we kept is over
                    _conn.execute("UPDATE offers SET expired = 1 WHERE id = ?", (dup["id"],))
                    _conn.commit()
                return False
        _conn.execute(
            """
            INSERT INTO offers (source_key, dedupe_key, source, title, tag, store, price, mrp, discount,
                                image, url, links, text, posted_at, weak_title, category, expired)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_key) DO UPDATE SET
                dedupe_key = excluded.dedupe_key, tag = excluded.tag, expired = excluded.expired,
                category = CASE WHEN excluded.weak_title = 1 AND offers.weak_title = 2
                                THEN offers.category ELSE excluded.category END,
                -- an edited post keeps a product name found earlier for its vague title
                title = CASE WHEN excluded.weak_title = 1 AND offers.weak_title = 2
                             THEN offers.title ELSE excluded.title END,
                weak_title = CASE WHEN excluded.weak_title = 1 AND offers.weak_title = 2
                                  THEN 2 ELSE excluded.weak_title END,
                store = excluded.store, price = excluded.price, mrp = excluded.mrp,
                discount = excluded.discount, image = COALESCE(excluded.image, offers.image),
                url = excluded.url, links = excluded.links, text = excluded.text
            """,
            (source_key, key, source, offer["title"], offer.get("tag"), offer["store"], offer.get("price"),
             offer.get("mrp"), offer.get("discount"), image, offer["url"], json.dumps(offer["links"]),
             offer["text"], posted_at, int(offer.get("weak_title", False)), offer.get("category", "Other"),
             int(offer.get("expired", False))),
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


def expired_images() -> list[str]:
    """Images of the deals the next purge_old() removes (to delete them from the image store)."""
    cutoff = int(time.time()) - KEEP_DAYS * 86400
    with _lock:
        return [r[0] for r in _conn.execute(
            "SELECT image FROM offers WHERE posted_at < ? AND image IS NOT NULL AND image != ''", (cutoff,))]


def restore_offer(doc: dict, doc_hash: str) -> None:
    """Re-creates a deal from its Firestore document (see firebase_publisher.py), marked as
    already published and announced."""
    with _lock:
        _conn.execute(
            """INSERT OR REPLACE INTO offers (source_key, dedupe_key, source, title, tag, store, price, mrp,
                   discount, image, url, links, text, posted_at, weak_title, category, expired)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (doc["key"], doc.get("dedupe") or "", doc["source"], doc["title"], doc.get("tag"), doc["store"],
             doc.get("price"), doc.get("mrp"), doc.get("discount"), doc.get("raw_image"), doc["url"],
             json.dumps(doc.get("links") or []), doc.get("text") or "", doc["posted_at"], doc.get("weak") or 0,
             doc.get("category") or "Other", int(bool(doc.get("expired")))),
        )
        _conn.execute(
            "INSERT OR REPLACE INTO published (source_key, hash, image_blob, notified) VALUES (?, ?, NULL, 1)",
            (doc["key"], doc_hash),
        )
        _conn.commit()


def reparse_all(parse) -> int:
    """Re-reads every saved post with the current parser (after a parser fix). A product name
    already found for a vague title is kept. Returns how many deals changed."""
    changed = 0
    with _lock:
        rows = _conn.execute("SELECT * FROM offers").fetchall()
        for row in rows:
            offer = parse(row["text"])
            if offer is None:
                continue
            # A title from a lookup, or a placeholder with the store a redirect revealed, stays.
            kept_name = row["weak_title"] != 0 and offer["weak_title"]
            title = row["title"] if kept_name else offer["title"]
            category = categorize(title, row["text"]) if kept_name else offer["category"]
            weak = row["weak_title"] or int(offer["weak_title"])  # newly vague: gets a name lookup
            new = (title, offer["tag"], offer["price"], offer["mrp"], offer["discount"], category,
                   int(offer["expired"]), weak)
            if new != (row["title"], row["tag"], row["price"], row["mrp"], row["discount"], row["category"],
                       row["expired"], row["weak_title"]):
                _conn.execute(
                    """UPDATE offers SET title = ?, tag = ?, price = ?, mrp = ?, discount = ?, category = ?,
                       expired = ?, weak_title = ? WHERE id = ?""",
                    (*new, row["id"]),
                )
                changed += 1
        _conn.commit()
    return changed


def all_channels() -> list[dict]:
    with _lock:
        return [dict(r) for r in _conn.execute("SELECT * FROM channels")]


def purge_old() -> int:
    cutoff = int(time.time()) - KEEP_DAYS * 86400
    with _lock:
        cur = _conn.execute("DELETE FROM offers WHERE posted_at < ?", (cutoff,))
        _conn.commit()
        return cur.rowcount


def _row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["links"] = json.loads(d["links"])
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
        return [_public(r) for r in _conn.execute(sql, args).fetchall()]


def all_offers() -> list[dict]:
    with _lock:
        rows = _conn.execute(f"SELECT * FROM offers ORDER BY {SORTS['latest']}").fetchall()
    return [_public(r) for r in rows]


def _public(row: sqlite3.Row) -> dict:
    """What the app sees: no internal bookkeeping columns."""
    d = _row_to_dict(row)
    d.pop("weak_title", None)
    d.pop("dedupe_key", None)
    return d


def referenced_images() -> set[str]:
    with _lock:
        return {r[0] for r in _conn.execute("SELECT image FROM offers WHERE image IS NOT NULL")}


def offers_needing_lookup(limit: int) -> list[dict]:
    """Deals with no image tried yet, or a vague title still waiting for a product name."""
    with _lock:
        rows = _conn.execute(
            f"SELECT * FROM offers WHERE image IS NULL OR weak_title = 1 ORDER BY {SORTS['latest']} LIMIT ?",
            (limit,),
        ).fetchall()
    return [_row_to_dict(r) for r in rows]


def save_lookup(source_key: str, image: str | None, title: str | None) -> None:
    """image: "/media/..." found, "" tried without luck, None = unchanged.
    title: a product name for a vague title (None = keep the placeholder)."""
    with _lock:
        if image is not None:
            _conn.execute("UPDATE offers SET image = ? WHERE source_key = ?", (image, source_key))
        row = _conn.execute("SELECT weak_title, text FROM offers WHERE source_key = ?", (source_key,)).fetchone()
        if row and row["weak_title"] == 1:
            _conn.execute(
                "UPDATE offers SET title = COALESCE(?, title), weak_title = 2, category = ? WHERE source_key = ?",
                (title, categorize(title, row["text"]) if title else categorize("", row["text"]), source_key),
            )
        _conn.commit()


def mark_weak_titles(is_weak, placeholder) -> int:
    """One-time pass for deals saved before vague titles were detected."""
    with _lock:
        rows = _conn.execute("SELECT source_key, title, store FROM offers WHERE weak_title = 0").fetchall()
        weak = [(placeholder(r["store"]), r["source_key"]) for r in rows if is_weak(r["title"])]
        _conn.executemany("UPDATE offers SET title = ?, weak_title = 1 WHERE source_key = ?", weak)
        _conn.commit()
    return len(weak)


def get_offer(source_key: str) -> dict | None:
    with _lock:
        row = _conn.execute("SELECT * FROM offers WHERE source_key = ?", (source_key,)).fetchone()
    return _row_to_dict(row) if row else None


def all_offer_rows() -> list[dict]:
    with _lock:
        rows = _conn.execute(f"SELECT * FROM offers ORDER BY {SORTS['latest']}").fetchall()
    return [_row_to_dict(r) for r in rows]


def published_state() -> dict[str, dict]:
    with _lock:
        return {r["source_key"]: dict(r) for r in _conn.execute("SELECT * FROM published")}


def set_published(source_key: str, doc_hash: str, image_blob: str | None) -> None:
    with _lock:
        _conn.execute(
            """INSERT INTO published (source_key, hash, image_blob) VALUES (?, ?, ?)
               ON CONFLICT(source_key) DO UPDATE SET hash = excluded.hash,
                   image_blob = COALESCE(excluded.image_blob, published.image_blob)""",
            (source_key, doc_hash, image_blob),
        )
        _conn.commit()


def mark_notified(source_key: str) -> bool:
    """True the first time it's called for a deal (so each deal is announced once)."""
    with _lock:
        cur = _conn.execute(
            "UPDATE published SET notified = 1 WHERE source_key = ? AND notified = 0", (source_key,)
        )
        _conn.commit()
        return cur.rowcount == 1


def forget_published(source_key: str) -> None:
    with _lock:
        _conn.execute("DELETE FROM published WHERE source_key = ?", (source_key,))
        _conn.commit()


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
