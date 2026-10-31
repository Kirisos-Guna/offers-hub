"""Finds a product picture (and, when the post has none, a product name) from the deal link.

- Amazon: the short link redirects to /dp/<ASIN> (or a search listing ASINs); Amazon serves a
  standard image per ASIN. The product page is loaded only when that has no image or a name is
  needed; if Amazon answers with a captcha there is simply no result.
- Other shops (Flipkart, Myntra...): the product page's og:image / og:title preview tags.
Requests are plain and identified; a shop that refuses them gets no image.
"""
import html
import logging
import re

import httpx

log = logging.getLogger("images")

ASIN_RE = re.compile(r"/(?:dp|gp/product|gp/aw/d|d)/([A-Z0-9]{10})(?:[/?]|$)")
KEYWORD_ASINS_RE = re.compile(r"hidden-keywords=([A-Z0-9|+%C]+)")


def _meta_re(prop: str) -> re.Pattern:
    return re.compile(
        rf"<meta[^>]+(?:property|name)=[\"']{prop}[\"'][^>]+content=[\"']([^\"']+)[\"']"
        rf"|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']{prop}[\"']",
        re.IGNORECASE,
    )


OG_IMAGE_RE = _meta_re("og:image")
OG_TITLE_RE = _meta_re("og:title")
AMAZON_MAIN_IMAGE_RE = re.compile(r'data-old-hires="(https://[^"]+)"|"hiRes":"(https://[^"]+)"')
AMAZON_TITLE_RE = re.compile(r'id="productTitle"[^>]*>\s*([^<]+?)\s*<')
AMAZON_IMAGE = "https://m.media-amazon.com/images/P/{}.01._SCLZZZZZZZ_.jpg"
MIN_IMAGE_BYTES = 1500  # Amazon answers unknown ASINs with a 43-byte placeholder GIF
# Shop boilerplate around product names in page titles.
TITLE_NOISE_RE = re.compile(
    r"^(?:buy\s+)|\s+(?:-\s*(?:price in india|buy|online|shop)|\|).*$|\s+online at .*$", re.IGNORECASE
)

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


def _first(m: re.Match | None) -> str | None:
    return html.unescape(next(g for g in m.groups() if g)) if m else None


def _clean_title(title: str | None) -> str | None:
    if not title:
        return None
    title = TITLE_NOISE_RE.sub("", " ".join(html.unescape(title).split())).strip(" -|:")
    generic = ("amazon", "flipkart", "myntra", "online shopping", "page not found", "robot check")
    # Store/brand pages give names like "Stores" or an unfilled "{TITLE}" template.
    if len(title.split()) < 2 or "{" in title or any(title.lower().startswith(g) for g in generic):
        return None
    return title[:200]


async def _download(url: str) -> bytes | None:
    resp = await _http.get(url, follow_redirects=True)
    if resp.status_code != 200 or not resp.headers.get("content-type", "").startswith("image/"):
        return None
    return resp.content if len(resp.content) >= MIN_IMAGE_BYTES else None


async def _page_meta(url: str) -> tuple[str | None, str | None, str]:
    """(og:image, og:title, final page URL), reading only the page head."""
    async with _http.stream("GET", url, follow_redirects=True) as resp:
        final = str(resp.url)
        if resp.status_code != 200:
            return None, None, final
        head = b""
        async for chunk in resp.aiter_bytes():
            head += chunk
            if b"</head>" in head or len(head) > 400_000:
                break
    text = head.decode("utf-8", "ignore")
    image = _first(OG_IMAGE_RE.search(text))
    if image:
        image = image.replace("{@width}", "640").replace("{@height}", "640")
    return image, _first(OG_TITLE_RE.search(text)), final


async def _amazon_page(asin: str) -> tuple[str | None, str | None]:
    """(main image URL, product name) from the product page; nothing on a captcha."""
    resp = await _http.get(f"https://www.amazon.in/dp/{asin}", follow_redirects=True)
    if resp.status_code != 200:
        return None, None
    image = _first(AMAZON_MAIN_IMAGE_RE.search(resp.text))
    if image:
        image = re.sub(r"\._[A-Z0-9_,]+_\.jpg$", "._SL640_.jpg", image)
    return image, _first(AMAZON_TITLE_RE.search(resp.text))


async def lookup(url: str, want_image: bool = True, want_title: bool = False) -> tuple[bytes | None, str | None]:
    """(image bytes, product name) for a deal link; either can be None."""
    try:
        final = await _final_url(url)
        asins = _asins(final)
        if not asins:
            # Not an Amazon product yet: load the page (link.amazon only reveals the product
            # this way) and use its preview tags unless it turned out to be Amazon after all.
            image_url, title, final = await _page_meta(final)
            asins = _asins(final)
            if not asins:
                image = await _download(image_url) if want_image and image_url else None
                return image, _clean_title(title) if want_title else None
        image = None
        if want_image:
            for asin in asins[:4]:
                if image := await _download(AMAZON_IMAGE.format(asin)):
                    break
        title = None
        if (want_image and image is None) or want_title:
            page_image, title = await _amazon_page(asins[0])
            if want_image and image is None and page_image:
                image = await _download(page_image)
        return image, _clean_title(title) if want_title else None
    except (httpx.HTTPError, httpx.InvalidURL, ValueError) as exc:
        log.info("lookup failed for %s: %s", url, exc)
        return None, None
