"""One-time Telegram login. Asks for your phone number and the code Telegram sends you.

    python login.py           saves the session to backend/offers.session (for server.py)
    python login.py --string  prints a session string for the TG_SESSION_STRING GitHub secret

Keep the session file and string private: they give access to your Telegram account.
"""
import asyncio
import sys

from telethon import TelegramClient
from telethon.sessions import StringSession

from config import TG_API_HASH, TG_API_ID
from telegram_source import make_client


async def main() -> None:
    if not TG_API_ID or not TG_API_HASH:
        raise SystemExit("Fill TG_API_ID and TG_API_HASH in backend/.env first (see .env.example).")
    as_string = "--string" in sys.argv
    client = TelegramClient(StringSession(), TG_API_ID, TG_API_HASH) if as_string else make_client()
    await client.start()  # prompts for phone, code and 2FA password in this terminal
    me = await client.get_me()
    print(f"Logged in as {me.first_name}.")
    if as_string:
        print("\nTG_SESSION_STRING (copy into the GitHub secret, never share it):\n")
        print(client.session.save())
    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
