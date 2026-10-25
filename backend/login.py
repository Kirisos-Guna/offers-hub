"""One-time Telegram login. Asks for your phone number and the code Telegram sends you.

    python login.py           saves the session to backend/offers.session (for server.py)
    python login.py --string  prints a session string for the TG_SESSION_STRING GitHub secret
    python login.py --github  stores that string straight into the repo secret (needs the gh CLI)

Keep the session file and string private: they give access to your Telegram account.
"""
import asyncio
import subprocess
import sys

from telethon import TelegramClient
from telethon.sessions import StringSession

from config import BASE_DIR, TG_API_HASH, TG_API_ID
from telegram_source import make_client


async def main() -> None:
    if not TG_API_ID or not TG_API_HASH:
        raise SystemExit("Fill TG_API_ID and TG_API_HASH in backend/.env first (see .env.example).")
    to_github = "--github" in sys.argv
    as_string = to_github or "--string" in sys.argv
    client = TelegramClient(StringSession(), TG_API_ID, TG_API_HASH) if as_string else make_client()
    await client.start()  # prompts for phone, code and 2FA password in this terminal
    me = await client.get_me()
    print(f"Logged in as {me.first_name}.")
    if to_github:
        subprocess.run(["gh", "secret", "set", "TG_SESSION_STRING"], input=client.session.save(),
                       text=True, check=True, cwd=BASE_DIR.parent)
        print("Saved to the TG_SESSION_STRING secret of the GitHub repo.")
    elif as_string:
        print("\nTG_SESSION_STRING (copy into the GitHub secret, never share it):\n")
        print(client.session.save())
    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
