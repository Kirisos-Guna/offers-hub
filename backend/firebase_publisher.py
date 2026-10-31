"""Pushes deals from the local database to Firebase (works on the free Spark plan).

- Firestore `offers/<id>`: one document per deal; the app listens to this collection, so a
  new deal appears on open screens within a second or two.
- Cloud Messaging topic `deals`: one high-priority data message per new deal, so phones can
  show a notification even when the app is closed.
- Firestore `state/channels`: where each channel was read up to. Together with the offers
  themselves this lets a listener whose disk was wiped (Render restarts) pick up exactly
  where it left off, without duplicate notifications.

Images are already public HTTPS links (image_store.py), so nothing is uploaded here.
The local SQLite table `published` remembers what was written, so only changes are sent.
"""
import asyncio
import hashlib
import json
import logging
import os
import time
from datetime import timedelta

import db

log = logging.getLogger("firebase")

COLLECTION = "offers"
TOPIC = "deals"
# Deals posted longer ago than this are published but not announced (catch-up after a restart).
NOTIFY_WITHIN_SECONDS = 15 * 60
NOTIFY_ATTEMPTS = 3


def doc_id(source_key: str) -> str:
    return source_key.replace(":", "_")


def _hash(doc: dict) -> str:
    return hashlib.sha1(json.dumps(doc, sort_keys=True).encode()).hexdigest()


class Publisher:
    def __init__(self, firestore_client, messaging):
        self._fs = firestore_client
        self._messaging = messaging
        self._lock = asyncio.Lock()
        self._saved_channels: dict | None = None

    @classmethod
    def from_environment(cls) -> "Publisher":
        """FIREBASE_SERVICE_ACCOUNT holds the service-account key JSON (Firebase console →
        Project settings → Service accounts → Generate new private key). Without it,
        Application Default Credentials are used."""
        import firebase_admin
        from firebase_admin import credentials, firestore, messaging

        if not firebase_admin._apps:
            key = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
            cred = credentials.Certificate(json.loads(key)) if key else credentials.ApplicationDefault()
            firebase_admin.initialize_app(cred)
        return cls(firestore.client(), messaging)

    # --- documents --------------------------------------------------------------------------

    @staticmethod
    def _document(row: dict) -> dict:
        image = row["image"] or ""
        return {
            "source": row["source"],
            "title": row["title"],
            "tag": row["tag"],
            "store": row["store"],
            "price": row["price"],
            "mrp": row["mrp"],
            "discount": row["discount"],
            "image": image if image.startswith("http") else None,
            "url": row["url"],
            "links": row["links"],
            "text": row["text"],
            "posted_at": row["posted_at"],
            "category": row.get("category") or "Other",
            "expired": bool(row.get("expired")),
            # Listener bookkeeping, used to rebuild its database after a restart.
            "key": row["source_key"],
            "dedupe": row["dedupe_key"],
            "weak": row["weak_title"],
            "raw_image": row["image"],
        }

    def _publish_row(self, row: dict, state: dict | None) -> tuple[dict, bool]:
        doc = self._document(row)
        doc_hash = _hash(doc)
        if state and state["hash"] == doc_hash:
            return doc, False
        self._fs.collection(COLLECTION).document(doc_id(row["source_key"])).set(doc)
        db.set_published(row["source_key"], doc_hash, None)
        return doc, True

    def _notify(self, key: str, doc: dict) -> None:
        if doc["expired"] or time.time() - doc["posted_at"] > NOTIFY_WITHIN_SECONDS or not db.mark_notified(key):
            return
        data = {
            "id": doc_id(key),
            "title": doc["title"],
            "store": doc["store"],
            "category": doc["category"],
            "text": doc["text"][:500],  # lets phones match the user's alert keywords
            "price": "" if doc["price"] is None else f"{doc['price']:g}",
            "mrp": "" if doc["mrp"] is None else f"{doc['mrp']:g}",
            "discount": "" if doc["discount"] is None else str(doc["discount"]),
            "image": doc["image"] or "",
            "url": doc["url"],
            "posted_at": str(doc["posted_at"]),
        }
        message = self._messaging.Message(
            topic=TOPIC,
            data=data,
            android=self._messaging.AndroidConfig(priority="high", ttl=timedelta(hours=2)),
        )
        for attempt in range(NOTIFY_ATTEMPTS):  # a dropped request must not cost the user a deal
            try:
                self._messaging.send(message)
                log.info("notified: %s", doc["title"][:60])
                return
            except Exception:
                if attempt == NOTIFY_ATTEMPTS - 1:
                    raise
                time.sleep(2 * (attempt + 1))

    def _delete(self, key: str) -> None:
        self._fs.collection(COLLECTION).document(doc_id(key)).delete()
        db.forget_published(key)

    def _save_channels(self) -> None:
        channels = {c["link"]: {"peer_id": c["peer_id"], "title": c["title"], "last_id": c["last_id"]}
                    for c in db.all_channels()}
        if channels != self._saved_channels:
            self._fs.collection("state").document("channels").set({"channels": channels})
            self._saved_channels = channels

    def _restore(self) -> int:
        """Rebuilds an empty local database from Firestore. Returns the deals restored."""
        state = self._fs.collection("state").document("channels").get()
        if state.exists:
            for link, c in (state.to_dict() or {}).get("channels", {}).items():
                db.save_channel(link, c["peer_id"], c["title"], c["last_id"])
        restored = 0
        for snap in self._fs.collection(COLLECTION).stream():
            doc = snap.to_dict() or {}
            if not doc.get("key"):
                continue
            db.restore_offer(doc, _hash(doc))
            restored += 1
        self._saved_channels = None
        return restored

    # --- public API (async; Firebase clients are blocking) ----------------------------------

    async def restore_if_empty(self) -> None:
        if db.all_offer_rows() or db.all_channels():
            return
        restored = await asyncio.to_thread(self._restore)
        log.info("restored %d deals and channel positions from Firestore", restored)

    async def publish(self, source_key: str, notify: bool) -> None:
        """Publishes one deal right after it was saved; announces it when notify is set."""
        async with self._lock:
            row = db.get_offer(source_key)
            if row is None:
                return
            try:
                await asyncio.to_thread(self._publish_row, row, db.published_state().get(source_key))
            except Exception:
                log.exception("publishing %s failed", source_key)
                db.set_published(source_key, "", None)  # the next full_sync writes it
            if notify:  # the alert still goes out; a feed hiccup shouldn't cost it
                try:
                    await asyncio.to_thread(self._notify, source_key, self._document(row))
                except Exception:
                    log.exception("notifying %s failed", source_key)

    async def remove(self, source_keys: list[str]) -> None:
        async with self._lock:
            states = db.published_state()
            for key in source_keys:
                if key in states:
                    await asyncio.to_thread(self._delete, key)

    async def save_channels(self) -> None:
        try:
            await asyncio.to_thread(self._save_channels)
        except Exception:
            log.exception("saving channel positions failed")

    async def full_sync(self) -> None:
        """Brings Firebase in line with the database: writes changed deals, deletes gone ones."""
        states = db.published_state()
        rows = db.all_offer_rows()
        written = 0
        # The lock is taken per deal: a long sync must not hold back new deals and their alerts.
        for row in rows:
            async with self._lock:
                try:
                    _, changed = await asyncio.to_thread(self._publish_row, row, states.get(row["source_key"]))
                    written += changed
                except Exception:
                    log.exception("publishing %s failed", row["source_key"])
        live = {row["source_key"] for row in rows}
        gone = [key for key in states if key not in live]
        for key in gone:
            async with self._lock:
                try:
                    await asyncio.to_thread(self._delete, key)
                except Exception:
                    log.exception("deleting %s failed", key)
        if written or gone:
            log.info("firebase sync: %d written, %d deleted, %d live", written, len(gone), len(live))
        await self.save_channels()
