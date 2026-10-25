"""Sync job for GitHub Actions: reads channel posts and publishes a static site.

    python sync_static.py <site dir> [--watch MINUTES]

Without --watch it does one catch-up pass. With --watch it then stays connected and picks up
posts as they arrive, publishing at most every PUBLISH_EVERY seconds (GitHub Pages allows about
10 site builds an hour), so deals show up within minutes even when GitHub starts scheduled runs
late.

The site dir holds everything between runs (it is the gh-pages branch):
    offers.json   all current deals, newest first - what the app downloads
    media/        post images
    offers.db     sync state (offers + where each channel left off)
Publishing (force-pushing the site to gh-pages) only happens when GITHUB_TOKEN and
GITHUB_REPOSITORY are set, i.e. inside the workflow.
"""
import argparse
import asyncio
import json
import logging
import os
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("site", nargs="?", default="site")
parser.add_argument("--watch", type=float, default=0, metavar="MINUTES")
args = parser.parse_args()

site = Path(args.site).resolve()
site.mkdir(parents=True, exist_ok=True)
# Must be set before config is imported.
os.environ["DB_PATH"] = str(site / "offers.db")
os.environ["MEDIA_DIR"] = str(site / "media")

from telethon import events, utils  # noqa: E402

import db  # noqa: E402
import telegram_source  # noqa: E402
from config import MEDIA_DIR, TG_API_HASH, TG_API_ID, TG_SESSION_STRING  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("sync")
BACKFILL_IMAGES = 400  # at most this many older deals get an image lookup per run
PUBLISH_EVERY = 6 * 60
PUBLISH_SCRIPT = Path(__file__).with_name("publish.sh")


def export() -> bool:
    """Writes offers.json; returns True when its content changed."""
    removed = db.purge_old()
    keep = {Path(p).name for p in db.referenced_images()}
    for f in MEDIA_DIR.glob("*.jpg"):
        if f.name not in keep:
            f.unlink()

    items = db.all_offers()
    out = site / "offers.json"
    old = json.loads(out.read_text(encoding="utf-8")).get("items") if out.exists() else None
    if old == items:
        return False
    out.write_text(json.dumps({"updated_at": int(time.time()), "items": items}, ensure_ascii=False,
                              separators=(",", ":")), encoding="utf-8")
    (site / ".nojekyll").touch()  # serve files as-is, no Jekyll build
    log.info("%d offers, %d expired removed", len(items), removed)
    return True


def publish() -> None:
    if not export():
        return
    if os.environ.get("GITHUB_TOKEN") and os.environ.get("GITHUB_REPOSITORY"):
        subprocess.run(["bash", str(PUBLISH_SCRIPT), str(site)], check=True)
        log.info("published")


async def fill_missing_images() -> None:
    """Deals saved before the link-image lookup existed (or whose lookup was cut short)."""
    missing = db.offers_without_image(BACKFILL_IMAGES)
    found = 0
    for offer in missing:
        found += await telegram_source.fill_link_image(offer["source_key"], offer["links"])
    if missing:
        log.info("link images: %d found for %d deals without a photo", found, len(missing))


async def main() -> None:
    client = telegram_source.make_client()
    await client.connect()
    try:
        if not await client.is_user_authorized():
            raise SystemExit("Telegram session is not logged in - recreate it with login.py --github")
        opened = await telegram_source.open_channels(client)

        async def catch_up():
            for link, chat in opened:
                await telegram_source.sync_channel(client, link, chat)

        await catch_up()
        await fill_missing_images()
        publish()
        if not args.watch or not opened:
            return

        chats = [chat for _, chat in opened]
        titles = {utils.get_peer_id(chat): chat.title for chat in chats}
        dirty = False

        @client.on(events.NewMessage(chats=chats))
        @client.on(events.MessageEdited(chats=chats))
        async def on_post(event):
            nonlocal dirty
            await telegram_source.handle_message(event.message, titles.get(event.chat_id, "Telegram"))
            dirty = True

        @client.on(events.MessageDeleted(chats=chats))
        async def on_delete(event):
            nonlocal dirty
            if event.chat_id:
                db.delete_offers([f"tg:{event.chat_id}:{i}" for i in event.deleted_ids])
                dirty = True

        log.info("watching %d channels for %.0f minutes", len(chats), args.watch)
        deadline = time.monotonic() + args.watch * 60
        last_publish = time.monotonic()
        while time.monotonic() < deadline and client.is_connected():
            await asyncio.sleep(20)
            if dirty and time.monotonic() - last_publish >= PUBLISH_EVERY:
                dirty = False
                publish()
                last_publish = time.monotonic()

        await catch_up()  # anything missed during a reconnect; also records where we left off
        publish()
    finally:
        await client.disconnect()


if __name__ == "__main__":
    if not (TG_API_ID and TG_API_HASH and TG_SESSION_STRING):
        raise SystemExit("Set TG_API_ID, TG_API_HASH and TG_SESSION_STRING")
    try:
        asyncio.run(main())
    finally:
        db.close()
