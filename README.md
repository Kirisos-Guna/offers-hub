# Offers Hub

An Android app (Material 3, light and dark themes) that shows deals from Telegram offer channels
in one live feed, with product images, store filters, search, and an instant notification for
every new deal. Tap **Buy** to open the deal in the shop's own app.

```
Telegram channels ──► listener (Google Cloud VM, 24/7) ──► Firebase ──► Android app
                      reads each post within a second      Firestore    live feed (no refresh needed)
                      finds product image + name            Storage      deal images
                                                            FCM topic    instant notifications
```

The channels use private invite links, so a Telegram bot can't read them; the listener reads them
as a Telegram user account. Use a spare account that has joined the channels.

## Repository layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose) |
| `backend/listener.py` | Always-on listener: Telegram → Firebase |
| `backend/offer_parser.py`, `product_images.py` | Turn posts into deals; images/names from links |
| `backend/firebase_publisher.py` | Writes deals to Firestore/Storage, sends notifications |
| `deploy/` | VM setup script and systemd service |
| `firebase/` | Firestore and Storage security rules |
| `backend/sync_static.py`, `.github/workflows/` | Older GitHub Pages pipeline (fallback; see below) |

## Production setup

### 1. Firebase project (once, in the browser)
1. <https://console.firebase.google.com> → **Add project** (Analytics not needed).
2. Switch the project to the **Blaze** plan. A card is required, but this setup stays inside
   the free quotas (Firestore, Storage in a US region, Cloud Messaging, one e2-micro VM).
3. **Firestore Database** → Create → production mode → location `us-central1`.
4. **Storage** → Get started → location `us-central1`.
5. **Project settings → Your apps → Android**: package `com.offershub.app`. Download
   `google-services.json` to `android/app/google-services.json` (git-ignored).
6. Publish the rules from `firebase/firestore.rules` and `firebase/storage.rules`
   (paste them in the console's Rules tabs, or `firebase deploy --only firestore:rules,storage`).

### 2. Listener VM (Google Cloud, same project)
1. <https://console.cloud.google.com> → Compute Engine → **Create instance**:
   `e2-micro`, region `us-central1`, Debian 12, 30 GB standard disk,
   **Access scopes: Allow full access to all Cloud APIs** (the VM's own account then has access
   to Firestore, Storage and Messaging; no key files needed).
2. Open **SSH** on the VM and run:
   ```
   curl -fsSL https://raw.githubusercontent.com/Kirisos-Guna/offers-hub/main/deploy/setup_vm.sh | sudo bash
   sudo -u offershub nano /opt/offershub/backend/.env
   ```
   Put in `TG_API_ID`, `TG_API_HASH` and `FIREBASE_BUCKET=<project-id>.firebasestorage.app`.
3. Log in to Telegram (phone number + code), then start the service:
   ```
   sudo -u offershub /opt/offershub/backend/.venv/bin/python /opt/offershub/backend/login.py --env
   sudo systemctl start offershub-listener
   journalctl -u offershub-listener -f
   ```
   It catches up on recent posts, then logs `live: listening for new deals`.
4. Updating later: run the same `setup_vm.sh` line again (it pulls and restarts).
5. Once the listener runs, disable the GitHub workflow (`gh workflow disable "Sync deals"`).

### 3. App release
1. Build: `cd android && gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
   With `google-services.json` present the app uses the live Firebase feed; without it, it falls
   back to the GitHub Pages feed.
2. Signing: `android/keystore.properties` points at `keystore/offershub-release.jks` (both
   git-ignored). **Back up both files somewhere safe.** Every update must be signed with this
   key; if it's lost, users have to uninstall and reinstall.
3. Publish a GitHub release with the APK attached (tag `vX.Y.Z`, bump `versionCode` and
   `versionName` in `android/app/build.gradle.kts`). Installed apps find it on their own: a
   banner offers the update, downloads it and opens Android's installer.

## App features
- Live feed: new deals appear on screen within seconds; a "N new deals" pill when scrolled down
- Search, store filter chips, sort by latest / top discount / lowest price
- Deal sheet: big image, price, MRP, "You save", one button per link, share
- Notifications for every new deal with picture and **Buy now** button; filter by minimum
  discount; tapping opens the deal
- Theme: System / Light / Dark, optional wallpaper colours (Android 12+)
- In-app updates from GitHub releases
- Works offline with the last loaded deals

## Development
- Tests: `cd backend && .venv\Scripts\python.exe test_parser.py && .venv\Scripts\python.exe test_publisher.py`
- Local API for experiments: `backend\run.bat` (serves `/offers.json` on port 8000)
- Test the update flow: build a debug copy that looks older,
  `gradlew assembleDebug "-PversionCodeOverride=1" "-PversionNameOverride=1.0.0"`

## Notes
- The links are the channel owners' affiliate links; ask them before releasing the app publicly.
- Amazon sometimes answers automated page requests with a captcha; those deals keep a
  placeholder title ("Amazon deal") or the store tile instead of a product photo.
- `dcloots.in` blocks automated access; its deals come from the DC LOOTS Telegram channel.
