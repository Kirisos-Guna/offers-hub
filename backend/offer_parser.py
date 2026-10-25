"""Turns a free-text deal post into a structured offer.

Typical posts:
    GRAB : Philips Viva Induction Cooktop Black at 2,997.  https://amzn.to/4yGSmhg
    72% Off : Parachute Body Lotion 400 ml @140.  https://amzn.to/4wYJWlc
    Upto 92% Off On Symbol Clothing.  Women : https://link.amazon/B08UGhFq6 Men : https://...
"""
import re
from urllib.parse import urlparse

URL_RE = re.compile(r"https?://[^\s<>\"')\]]+", re.IGNORECASE)

# Domain suffix -> store name. Checked against the link host.
STORE_DOMAINS = {
    "amzn.to": "Amazon", "amzn.in": "Amazon", "amzn.eu": "Amazon", "a.co": "Amazon",
    "amazon.in": "Amazon", "amazon.com": "Amazon", "link.amazon": "Amazon",
    "fkrt.it": "Flipkart", "fkrt.cc": "Flipkart", "fkrt.co": "Flipkart", "fkrt.to": "Flipkart",
    "flipkart.com": "Flipkart", "shopsy.in": "Shopsy",
    "myntr.it": "Myntra", "myntra.com": "Myntra",
    "ajio.com": "Ajio", "ajiio.co": "Ajio", "ajio.me": "Ajio",
    "meesho.com": "Meesho", "msho.in": "Meesho",
    "nykaa.com": "Nykaa", "nykaafashion.com": "Nykaa", "nykaa.me": "Nykaa",
    "tatacliq.com": "Tata CLiQ", "croma.com": "Croma", "jiomart.com": "JioMart",
    "reliancedigital.in": "Reliance Digital", "bigbasket.com": "BigBasket",
    "blinkit.com": "Blinkit", "zeptonow.com": "Zepto", "swiggy.com": "Swiggy",
    "firstcry.com": "FirstCry", "snapdeal.com": "Snapdeal", "pepperfry.com": "Pepperfry",
    "decathlon.in": "Decathlon", "lenskart.com": "Lenskart", "boat-lifestyle.com": "boAt",
    "mamaearth.in": "Mamaearth", "pharmeasy.in": "PharmEasy", "1mg.com": "Tata 1mg",
    "goibibo.com": "Goibibo", "makemytrip.com": "MakeMyTrip",
}
# Words in the post that name a store, used when the link is an unknown shortener.
STORE_WORDS = {
    "amazon": "Amazon", "flipkart": "Flipkart", "myntra": "Myntra", "ajio": "Ajio",
    "meesho": "Meesho", "nykaa": "Nykaa", "tata cliq": "Tata CLiQ", "croma": "Croma",
    "jiomart": "JioMart", "shopsy": "Shopsy", "bigbasket": "BigBasket", "blinkit": "Blinkit",
    "zepto": "Zepto", "swiggy": "Swiggy", "snapdeal": "Snapdeal", "firstcry": "FirstCry",
}
IGNORED_HOSTS = ("t.me", "telegram.me", "telegram.org", "youtube.com", "youtu.be", "instagram.com",
                 "whatsapp.com", "wa.me", "chat.whatsapp.com")

PRICE_RE = re.compile(
    r"(?:₹|rs\.?|inr|\bat\b|@|\bstarts?\s*(?:at|@|from)?)\s*₹?\s*(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.\d{1,2})?",
    re.IGNORECASE,
)
MRP_RE = re.compile(r"\bmrp\s*[:\-]?\s*(?:₹|rs\.?)?\s*(\d{1,3}(?:,\d{2,3})+|\d+)", re.IGNORECASE)
DISCOUNT_RE = re.compile(r"(\d{1,2})\s*%\s*off", re.IGNORECASE)
# "GRAB : ..."  "Loot : ..."  "72% Off : ..."
TAG_RE = re.compile(r"^\s*([^:\n]{2,24}?)\s*:\s+(?!//)")
TITLE_TAIL_RE = re.compile(
    r"\s*(?:\bstarts?\s*(?:at|@|from)?|\bat\b|@|₹|\brs\.?)\s*₹?\s*[\d,]+(?:\.\d+)?.*$", re.IGNORECASE
)


def store_for_url(url: str) -> str | None:
    host = (urlparse(url).hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    if "redirectAmzASIN" in url:
        return "Amazon"
    for domain, store in STORE_DOMAINS.items():
        if host == domain or host.endswith("." + domain):
            return store
    return None


def is_shop_link(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return bool(host) and not any(host == h or host.endswith("." + h) for h in IGNORED_HOSTS)


def _to_number(raw: str) -> float | None:
    try:
        return float(raw.replace(",", ""))
    except ValueError:
        return None


def _clean_url(url: str) -> str:
    return url.rstrip(".,;:!*")


def extract_links(text: str, extra_urls: list[str] | None = None) -> list[dict]:
    """Links in order, each with the label written before it on the same line ("Women", "₹50")."""
    links: list[dict] = []
    seen: set[str] = set()
    for line in text.splitlines():
        last_end = 0
        for m in URL_RE.finditer(line):
            url = _clean_url(m.group(0))
            label = line[last_end:m.start()].strip(" :-–|>*\t")
            last_end = m.end()
            if url in seen or not is_shop_link(url):
                continue
            seen.add(url)
            if len(label) > 30:  # that's the deal sentence, not a label
                label = ""
            links.append({"url": url, "label": label})
    for url in extra_urls or []:
        url = _clean_url(url)
        if url not in seen and is_shop_link(url):
            seen.add(url)
            links.append({"url": url, "label": ""})
    return links


def parse_offer(text: str, extra_urls: list[str] | None = None) -> dict | None:
    """Returns None for posts without a shopping link (announcements, chatter)."""
    text = (text or "").strip()
    links = extract_links(text, extra_urls)
    if not text or not links:
        return None

    first_line = next((l.strip() for l in text.splitlines() if l.strip()), "")
    tag = None
    m = TAG_RE.match(first_line)
    if m and not URL_RE.search(m.group(1)):
        tag = m.group(1).strip()
        first_line = first_line[m.end():]

    title = URL_RE.split(first_line)[0].strip()
    title = TITLE_TAIL_RE.sub("", title).strip(" .:-–|*")
    if not title:  # first line was only a link; take the next text line
        rest = [l.strip() for l in text.splitlines()[1:] if l.strip() and not URL_RE.match(l.strip())]
        title = rest[0] if rest else "Deal"
        title = TITLE_TAIL_RE.sub("", URL_RE.split(title)[0]).strip(" .:-–|*") or "Deal"

    body_wo_urls = URL_RE.sub(" ", text)
    price = None
    pm = PRICE_RE.search(body_wo_urls)
    if pm:
        price = _to_number(pm.group(1))
    mrp = None
    mm = MRP_RE.search(body_wo_urls)
    if mm:
        mrp = _to_number(mm.group(1))
        if price == mrp:  # the price regex caught the MRP; look for another number
            others = [p for p in PRICE_RE.finditer(body_wo_urls) if _to_number(p.group(1)) != mrp]
            price = _to_number(others[0].group(1)) if others else None

    discount = None
    dm = DISCOUNT_RE.search(text)
    if dm:
        discount = int(dm.group(1))
    elif price and mrp and mrp > price:
        discount = round((1 - price / mrp) * 100)

    store = None
    for link in links:
        store = store_for_url(link["url"])
        if store:
            break
    if not store:
        lowered = text.lower()
        store = next((s for w, s in STORE_WORDS.items() if w in lowered), None)

    return {
        "title": title[:200],
        "tag": tag,
        "store": store or "Other",
        "price": price,
        "mrp": mrp,
        "discount": discount,
        "url": links[0]["url"],
        "links": links,
        "text": text,
    }


def dedupe_key(offer: dict) -> str:
    """Same deal reposted across sister channels gets the same key."""
    words = re.sub(r"[^a-z0-9]+", "", offer["title"].lower())[:60]
    return f"{words}|{offer.get('price') or ''}"
