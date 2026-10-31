"""Optional: put your own affiliate IDs on Amazon and Flipkart links (off unless configured).

    AMAZON_AFFILIATE_TAG=yourtag-21      FLIPKART_AFFILIATE_ID=youraffid

The channels' posts carry the channel owners' affiliate links, which is how they earn from their
work. Only turn this on with the channel owners' permission.
Short links are followed to the shop's URL, and the tag query parameter is replaced there.
"""
import logging
import os
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import httpx

log = logging.getLogger("affiliate")

AMAZON_TAG = os.environ.get("AMAZON_AFFILIATE_TAG", "").strip()
FLIPKART_ID = os.environ.get("FLIPKART_AFFILIATE_ID", "").strip()
ENABLED = bool(AMAZON_TAG or FLIPKART_ID)

_http = httpx.AsyncClient(timeout=10, headers={"User-Agent": "OffersHub/1.0"})


def _with_param(url: str, key: str, value: str, drop: tuple[str, ...] = ()) -> str:
    parts = urlparse(url)
    query = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if k != key and k not in drop]
    query.append((key, value))
    return urlunparse(parts._replace(query=urlencode(query)))


def tag_url(url: str) -> str | None:
    """The URL with your affiliate ID, if it is an Amazon or Flipkart product URL."""
    host = (urlparse(url).hostname or "").lower()
    if AMAZON_TAG and host.endswith("amazon.in"):
        return _with_param(url, "tag", AMAZON_TAG, drop=("ascsubtag", "linkCode", "linkId"))
    if FLIPKART_ID and host.endswith("flipkart.com"):
        return _with_param(url, "affid", FLIPKART_ID, drop=("affExtParam1", "affExtParam2"))
    return None


async def _resolve(url: str) -> str:
    current = url
    for _ in range(8):
        resp = await _http.head(current, follow_redirects=False)
        location = resp.headers.get("location")
        if resp.status_code not in (301, 302, 303, 307, 308) or not location:
            break
        current = str(httpx.URL(current).join(location))
    return current


async def retag_links(links: list[dict]) -> list[dict]:
    """Same links with your IDs where possible; links that can't be retagged stay as they are."""
    if not ENABLED:
        return links
    out = []
    for link in links:
        try:
            tagged = tag_url(await _resolve(link["url"]))
        except (httpx.HTTPError, httpx.InvalidURL, ValueError) as exc:
            log.info("couldn't resolve %s: %s", link["url"], exc)
            tagged = None
        out.append({**link, "url": tagged or link["url"]})
    return out
