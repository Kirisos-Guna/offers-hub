# Offers Hub

An Android app (Material 3, light and dark themes) that shows deals from Telegram offer channels
in one live feed, with product images, store filters, search, and an instant notification for
every new deal. Tap **Buy** to open the deal in the shop's own app.

Everything runs on free tiers that need no card:

```
Telegram channels ──► listener (Render, free) ──► Firebase (Spark) ──► Android app
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
| `render.yaml` | Render Blueprint for the listener (free plan, Singapore) |
| `firebase/` | Firestore security rules |
| `.github/workflows/keep-listener-awake.yml` | Backup pinger so the listener never sleeps |
| `backend/sync_static.py`, `sync-deals.yml` | Older GitHub Pages pipeline (fallback) |

## Production setup

### 1. Firebase (free Spark plan)
1. **Firestore Database** → Create → **Standard edition**, database ID `(default)`,
   location `asia-south1` (Mumbai, closest to users in India), production mode.
   In its **Rules** tab, paste `firebase/firestore.rules` and publish.
2. **Project settings → Your apps → Android**: package `com.offershub.app`. Download
   `google-services.json` to `android/app/google-services.json` (git-ignored).
3. **Project settings → Service accounts → Generate new private key**. Keep the downloaded JSON
   private; its contents become the Render secret `FIREBASE_SERVICE_ACCOUNT`.

### 2. Cloudinary (free, deal images)
Sign up at <https://cloudinary.com>. On the dashboard, copy the **API environment variable**
(`cloudinary://<key>:<secret>@<cloud>`); it becomes the Render secret `CLOUDINARY_URL`.

### 3. Render (free, the listener)
1. Create a Telegram session just for the listener:
   `backend\.venv\Scripts\python.exe backend\login.py --string`
2. Sign up at <https://render.com> with GitHub → **New → Blueprint** → pick this repository.
   Render reads `render.yaml` and asks for the five secrets: `TG_API_ID`, `TG_API_HASH`,
   `TG_SESSION_STRING`, `FIREBASE_SERVICE_ACCOUNT` (the whole key JSON), `CLOUDINARY_URL`.
   **Apply** builds and starts it; every push to `main` redeploys it.
3. The service's page (`https://offers-hub-listener.onrender.com/`) shows `{"state": "live", ...}`.
4. Keep it awake: free account at <https://cron-job.org> → a job that opens that URL every
   10 minutes. (`keep-listener-awake.yml` on GitHub is a backup.)
5. Once it's live, stop the GitHub sync: `gh workflow disable "Sync deals"`.

Render stops free services after 15 minutes without visits (the pinger prevents that) and
restarts them now and then, wiping their disk. The listener reloads its state from Firestore
and catches up on anything posted meanwhile, so no deal is missed or announced twice; at most
it arrives about a minute late. 750 free hours a month cover one service running 24/7.

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
- Live feed: new deals appear on screen within seconds; a "N new deals" pill when scrolled down.
  The newest 60 deals are live; older ones load page by page while scrolling.
- 🔥 **Hot right now**: biggest discounts of the last 3 hours (real products with a price)
- **Categories** (Electronics, Fashion, Home & Kitchen, …), store chips, search, sort
- **Ended deals**: posts the channel edits to "Over now ❌ / Sold out / Expired" are faded and never announced
- **Saved deals ♥**, kept on the phone even after a deal leaves the feed
- Deal sheet: big image, price, MRP, "You save", one button per link, share as text or **as an image**
- **Notifications** for every new deal (picture + **Buy now**), or only for **your keywords**
  ("iphone", "air fryer"), with a minimum discount; tapping opens the deal
- **Home-screen widget** with the latest deals
- **English, Tamil and Hindi** (Android 13+: Settings → Language)
- Theme: System / Light / Dark, optional wallpaper colours (Android 12+)
- In-app updates from GitHub releases; crash reports in Firebase Crashlytics
- Works offline with the last loaded deals

## Optional settings (Render environment variables)
| Variable | What it does |
|---|---|
| `AMAZON_AFFILIATE_TAG`, `FLIPKART_AFFILIATE_ID` | Put **your** affiliate IDs on deal links. Off by default. The posts carry the channel owners' affiliate links, so only turn this on with their permission. |
| `KEEP_DAYS` | How long deals stay (default 7). |

## Monitoring
- `https://offers-hub-listener.onrender.com/` shows the listener's state, channels and errors.
- It answers **503** when something is wrong (error, or no new deal for 6 hours). cron-job.org
  then emails you, and Render restarts the service.
- App crashes appear in the Firebase console under **Crashlytics**.

## Using a separate Telegram account for the listener
So that a Telegram restriction on the listener can't affect your personal account:
1. Create a Telegram account with another phone number, and join every channel with it
   (open each invite link in Telegram; for expired links, ask the channel or find it by name).
2. If a channel's invite link changed, update it in `backend/config.py` (or use `title:<name>`).
3. Run `backend\.venv\Scripts\python.exe backend\login.py --string`, log in with the new number,
   and put the printed string into Render → Environment → `TG_SESSION_STRING`.
   Render restarts the listener with the new account.

## Not done yet: Amazon's product API
Real titles, photos and prices for the deals Amazon currently blocks need Amazon's own product
API, which requires an Amazon Associates account with qualifying sales. Once you have API
access, the listener can use it instead of reading Amazon's pages.

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
