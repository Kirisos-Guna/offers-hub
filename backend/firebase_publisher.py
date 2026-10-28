"""Pushes deals from the local database to Firebase, where the app reads them live.

- Firestore `offers/<id>`: one document per deal; the app listens to this collection, so a
  new deal appears on open screens within a second or two.
- Storage `media/<file>.jpg`: deal images, served through Firebase download-token URLs.
- Cloud Messaging topic `deals`: one high-priority data message per new deal, so phones can
  show a notification even when the app is closed.

The local SQLite table `published` remembers what was written, so restarts only send changes.
"""
import asyncio
import hashlib
import json
import logging
import time
import uuid
from datetime import timedelta
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

import db
from config import MEDIA_DIR

log = logging.getLogger("firebase")

COLLECTION = "offers"
TOPIC = "deals"
# Deals posted longer ago than this are published but not announced (catch-up after a restart).
NOTIFY_WITHIN_SECONDS = 15 * 60


def doc_id(source_key: str) -> str:
    return source_key.replace(":", "_")


class Publisher:
    def __init__(self, firestore_client, bucket, messaging):
        self._fs = firestore_client
        self._bucket = bucket
        self._messaging = messaging
        self._lock = asyncio.Lock()

    @classmethod
    def from_environment(cls, bucket_name: str) -> "Publisher":
        """Uses Application Default Credentials: the VM's service account in production, or
        GOOGLE_APPLICATION_CREDENTIALS pointing at a key file elsewhere."""
        import firebase_admin
        from firebase_admin import firestore, messaging, storage

        if not firebase_admin._apps:
            firebase_admin.initialize_app(options={"storageBucket": bucket_name})
        return cls(firestore.client(), storage.bucket(), messaging)

    # --- images -----------------------------------------------------------------------------

    def _upload_image(self, local: str) -> str | None:
        """Uploads /media/<file> and returns its public download URL."""
        path = MEDIA_DIR / Path(local).name
        if not path.exists():
            return None
        blob_name = f"media/{path.name}"
        token = str(uuid.uuid4())
        blob = self._bucket.blob(blob_name)
        blob.metadata = {"firebaseStorageDownloadTokens": token}
        blob.cache_control = "public, max-age=604800"
        blob.upload_from_filename(str(path), content_type="image/jpeg")
        return (f"https://firebasestorage.googleapis.com/v0/b/{self._bucket.name}/o/"
                f"{quote(blob_name, safe='')}?alt=media&token={token}")

    def _delete_image(self, url: str | None) -> None:
        if not url or "/o/" not in url:
            return
        blob_name = unquote(urlparse(url).path.split("/o/", 1)[1])
        try:
            self._bucket.blob(blob_name).delete()
        except Exception as exc:  # already gone is fine
            log.debug("image delete %s: %s", blob_name, exc)

    # --- documents --------------------------------------------------------------------------

    @staticmethod
    def _document(row: dict, image_url: str | None) -> dict:
        return {
            "source": row["source"],
            "title": row["title"],
            "tag": row["tag"],
            "store": row["store"],
            "price": row["price"],
            "mrp": row["mrp"],
            "discount": row["discount"],
            "image": image_url,
            "url": row["url"],
            "links": row["links"],
            "text": row["text"],
            "posted_at": row["posted_at"],
        }

    def _publish_row(self, row: dict, state: dict | None) -> tuple[dict, str | None, bool]:
        """Writes the deal if it changed. Returns (document, image URL, written?)."""
        image_url = state.get("image_blob") if state else None
        local = row["image"] or ""
        if local.startswith("/media/") and (not image_url or Path(local).name not in unquote(image_url)):
            image_url = self._upload_image(local) or image_url
        elif local.startswith("http"):
            image_url = local
        doc = self._document(row, image_url)
        doc_hash = hashlib.sha1(json.dumps(doc, sort_keys=True).encode()).hexdigest()
        if state and state["hash"] == doc_hash:
            return doc, image_url, False
        self._fs.collection(COLLECTION).document(doc_id(row["source_key"])).set(doc)
        db.set_published(row["source_key"], doc_hash, image_url)
        return doc, image_url, True

    def _notify(self, key: str, doc: dict) -> None:
        if time.time() - doc["posted_at"] > NOTIFY_WITHIN_SECONDS or not db.mark_notified(key):
            return
        data = {
            "id": doc_id(key),
            "title": doc["title"],
            "store": doc["store"],
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
        self._messaging.send(message)
        log.info("notified: %s", doc["title"][:60])

    def _delete(self, key: str, state: dict) -> None:
        self._fs.collection(COLLECTION).document(doc_id(key)).delete()
        self._delete_image(state.get("image_blob"))
        db.forget_published(key)

    # --- public API (async; Firebase clients are blocking) ----------------------------------

    async def publish(self, source_key: str, notify: bool) -> None:
        """Publishes one deal right after it was saved; announces it when notify is set."""
        async with self._lock:
            row = db.get_offer(source_key)
            if row is None:
                return
            state = db.published_state().get(source_key)
            try:
                doc, _, _ = await asyncio.to_thread(self._publish_row, row, state)
                if notify:
                    await asyncio.to_thread(self._notify, source_key, doc)
            except Exception:
                log.exception("publishing %s failed", source_key)

    async def remove(self, source_keys: list[str]) -> None:
        async with self._lock:
            states = db.published_state()
            for key in source_keys:
                if key in states:
                    await asyncio.to_thread(self._delete, key, states[key])

    async def full_sync(self) -> None:
        """Brings Firebase in line with the database: writes changed deals, deletes gone ones."""
        async with self._lock:
            states = db.published_state()
            rows = db.all_offer_rows()
            written = 0
            for row in rows:
                try:
                    _, _, changed = await asyncio.to_thread(self._publish_row, row, states.get(row["source_key"]))
                    written += changed
                except Exception:
                    log.exception("publishing %s failed", row["source_key"])
            live = {row["source_key"] for row in rows}
            gone = [key for key in states if key not in live]
            for key in gone:
                try:
                    await asyncio.to_thread(self._delete, key, states[key])
                except Exception:
                    log.exception("deleting %s failed", key)
            if written or gone:
                log.info("firebase sync: %d written, %d deleted, %d live", written, len(gone), len(live))
