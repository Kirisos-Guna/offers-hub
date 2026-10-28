# Offers Hub

An Android app (Material 3, light and dark themes) that shows deals from Telegram offer channels
in one live feed, with product images, store filters, search, and an instant notification for
every new deal. Tap **Buy** to open the deal in the shop's own app.

Everything runs on free tiers that need no card:

```
Telegram channels ──► listener (Hugging Face Space) ──► Firebase (Spark) ──► Android app
                      reads each post within a second   Firestore            live feed
                      finds product image + name        Cloud Messaging      instant notifications
                      uploads images ──► Cloudinary (free) ─────────────────► deal images
```

The channels use private invite links, so a Telegram bot can't read them; the listener reads them
as a Telegram user account. Use a spare account that has joined the channels.

## Repository layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose) |
| `backend/listener.py` | Always-on listener: Telegram → Firebase |
| `backend/offer_parser.py`, `product_images.py` | Turn posts into deals; images/names from links |
| `backend/firebase_publisher.py` | Writes deals to Firestore, sends notifications, keeps listener state |
| `backend/image_store.py` | Image uploads to Cloudinary |
| `deploy/hf_space/`, `deploy/push_hf_space.ps1` | Hugging Face Space container and deploy script |
| `firebase/` | Firestore security rules |
| `.github/workflows/keep-listener-awake.yml` | Pings the Space every 6 h so it never sleeps |
| `backend/sync_static.py`, `sync-deals.yml` | Older GitHub Pages pipeline (fallback) |

## Production setup

### 1. Firebase (free Spark plan)
1. **Firestore Database** → Create → **Standard edition**, database ID `(default)`,
   location `asia-south1` (Mumbai, closest to users in India), production mode.
   In its **Rules** tab, paste `firebase/firestore.rules` and publish.
2. **Project settings → Your apps → Android**: package `com.offershub.app`. Download
   `google-services.json` to `android/app/google-services.json` (git-ignored).
3. **Project settings → Service accounts → Generate new private key**. Keep the downloaded JSON
   private; its contents become the Space secret `FIREBASE_SERVICE_ACCOUNT`.

### 2. Cloudinary (free, deal images)
Sign up at <https://cloudinary.com>. On the dashboard, copy the **API environment variable**
(`cloudinary://<key>:<secret>@<cloud>`); it becomes the Space secret `CLOUDINARY_URL`.

### 3. Hugging Face Space (free, the listener)
1. Create a Space: name `offers-hub-listener`, SDK **Docker** (blank), hardware **CPU basic (free)**.
2. **Settings → Variables and secrets**, add these as *secrets*:
   `TG_API_ID`, `TG_API_HASH`, `TG_SESSION_STRING`, `FIREBASE_SERVICE_ACCOUNT`, `CLOUDINARY_URL`.
   Create the session string with `backend\.venv\Scripts\python.exe backend\login.py --string`
   (a fresh Telegram login just for the listener).
3. Deploy: `hf auth login` once (write token), then `powershell -File deploy\push_hf_space.ps1`.
   The Space builds, restores its state from Firestore, catches up, and shows
   `{"state": "live", ...}` on its page.
4. Once it's live, stop the GitHub sync: `gh workflow disable "Sync deals"`.

Free Spaces sleep after ~48 h without visits; the keep-awake workflow visits every 6 h. Spaces
also restart now and then; the listener then reloads its state from Firestore and catches up on
anything posted meanwhile, so no deal is missed or announced twice.

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
