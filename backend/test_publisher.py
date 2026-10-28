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


class FakeSnap:
    def __init__(self, data):
        self._data = data
        self.exists = data is not None

    def to_dict(self):
        return dict(self._data) if self._data is not None else None


class FakeDoc:
    def __init__(self, coll, key):
        self.coll, self.key = coll, key

    def set(self, data):
        self.coll.writes.append(self.key)
        self.coll.docs[self.key] = dict(data)

    def get(self):
        return FakeSnap(self.coll.docs.get(self.key))

    def delete(self):
        self.coll.docs.pop(self.key, None)


class FakeCollection:
    def __init__(self):
        self.docs, self.writes = {}, []

    def document(self, key):
        return FakeDoc(self, key)

    def stream(self):
        return [FakeSnap(d) for d in self.docs.values()]


class FakeFirestore:
    def __init__(self):
        self.collections = {}

    def collection(self, name):
        return self.collections.setdefault(name, FakeCollection())


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
    fs, msg = FakeFirestore(), FakeMessaging()
    pub = Publisher(fs, msg)
    offers = fs.collection("offers")
    now = int(time.time())
    image = "https://res.cloudinary.com/demo/image/upload/v1/offershub/tg_1_1.jpg"

    save("tg:-1:1", "GRAB : Philips Cooktop at 2,997. https://amzn.to/a", now, image)
    await pub.publish("tg:-1:1", notify=False)   # first save, lookups pending
    await pub.publish("tg:-1:1", notify=True)    # final
    await pub.publish("tg:-1:1", notify=True)    # e.g. an edit with no change
    doc = offers.docs[doc_id("tg:-1:1")]
    assert doc["title"] == "Philips Cooktop" and doc["price"] == 2997 and doc["image"] == image
    assert offers.writes.count(doc_id("tg:-1:1")) == 1, "unchanged deal must not be rewritten"
    assert len(msg.sent) == 1 and msg.sent[0]["data"]["price"] == "2997", "exactly one notification"

    save("tg:-1:2", "Loot : Old deal @99 https://fkrt.it/b", now - 3600, "/media/local.jpg")
    await pub.publish("tg:-1:2", notify=True)
    assert len(msg.sent) == 1, "deals older than 15 minutes are not announced"
    assert offers.docs[doc_id("tg:-1:2")]["image"] is None, "local paths are not public links"

    db.save_channel("https://t.me/+abc", -1, "Offerzone", 42)
    await pub.full_sync()
    assert fs.collection("state").docs["channels"]["channels"]["https://t.me/+abc"]["last_id"] == 42

    # A restart on a wiped disk: rebuild from Firestore, nothing is announced again.
    db.delete_offers(["tg:-1:1", "tg:-1:2"])
    for key in list(db.published_state()):
        db.forget_published(key)
    db._conn.execute("DELETE FROM channels")
    db._conn.commit()
    await pub.restore_if_empty()
    assert db.get_offer("tg:-1:1")["title"] == "Philips Cooktop"
    assert db.get_channel("https://t.me/+abc")["last_id"] == 42
    writes_before = len(offers.writes)
    await pub.full_sync()
    assert len(offers.writes) == writes_before, "restored deals must not be rewritten"
    await pub.publish("tg:-1:1", notify=True)
    assert len(msg.sent) == 1, "restored deals must not be announced again"

    db.delete_offers(["tg:-1:1"])
    await pub.full_sync()
    assert doc_id("tg:-1:1") not in offers.docs and doc_id("tg:-1:2") in offers.docs
    print("ok publisher")


asyncio.run(main())
