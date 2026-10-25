"""One sync pass for the GitHub Actions job: reads new channel posts and writes a static site.

    python sync_static.py <site dir>

The site dir holds everything between runs (it is the gh-pages branch):
    offers.json   all current deals, newest first - what the app downloads
    media/        post images
    offers.db     sync state (offers + where each channel left off)
"""
import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path

site = Path(sys.argv[1] if len(sys.argv) > 1 else "site").resolve()
site.mkdir(parents=True, exist_ok=True)
# Must be set before config is imported.
os.environ["DB_PATH"] = str(site / "offers.db")
os.environ["MEDIA_DIR"] = str(site / "media")

import db  # noqa: E402
import telegram_source  # noqa: E402
from config import MEDIA_DIR, TG_API_HASH, TG_API_ID, TG_SESSION_STRING  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(name)s: %(message)s")
log = logging.getLogger("sync")
BACKFILL_IMAGES = 400  # at most this many older deals get an image lookup per run


async def sync() -> None:
    client = telegram_source.make_client()
    await client.connect()
    try:
        if not await client.is_user_authorized():
            raise SystemExit("Telegram session is not logged in - recreate TG_SESSION_STRING with login.py --string")
        for link, chat in await telegram_source.open_channels(client):
            await telegram_source.sync_channel(client, link, chat)
    finally:
        await client.disconnect()
    # Deals saved before the link-image lookup existed (or whose lookup was cut short).
    found = 0
    missing = db.offers_without_image(BACKFILL_IMAGES)
    for offer in missing:
        found += await telegram_source.fill_link_image(offer["source_key"], offer["links"])
    if missing:
        log.info("link images: %d found for %d deals without a photo", found, len(missing))


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
    out.write_text(json.dumps({"updated_at": int(time.time()), "items": items}, ensure_ascii=False,
                              separators=(",", ":")), encoding="utf-8")
    (site / ".nojekyll").touch()  # serve files as-is, no Jekyll build
    log.info("%d offers published, %d expired removed", len(items), removed)
    return old != items


if __name__ == "__main__":
    if not (TG_API_ID and TG_API_HASH and TG_SESSION_STRING):
        raise SystemExit("Set TG_API_ID, TG_API_HASH and TG_SESSION_STRING")
    asyncio.run(sync())
    changed = export()
    db.close()
    if gh_output := os.environ.get("GITHUB_OUTPUT"):
        with open(gh_output, "a") as f:
            f.write(f"changed={'true' if changed else 'false'}\n")
