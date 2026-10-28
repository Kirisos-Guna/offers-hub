"""Always-on deal listener (runs on Render's free plan, see render.yaml).

    python listener.py

Stays connected to Telegram and pushes each new deal to Firebase as it is posted:
Firestore for the app's live feed and a Cloud Messaging notification to phones. Every minute
it also re-reads the channels as a safety net in case an update was missed, and once an hour
it removes expired deals. A small status page on $PORT keeps the host awake when it is
pinged and shows that the listener is alive.

Settings (environment variables): TG_API_ID, TG_API_HASH, TG_SESSION_STRING,
FIREBASE_SERVICE_ACCOUNT, CLOUDINARY_URL.
"""
import asyncio
import json
import logging
import os
import signal
import time

from telethon import events, utils

import db
import telegram_source
from config import TG_API_HASH, TG_API_ID, TG_SESSION_STRING
from firebase_publisher import Publisher
from image_store import CloudinaryStore, store as image_store
from offer_parser import is_weak_title

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("listener")

SAFETY_POLL_SECONDS = 60
HOUSEKEEPING_SECONDS = 3600
LOOKUP_BACKLOG = 400

status = {"state": "starting", "started_at": int(time.time()), "last_deal_at": None, "channels": 0}


async def serve_status(port: int) -> asyncio.AbstractServer:
    """Minimal HTTP endpoint: the host needs an open port, and pings keep the service awake."""
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        except Exception:
            pass
        body = json.dumps({**status, "deals": len(db.all_offer_rows())}).encode()
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                     + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)
        try:
            await writer.drain()
        finally:
            writer.close()

    return await asyncio.start_server(handle, "0.0.0.0", port)


async def main() -> None:
    if not (TG_API_ID and TG_API_HASH and TG_SESSION_STRING):
        raise SystemExit("Set TG_API_ID, TG_API_HASH and TG_SESSION_STRING")
    if not isinstance(image_store, CloudinaryStore):
        raise SystemExit("Set CLOUDINARY_URL: the listener's own disk doesn't keep images")
    publisher = Publisher.from_environment()
    await publisher.restore_if_empty()

    client = telegram_source.make_client()
    await client.connect()
    if not await client.is_user_authorized():
        raise SystemExit("Telegram session is not logged in - create a new TG_SESSION_STRING")

    async def on_saved(source_key: str, final: bool) -> None:
        await publisher.publish(source_key, notify=final)
        status["last_deal_at"] = int(time.time())

    opened = await telegram_source.open_channels(client)
    if not opened:
        raise SystemExit("No channels could be opened")
    chats = [chat for _, chat in opened]
    titles = {utils.get_peer_id(chat): chat.title for chat in chats}
    status["channels"] = len(chats)

    # Register live handlers first so nothing posted during the catch-up is lost.
    @client.on(events.NewMessage(chats=chats))
    @client.on(events.MessageEdited(chats=chats))
    async def on_post(event):
        await telegram_source.handle_message(event.message, titles.get(event.chat_id, "Telegram"), on_saved)

    @client.on(events.MessageDeleted(chats=chats))
    async def on_delete(event):
        if not event.chat_id:
            return
        keys = [f"tg:{event.chat_id}:{i}" for i in event.deleted_ids]
        for key in keys:
            row = db.get_offer(key)
            if row:
                await image_store.delete(row["image"])
        db.delete_offers(keys)
        await publisher.remove(keys)

    status["state"] = "catching up"
    log.info("catching up on %d channels", len(opened))
    for link, chat in opened:
        await telegram_source.sync_channel(client, link, chat, on_saved)
    db.mark_weak_titles(is_weak_title, telegram_source.placeholder_title)
    await publisher.full_sync()
    status["state"] = "live"
    log.info("live: listening for new deals")

    async def safety_poll():
        while True:
            await asyncio.sleep(SAFETY_POLL_SECONDS)
            for link, chat in opened:
                try:
                    await telegram_source.sync_channel(client, link, chat, on_saved)
                except Exception:
                    log.exception("safety poll of %s failed", chat.title)
            await publisher.save_channels()

    async def housekeeping():
        while True:
            # Older deals still missing an image or a product name (e.g. after a lookup outage).
            for offer in db.offers_needing_lookup(LOOKUP_BACKLOG):
                await telegram_source.enrich_from_link(
                    offer["source_key"], offer["links"], need_image=offer["image"] is None,
                    need_title=offer["weak_title"] == 1)
            for image in db.expired_images():
                await image_store.delete(image)
            removed = db.purge_old()
            await publisher.full_sync()
            if removed:
                log.info("expired %d deals", removed)
            await asyncio.sleep(HOUSEKEEPING_SECONDS)

    tasks = [asyncio.create_task(safety_poll()), asyncio.create_task(housekeeping())]
    try:
        disconnected = asyncio.ensure_future(client.disconnected)
        await asyncio.wait([disconnected, asyncio.ensure_future(stop.wait())],
                           return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await client.disconnect()
    if not stop.is_set():
        raise ConnectionError("Telegram connection lost")


stop = asyncio.Event()


async def run_forever() -> None:
    """Reconnects by itself instead of exiting, so a dropped connection never needs a redeploy."""
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:  # Windows
            pass
    server = await serve_status(int(os.environ.get("PORT", "7860")))
    try:
        while not stop.is_set():
            try:
                await main()
            except Exception:
                status["state"] = "reconnecting"
                log.exception("listener stopped; restarting in 30 s")
                try:
                    await asyncio.wait_for(stop.wait(), timeout=30)
                except asyncio.TimeoutError:
                    pass
    finally:
        server.close()
        db.close()


if __name__ == "__main__":
    asyncio.run(run_forever())
