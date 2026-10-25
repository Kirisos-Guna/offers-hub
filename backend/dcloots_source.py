"""Polls dcloots.in (a WooCommerce shop) through its public Store API."""
import asyncio
import html
import logging
import re
import time

import httpx

import db
from config import DCLOOTS_POLL_MINUTES
from offer_parser import store_for_url

log = logging.getLogger("dcloots")

API = "https://dcloots.in/"
TAG_RE = re.compile(r"<[^>]+>")


def _money(raw: str | None, minor_unit: int) -> float | None:
    if not raw:
        return None
    return int(raw) / (10 ** minor_unit)


def product_to_offer(p: dict) -> dict | None:
    prices = p.get("prices") or {}
    minor = int(prices.get("currency_minor_unit") or 0)
    price = _money(prices.get("sale_price") or prices.get("price"), minor)
    mrp = _money(prices.get("regular_price"), minor)
    url = html.unescape((p.get("add_to_cart") or {}).get("url") or p.get("permalink") or "")
    if not url:
        return None
    title = html.unescape(p.get("name") or "").strip()
    discount = round((1 - price / mrp) * 100) if price and mrp and mrp > price else None
    categories = [html.unescape(c["name"]) for c in p.get("categories") or []]
    description = TAG_RE.sub(" ", html.unescape(p.get("short_description") or ""))
    return {
        "title": title[:200],
        "tag": categories[0] if categories else None,
        "store": store_for_url(url) or "Other",
        "price": price,
        "mrp": mrp,
        "discount": discount,
        "url": url,
        "links": [{"url": url, "label": "Buy"}],
        "text": f"{title}\n\n{' '.join(description.split())}".strip(),
        "image": (p.get("images") or [{}])[0].get("src"),
    }


async def poll_once(client: httpx.AsyncClient) -> int:
    resp = await client.get(API, params={"rest_route": "/wc/store/v1/products", "per_page": 50,
                                         "orderby": "date", "order": "desc"})
    resp.raise_for_status()
    added = 0
    now = int(time.time())
    for index, product in enumerate(resp.json()):
        key = f"dc:{product['id']}"
        if db.has_offer(key):
            continue
        offer = product_to_offer(product)
        if offer is None:
            continue
        # The Store API has no dates; keep the site's order by spacing first-seen times.
        if db.upsert_offer(key, "DC Loots", offer, now - index, offer.pop("image")):
            added += 1
    return added


async def run(stop: asyncio.Event) -> None:
    async with httpx.AsyncClient(timeout=20, headers={"User-Agent": "OffersHub/1.0"}) as client:
        while not stop.is_set():
            try:
                log.info("dcloots: %d new offers", await poll_once(client))
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code == 403:
                    log.warning("dcloots.in refuses automated access (403); source stopped")
                    return
                log.warning("dcloots poll failed: %s", exc)
            except Exception as exc:
                log.warning("dcloots poll failed: %s", exc)
            try:
                await asyncio.wait_for(stop.wait(), timeout=DCLOOTS_POLL_MINUTES * 60)
            except asyncio.TimeoutError:
                pass
