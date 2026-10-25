"""Offers API for the Android app.

    uvicorn server:app --host 0.0.0.0 --port 8000
"""
import asyncio
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Query
from fastapi.staticfiles import StaticFiles

import db
import dcloots_source
import telegram_source
from config import DCLOOTS_ENABLED, MEDIA_DIR

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
log = logging.getLogger("server")


async def _purge_loop(stop: asyncio.Event) -> None:
    while not stop.is_set():
        removed = db.purge_old()
        if removed:
            log.info("purged %d expired offers", removed)
        try:
            await asyncio.wait_for(stop.wait(), timeout=3600)
        except asyncio.TimeoutError:
            pass


async def _guard(name: str, coro) -> None:
    try:
        await coro
    except Exception:
        log.exception("%s stopped", name)


@asynccontextmanager
async def lifespan(_: FastAPI):
    stop = asyncio.Event()
    tasks = [asyncio.create_task(_guard("telegram", telegram_source.run(stop))),
             asyncio.create_task(_purge_loop(stop))]
    if DCLOOTS_ENABLED:
        tasks.append(asyncio.create_task(_guard("dcloots", dcloots_source.run(stop))))
    yield
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)


app = FastAPI(title="Offers Hub", lifespan=lifespan)
app.mount("/media", StaticFiles(directory=MEDIA_DIR), name="media")


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/offers")
def offers(
    store: str | None = None,
    q: str | None = None,
    min_discount: int | None = None,
    sort: str = Query("latest", pattern="^(latest|discount|price)$"),
    offset: int = Query(0, ge=0),
    limit: int = Query(30, ge=1, le=100),
):
    items = db.list_offers(store, q, min_discount, sort, offset, limit)
    return {"items": items, "next_offset": offset + len(items) if len(items) == limit else None}


@app.get("/offers.json")
def offers_feed():
    """Same feed the GitHub Pages site publishes, so the app works with either."""
    return {"updated_at": int(time.time()), "items": db.all_offers()}


@app.get("/api/stores")
def stores():
    return db.store_counts()
