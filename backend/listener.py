"""Always-on deal listener (runs on the VM under systemd, see deploy/).

    python listener.py

Stays connected to Telegram and pushes each new deal to Firebase as it is posted:
Firestore for the app's live feed and a Cloud Messaging notification to phones. Every minute
it also re-reads the channels as a safety net in case an update was missed, and once an hour
it removes expired deals.

Needs in backend/.env: TG_API_ID, TG_API_HASH, TG_SESSION_STRING and FIREBASE_BUCKET.
"""
import asyncio
import logging
import os
import signal
from pathlib import Path

from telethon import events, utils

import db
import telegram_source
from config import MEDIA_DIR, TG_API_HASH, TG_API_ID, TG_SESSION_STRING
from firebase_publisher import Publisher
from offer_parser import is_weak_title

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("listener")

SAFETY_POLL_SECONDS = 60
PURGE_SECONDS = 3600
LOOKUP_BACKLOG = 400


def prune_local_images() -> None:
    keep = {Path(p).name for p in db.referenced_images()}
    for f in MEDIA_DIR.glob("*.jpg"):
        if f.name not in keep:
            f.unlink()


async def main() -> None:
    if not (TG_API_ID and TG_API_HASH):
        raise SystemExit("Set TG_API_ID and TG_API_HASH in backend/.env")
    bucket = os.environ.get("FIREBASE_BUCKET", "")
    if not bucket:
        raise SystemExit("Set FIREBASE_BUCKET in backend/.env (e.g. your-project.firebasestorage.app)")
    publisher = Publisher.from_environment(bucket)

    client = telegram_source.make_client()
    await client.connect()
    if not await client.is_user_authorized():
        raise SystemExit("Telegram session is not logged in" +
                         ("" if TG_SESSION_STRING else " - set TG_SESSION_STRING"))

    async def on_saved(source_key: str, final: bool) -> None:
        await publisher.publish(source_key, notify=final)

    opened = await telegram_source.open_channels(client)
    if not opened:
        raise SystemExit("No channels could be opened")
    chats = [chat for _, chat in opened]
    titles = {utils.get_peer_id(chat): chat.title for chat in chats}

    # Register live handlers first so nothing posted during the catch-up is lost.
    @client.on(events.NewMessage(chats=chats))
    @client.on(events.MessageEdited(chats=chats))
    async def on_post(event):
        await telegram_source.handle_message(event.message, titles.get(event.chat_id, "Telegram"), on_saved)

    @client.on(events.MessageDeleted(chats=chats))
    async def on_delete(event):
        if event.chat_id:
            keys = [f"tg:{event.chat_id}:{i}" for i in event.deleted_ids]
            db.delete_offers(keys)
            await publisher.remove(keys)

    log.info("catching up on %d channels", len(opened))
    for link, chat in opened:
        await telegram_source.sync_channel(client, link, chat, on_saved)
    db.mark_weak_titles(is_weak_title, telegram_source.placeholder_title)
    await publisher.full_sync()
    log.info("live: listening for new deals")

    async def safety_poll():
        while True:
            await asyncio.sleep(SAFETY_POLL_SECONDS)
            for link, chat in opened:
                try:
                    await telegram_source.sync_channel(client, link, chat, on_saved)
                except Exception:
                    log.exception("safety poll of %s failed", chat.title)

    async def housekeeping():
        while True:
            # Older deals still missing an image or a product name (e.g. after a lookup outage).
            for offer in db.offers_needing_lookup(LOOKUP_BACKLOG):
                await telegram_source.enrich_from_link(
                    offer["source_key"], offer["links"], need_image=offer["image"] is None,
                    need_title=offer["weak_title"] == 1)
            removed = db.purge_old()
            prune_local_images()
            await publisher.full_sync()
            if removed:
                log.info("expired %d deals", removed)
            await asyncio.sleep(PURGE_SECONDS)

    tasks = [asyncio.create_task(safety_poll()), asyncio.create_task(housekeeping())]
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:  # Windows
            pass
    disconnected = asyncio.ensure_future(client.disconnected)
    await asyncio.wait([disconnected, asyncio.ensure_future(stop.wait())], return_when=asyncio.FIRST_COMPLETED)
    for task in tasks:
        task.cancel()
    await client.disconnect()
    db.close()
    if not stop.is_set():
        raise SystemExit("Telegram connection lost; exiting so systemd restarts the listener")


if __name__ == "__main__":
    asyncio.run(main())
