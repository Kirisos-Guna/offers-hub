"""Run: python test_publisher.py  - checks firebase_publisher against in-memory fakes."""
import asyncio
import os
import tempfile
import time
from pathlib import Path

tmp = Path(tempfile.mkdtemp())
os.environ["DB_PATH"] = str(tmp / "test.db")
os.environ["MEDIA_DIR"] = str(tmp / "media")

import db  # noqa: E402
from firebase_publisher import Publisher, doc_id  # noqa: E402
from offer_parser import parse_offer  # noqa: E402


class FakeDoc:
    def __init__(self, store, key):
        self.store, self.key = store, key

    def set(self, data):
        self.store.writes.append(self.key)
        self.store.docs[self.key] = data

    def delete(self):
        self.store.docs.pop(self.key, None)


class FakeFirestore:
    def __init__(self):
        self.docs, self.writes = {}, []

    def collection(self, _):
        return self

    def document(self, key):
        return FakeDoc(self, key)


class FakeBlob:
    def __init__(self, bucket, name):
        self.bucket, self.name, self.metadata, self.cache_control = bucket, name, None, None

    def upload_from_filename(self, path, content_type):
        self.bucket.files.add(self.name)

    def delete(self):
        self.bucket.files.discard(self.name)


class FakeBucket:
    name = "demo.firebasestorage.app"

    def __init__(self):
        self.files = set()

    def blob(self, name):
        return FakeBlob(self, name)


class FakeMessaging:
    def __init__(self):
        self.sent = []

    def Message(self, **kw):
        return kw

    def AndroidConfig(self, **kw):
        return kw

    def send(self, message):
        self.sent.append(message)


def save(key, text, posted_at, image=None):
    assert db.upsert_offer(key, "Offerzone", parse_offer(text), posted_at, image)


async def main():
    fs, bucket, msg = FakeFirestore(), FakeBucket(), FakeMessaging()
    pub = Publisher(fs, bucket, msg)
    now = int(time.time())
    (tmp / "media").mkdir(exist_ok=True)
    (tmp / "media" / "tg_1_1.jpg").write_bytes(b"jpg")

    save("tg:-1:1", "GRAB : Philips Cooktop at 2,997. https://amzn.to/a", now, "/media/tg_1_1.jpg")
    await pub.publish("tg:-1:1", notify=False)   # first save, lookups pending
    await pub.publish("tg:-1:1", notify=True)    # final
    await pub.publish("tg:-1:1", notify=True)    # e.g. an edit with no change
    doc = fs.docs[doc_id("tg:-1:1")]
    assert doc["title"] == "Philips Cooktop" and doc["price"] == 2997
    assert doc["image"].startswith("https://firebasestorage.googleapis.com/v0/b/demo.firebasestorage.app/o/media%2Ftg_1_1.jpg")
    assert fs.writes.count(doc_id("tg:-1:1")) == 1, "unchanged deal must not be rewritten"
    assert len(msg.sent) == 1 and msg.sent[0]["data"]["price"] == "2997", "exactly one notification"
    assert "media/tg_1_1.jpg" in bucket.files

    save("tg:-1:2", "Loot : Old deal @99 https://fkrt.it/b", now - 3600)
    await pub.publish("tg:-1:2", notify=True)
    assert len(msg.sent) == 1, "deals older than 15 minutes are not announced"

    db.delete_offers(["tg:-1:1"])
    await pub.full_sync()
    assert doc_id("tg:-1:1") not in fs.docs and "media/tg_1_1.jpg" not in bucket.files
    assert doc_id("tg:-1:2") in fs.docs
    print("ok publisher")


asyncio.run(main())
