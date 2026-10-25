"""Settings, read from backend/.env (KEY=VALUE lines) or the process environment."""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent


def _load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


_load_env_file(BASE_DIR / ".env")

# From https://my.telegram.org -> API development tools
TG_API_ID = int(os.environ.get("TG_API_ID", "0") or 0)
TG_API_HASH = os.environ.get("TG_API_HASH", "")
TG_SESSION = str(BASE_DIR / os.environ.get("TG_SESSION", "offers"))
# Used instead of the session file when set (GitHub Actions secret, printed by login.py --string).
TG_SESSION_STRING = os.environ.get("TG_SESSION_STRING", "")

# Invite links (t.me/+...), public links (t.me/name) or @usernames, comma separated.
DEFAULT_CHANNELS = [
    "https://t.me/+Th6aG5Zaxz_i_u7a",
    "https://t.me/+lEEQraQyKwljNGQ1",
    "https://t.me/+sX1Ht4p33nFjZDE1",
    "https://t.me/+FpXKV70NYNY0NzQ1",
    "https://t.me/+uV5wcTkUWJEwM2Y1",
    "https://t.me/+7rHGiXC8TnsxN2I9",
]
TG_CHANNELS = [c.strip() for c in os.environ.get("TG_CHANNELS", "").split(",") if c.strip()] or DEFAULT_CHANNELS

# How many past messages to import per channel on startup.
BACKFILL_LIMIT = int(os.environ.get("BACKFILL_LIMIT", "300"))

# dcloots.in currently answers automated requests with 403, so it is off unless enabled.
DCLOOTS_ENABLED = os.environ.get("DCLOOTS_ENABLED", "0") == "1"
DCLOOTS_POLL_MINUTES = int(os.environ.get("DCLOOTS_POLL_MINUTES", "30"))

DB_PATH = BASE_DIR / os.environ.get("DB_PATH", "offers.db")
MEDIA_DIR = BASE_DIR / os.environ.get("MEDIA_DIR", "media")
MEDIA_DIR.mkdir(parents=True, exist_ok=True)
# Post images are shrunk to this width to keep the published site small.
IMAGE_MAX_WIDTH = int(os.environ.get("IMAGE_MAX_WIDTH", "480"))

# Offers older than this are removed (deals expire fast).
KEEP_DAYS = int(os.environ.get("KEEP_DAYS", "14"))
