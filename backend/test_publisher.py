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
    # An edit while lookups fail keeps the image found earlier.
    import datetime
    import types
    import product_images
    import telegram_source as t
    save("tg:-1:3", "Loot : Fan at 999 https://amzn.to/c", now)
    db.save_lookup("tg:-1:3", "https://res.cloudinary.com/x/good.jpg", None)

    async def miss(*a, **k):
        return None, None
    product_images.lookup = miss
    edited = types.SimpleNamespace(message="Loot : Fan at 899 https://amzn.to/c", entities=None, reply_markup=None,
                                   photo=None, media=None, chat_id=-1, id=3,
                                   date=datetime.datetime.now(datetime.timezone.utc))
    await t.handle_message(edited, "Offerzone")
    assert db.get_offer("tg:-1:3")["image"] == "https://res.cloudinary.com/x/good.jpg"
    assert db.get_offer("tg:-1:3")["price"] == 899

    # A failed feed write still sends the alert, and a retry covers a dropped request.
    class Flaky(FakeMessaging):
        calls = 0

        def send(self, message):
            Flaky.calls += 1
            if Flaky.calls == 1:
                raise ConnectionError("dropped")
            super().send(message)

    class BrokenDoc(FakeDoc):
        def set(self, data):
            raise ConnectionError("firestore down")

    class BrokenCollection(FakeCollection):
        def document(self, key):
            return BrokenDoc(self, key)

    class BrokenFirestore(FakeFirestore):
        def collection(self, name):
            return self.collections.setdefault(name, BrokenCollection())

    flaky = Flaky()
    broken = Publisher(BrokenFirestore(), flaky)
    save("tg:-1:4", "GRAB : Mixer at 1,999 https://amzn.to/d", now)
    await broken.publish("tg:-1:4", notify=True)
    assert len(flaky.sent) == 1 and Flaky.calls == 2

    # A sister channel's "over" edit marks the deal we kept as ended.
    save("tg:-1:5", "GRAB : Kettle at 599 https://amzn.to/e", now)
    repost = {**parse_offer("GRAB : Kettle at 599 https://amzn.to/e"), "expired": True}
    assert not db.upsert_offer("tg:-2:9", "Other", repost, now, None)
    assert db.get_offer("tg:-1:5")["expired"] == 1

    print("ok publisher")


asyncio.run(main())
