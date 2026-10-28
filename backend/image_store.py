"""Where deal images are kept.

- CloudinaryStore (when CLOUDINARY_URL is set): uploads each image and returns its public HTTPS
  link right away. Used by the live listener, whose own disk doesn't survive restarts.
- LocalStore: files under MEDIA_DIR, served as /media/<name> (local server, GitHub Pages sync).

CLOUDINARY_URL is the value shown on the Cloudinary dashboard: cloudinary://<key>:<secret>@<cloud>
"""
import hashlib
import logging
import os
import time
from urllib.parse import urlparse

import httpx

from config import MEDIA_DIR

log = logging.getLogger("images")


class LocalStore:
    async def save(self, name: str, data: bytes) -> str:
        (MEDIA_DIR / name).write_bytes(data)
        return f"/media/{name}"

    def exists(self, name: str) -> str | None:
        return f"/media/{name}" if (MEDIA_DIR / name).exists() else None

    async def delete(self, image: str | None) -> None:
        if image and image.startswith("/media/"):
            (MEDIA_DIR / image.removeprefix("/media/")).unlink(missing_ok=True)


class CloudinaryStore:
    FOLDER = "offershub"

    def __init__(self, url: str):
        parsed = urlparse(url)
        self.cloud, self.key, self.secret = parsed.hostname, parsed.username, parsed.password
        if not (self.cloud and self.key and self.secret):
            raise ValueError("CLOUDINARY_URL must look like cloudinary://<key>:<secret>@<cloud>")
        self._http = httpx.AsyncClient(timeout=30)

    def _signed(self, params: dict) -> dict:
        params = {**params, "timestamp": int(time.time())}
        to_sign = "&".join(f"{k}={params[k]}" for k in sorted(params))
        params["signature"] = hashlib.sha1((to_sign + self.secret).encode()).hexdigest()
        params["api_key"] = self.key
        return params

    def exists(self, name: str) -> str | None:
        return None  # uploads overwrite by name, so re-saving is harmless

    async def save(self, name: str, data: bytes) -> str:
        public_id = name.rsplit(".", 1)[0]
        resp = await self._http.post(
            f"https://api.cloudinary.com/v1_1/{self.cloud}/image/upload",
            data=self._signed({"public_id": public_id, "folder": self.FOLDER, "overwrite": "true"}),
            files={"file": (name, data, "image/jpeg")},
        )
        resp.raise_for_status()
        return resp.json()["secure_url"]

    async def delete(self, image: str | None) -> None:
        if not image or f"res.cloudinary.com/{self.cloud}/" not in image:
            return
        # .../image/upload/v123/offershub/tg_1_2.jpg -> offershub/tg_1_2
        path = urlparse(image).path.split("/upload/", 1)[1]
        public_id = path.split("/", 1)[1] if path.startswith("v") and "/" in path else path
        public_id = public_id.rsplit(".", 1)[0]
        try:
            resp = await self._http.post(
                f"https://api.cloudinary.com/v1_1/{self.cloud}/image/destroy",
                data=self._signed({"public_id": public_id}),
            )
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            log.info("couldn't delete %s: %s", public_id, exc)


def _make_store():
    url = os.environ.get("CLOUDINARY_URL", "").strip()
    return CloudinaryStore(url) if url else LocalStore()


store = _make_store()
