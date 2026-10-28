"""Reads deal posts from Telegram channels with a user account (Telethon).

The bot API can't read channels you don't own, so this logs in as you (once, via login.py)
and joins the channels from config.TG_CHANNELS.
"""
import asyncio
import io
import logging
import re

import httpx
from PIL import Image
from telethon import TelegramClient, errors, events, functions, types, utils
from telethon.sessions import StringSession

import db
import product_images
from image_store import store as image_store
from config import (BACKFILL_LIMIT, IMAGE_MAX_WIDTH, TG_API_HASH, TG_API_ID, TG_CHANNELS,
                    TG_SESSION, TG_SESSION_STRING)
from offer_parser import parse_offer, store_for_url

log = logging.getLogger("telegram")

INVITE_RE = re.compile(r"(?:t\.me|telegram\.me)/(?:\+|joinchat/)([\w-]+)")
PUBLIC_RE = re.compile(r"(?:t\.me|telegram\.me)/([A-Za-z0-9_]{4,})/?$")


def make_client() -> TelegramClient:
    session = StringSession(TG_SESSION_STRING) if TG_SESSION_STRING else TG_SESSION
    return TelegramClient(session, TG_API_ID, TG_API_HASH)


async def resolve_channel(client: TelegramClient, link: str):
    """Returns the channel entity, joining it first if needed."""
    m = INVITE_RE.search(link)
    if m:
        invite_hash = m.group(1)
        info = await client(functions.messages.CheckChatInviteRequest(invite_hash))
        if isinstance(info, (types.ChatInviteAlready, types.ChatInvitePeek)):
            return info.chat
        try:
            updates = await client(functions.messages.ImportChatInviteRequest(invite_hash))
            return updates.chats[0]
        except errors.UserAlreadyParticipantError:
            info = await client(functions.messages.CheckChatInviteRequest(invite_hash))
            return info.chat
    m = PUBLIC_RE.search(link)
    name = m.group(1) if m else link.lstrip("@")
    entity = await client.get_entity(name)
    try:
        await client(functions.channels.JoinChannelRequest(entity))
    except errors.UserAlreadyParticipantError:
        pass
    return entity


def _extra_urls(message) -> list[str]:
    """Links hidden behind formatted text or inline buttons."""
    urls = []
    for ent in message.entities or []:
        if isinstance(ent, types.MessageEntityTextUrl):
            urls.append(ent.url)
    markup = message.reply_markup
    if isinstance(markup, types.ReplyInlineMarkup):
        for row in markup.rows:
            for button in row.buttons:
                if isinstance(button, types.KeyboardButtonUrl):
                    urls.append(button.url)
    return urls


_http = httpx.AsyncClient(follow_redirects=True, timeout=8,
                          headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) OffersHub"})


async def _store_from_redirect(url: str) -> str | None:
    """Unknown short links (bit.ly, earnkaro, ...) are followed to find the shop."""
    try:
        resp = await _http.head(url)
        if resp.status_code >= 400:
            resp = await _http.get(url)
        return store_for_url(str(resp.url))
    except httpx.HTTPError:
        return None


def _shrink(data: bytes) -> bytes:
    """Re-encodes a post photo at card size (~30 KB instead of ~150 KB)."""
    with Image.open(io.BytesIO(data)) as img:
        img = img.convert("RGB")
        if img.width > IMAGE_MAX_WIDTH:
            img = img.resize((IMAGE_MAX_WIDTH, round(img.height * IMAGE_MAX_WIDTH / img.width)))
        out = io.BytesIO()
        img.save(out, "JPEG", quality=75, optimize=True)
        return out.getvalue()


def _has_image(message) -> bool:
    if message.photo:
        return True
    media = message.media
    return isinstance(media, types.MessageMediaWebPage) and isinstance(
        getattr(media.webpage, "photo", None), types.Photo
    )


async def handle_message(message, channel_title: str, on_saved=None) -> None:
    """Saves the deal in a post. on_saved(source_key, final) is awaited right after saving
    (final=False) and again once the link lookups are done (final=True)."""
    if not message or not message.message:
        return
    offer = parse_offer(message.message, _extra_urls(message))
    if offer is None:
        return
    if offer["store"] == "Other":
        offer["store"] = await _store_from_redirect(offer["url"]) or "Other"
        if offer["weak_title"] and offer["store"] != "Other":
            offer["title"] = placeholder_title(offer["store"])

    source_key = f"tg:{message.chat_id}:{message.id}"
    image = None
    if _has_image(message):
        name = f"tg_{abs(message.chat_id)}_{message.id}.jpg"
        existing = db.get_offer(source_key)
        image = (existing or {}).get("image") or image_store.exists(name)
        if not image:
            try:
                data = await message.download_media(file=bytes)
                image = await image_store.save(name, _shrink(data))
            except Exception as exc:  # media can be gone or restricted; the deal is still useful
                log.warning("image failed for %s: %s", source_key, exc)

    posted_at = int(message.date.timestamp())
    if db.upsert_offer(source_key, channel_title, offer, posted_at, image):
        log.info("[%s] %s | %s | %s", channel_title, offer["store"], offer["price"], offer["title"][:60])
        needs_lookup = image is None or offer["weak_title"]
        if on_saved and needs_lookup:
            await on_saved(source_key, False)  # show it now; the lookup can take a few seconds
        if needs_lookup:
            await enrich_from_link(source_key, offer["links"], need_image=image is None,
                                   need_title=offer["weak_title"])
        if on_saved:
            await on_saved(source_key, True)


def placeholder_title(store: str) -> str:
    return f"{store} deal" if store != "Other" else "Deal"


async def enrich_from_link(source_key: str, links: list[dict], need_image: bool, need_title: bool) -> bool:
    """Fills in what a post lacks from the product behind its link: the picture for a deal
    without a photo, the product name for a title that is only a price. Misses are recorded so
    they aren't retried every run. Returns True when an image was found."""
    image, title = None, None
    for link in links[:2]:
        data, found_title = await product_images.lookup(link["url"], want_image=need_image and not image,
                                                         want_title=need_title and not title)
        title = title or found_title
        if data and not image:
            try:
                name = f"{source_key.replace(':', '_').replace('-', '')}.jpg"
                image = await image_store.save(name, _shrink(data))
            except Exception as exc:  # not a decodable image, or the upload failed
                log.info("product image failed for %s: %s", source_key, exc)
        if (image or not need_image) and (title or not need_title):
            break
    db.save_lookup(source_key, (image or "") if need_image else None, title)
    return image is not None


async def open_channels(client: TelegramClient) -> list[tuple[str, object]]:
    """(link, entity) for every configured channel that could be opened."""
    # A string session keeps no entity cache, so load the joined chats once instead of
    # re-checking every invite link (Telegram rate-limits those checks).
    dialogs = {d.id: d.entity for d in await client.get_dialogs() if d.is_channel}
    by_title = {chat.title.casefold(): chat for chat in dialogs.values()}
    opened = []
    for link in TG_CHANNELS:
        known = db.get_channel(link)
        chat = dialogs.get(known["peer_id"]) if known else None
        if chat is None and link.startswith("title:"):
            # A channel the account has already joined, named instead of linked.
            chat = by_title.get(link[len("title:"):].strip().casefold())
            if chat is None:
                log.error("no joined channel is named %r", link[len("title:"):])
                continue
            db.save_channel(link, utils.get_peer_id(chat), chat.title, 0)
        if chat is None:
            try:
                chat = await resolve_channel(client, link)
            except errors.InviteRequestSentError:
                log.warning("%s needs admin approval; join request sent", link)
                continue
            except Exception as exc:
                log.error("could not open %s: %s", link, exc)
                continue
            db.save_channel(link, utils.get_peer_id(chat), chat.title, 0)
        opened.append((link, chat))
        log.info("opened %s (%s)", chat.title, link)
    return opened


async def sync_channel(client: TelegramClient, link: str, chat, on_saved=None) -> int:
    """Imports posts newer than the last sync (or the latest BACKFILL_LIMIT on the first run)."""
    last_id = (db.get_channel(link) or {}).get("last_id", 0)
    newest, count = last_id, 0
    kwargs = {"min_id": last_id} if last_id else {"limit": BACKFILL_LIMIT}
    async for message in client.iter_messages(chat, **kwargs):
        newest = max(newest, message.id)
        if not db.has_offer(f"tg:{message.chat_id}:{message.id}"):
            await handle_message(message, chat.title, on_saved)
        count += 1
    db.save_channel(link, utils.get_peer_id(chat), chat.title, newest)
    if count:
        log.info("synced %d new messages from %s", count, chat.title)
    return count


async def run(stop: asyncio.Event) -> None:
    if not TG_API_ID or not TG_API_HASH:
        log.warning("TG_API_ID / TG_API_HASH not set in backend/.env - Telegram channels are disabled")
        return
    client = make_client()
    await client.connect()
    if not await client.is_user_authorized():
        log.warning("Telegram session not logged in. Run `python login.py` once, then restart.")
        await client.disconnect()
        return

    opened = await open_channels(client)
    if not opened:
        await client.disconnect()
        return
    chats = [chat for _, chat in opened]
    titles = {utils.get_peer_id(chat): chat.title for chat in chats}  # same "-100..." form as event.chat_id

    def title_for(event_chat_id: int) -> str:
        return titles.get(event_chat_id, "Telegram")

    @client.on(events.NewMessage(chats=chats))
    async def on_new(event):
        await handle_message(event.message, title_for(event.chat_id))

    @client.on(events.MessageEdited(chats=chats))
    async def on_edit(event):
        await handle_message(event.message, title_for(event.chat_id))

    @client.on(events.MessageDeleted(chats=chats))
    async def on_delete(event):
        if event.chat_id:
            db.delete_offers([f"tg:{event.chat_id}:{i}" for i in event.deleted_ids])

    for link, chat in opened:
        await sync_channel(client, link, chat)

    log.info("listening for new deals...")
    disconnected = asyncio.ensure_future(client.disconnected)
    stopped = asyncio.ensure_future(stop.wait())
    await asyncio.wait([disconnected, stopped], return_when=asyncio.FIRST_COMPLETED)
    await client.disconnect()
