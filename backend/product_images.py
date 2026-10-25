"""Finds a product picture for deal posts that have no photo, from the deal link itself.

- Amazon: the short link redirects to /dp/<ASIN> (or a search listing ASINs); Amazon serves a
  standard image per ASIN, so the product page itself is never needed.
- Other shops (Flipkart, Myntra...): the product page's og:image preview tag.
Requests are plain and identified; a shop that refuses them simply gets no image.
"""
import logging
import re

import httpx

log = logging.getLogger("images")

ASIN_RE = re.compile(r"/(?:dp|gp/product|gp/aw/d|d)/([A-Z0-9]{10})(?:[/?]|$)")
KEYWORD_ASINS_RE = re.compile(r"hidden-keywords=([A-Z0-9|+%C]+)")
OG_IMAGE_RE = re.compile(
    r"<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"
    r"|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:image[\"']",
    re.IGNORECASE,
)
AMAZON_MAIN_IMAGE_RE = re.compile(r'data-old-hires="(https://[^"]+)"|"hiRes":"(https://[^"]+)"')
AMAZON_IMAGE ="https://m.media-amazon.com/images/P/{}.01._SCLZZZZZZZ_.jpg"
MIN_IMAGE_BYTES = 1500  # Amazon answers unknown ASINs with a 43-byte placeholder GIF

_http = httpx.AsyncClient(
    timeout=12,
    headers={"User-Agent": "OffersHub/1.0 (+https://github.com/Kirisos-Guna/offers-hub)"},
)


async def _final_url(url: str) -> str:
    """Follows redirects by their Location headers only (no page bodies)."""
    current = url
    for _ in range(8):
        resp = await _http.head(current, follow_redirects=False)
        location = resp.headers.get("location")
        if resp.status_code not in (301, 302, 303, 307, 308) or not location:
            break
        current = str(httpx.URL(current).join(location))
    return current


def _asins(url: str) -> list[str]:
    m = ASIN_RE.search(url)
    if m:
        return [m.group(1)]
    m = KEYWORD_ASINS_RE.search(url)  # search links list several products
    if m:
        return [a for a in re.split(r"\||%7C|\+", m.group(1)) if re.fullmatch(r"[A-Z0-9]{10}", a)]
    return []


async def _amazon_image(asins: list[str]) -> bytes | None:
    for asin in asins[:4]:
        if data := await _download(AMAZON_IMAGE.format(asin)):
            return data
    # Newer products have no standard image; the product page names its main photo, unless
    # Amazon answers with a captcha, in which case there is no image.
    resp = await _http.get(f"https://www.amazon.in/dp/{asins[0]}", follow_redirects=True)
    m = AMAZON_MAIN_IMAGE_RE.search(resp.text) if resp.status_code == 200 else None
    if not m:
        return None
    return await _download(re.sub(r"\._[A-Z0-9_,]+_\.jpg$", "._SL640_.jpg", m.group(1) or m.group(2)))


async def _download(url: str) -> bytes | None:
    resp = await _http.get(url, follow_redirects=True)
    if resp.status_code != 200 or not resp.headers.get("content-type", "").startswith("image/"):
        return None
    return resp.content if len(resp.content) >= MIN_IMAGE_BYTES else None


async def _og_image(url: str) -> tuple[str | None, str]:
    """(og:image URL, final page URL), reading only the page head."""
    async with _http.stream("GET", url, follow_redirects=True) as resp:
        final = str(resp.url)
        if resp.status_code != 200:
            return None, final
        head = b""
        async for chunk in resp.aiter_bytes():
            head += chunk
            if b"</head>" in head or len(head) > 400_000:
                break
    m = OG_IMAGE_RE.search(head.decode("utf-8", "ignore"))
    image = (m.group(1) or m.group(2)) if m else None
    if image:
        image = image.replace("&amp;", "&").replace("{@width}", "640").replace("{@height}", "640")
    return image, final


async def image_for_link(url: str) -> bytes | None:
    try:
        final = await _final_url(url)
        asins = _asins(final)
        if not asins:
            # Not an Amazon product yet: load the page (link.amazon only reveals the product
            # this way) and use its preview image unless it turned out to be Amazon after all.
            image_url, final = await _og_image(final)
            asins = _asins(final)
            if not asins:
                return await _download(image_url) if image_url else None
        return await _amazon_image(asins)
    except (httpx.HTTPError, ValueError) as exc:
        log.info("no image for %s: %s", url, exc)
        return None
