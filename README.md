# Offers Hub

An Android app (Material 3) that shows deals from your Telegram offer channels in one feed, with
images. You can filter by store (Amazon, Flipkart, Myntra…), search, sort by discount or price,
and tap **Buy** to open the deal in the shop's app. The app notifies you when new deals arrive.

No PC needs to be on:

```
Telegram channels ──► GitHub Actions (every ~10 min) ──► GitHub Pages ──► Android app
                      reads new posts as your account     offers.json      refreshes every 2 min
                      shrinks images                      + media/*.jpg    while open; background
                                                                           check every 15 min
```

The channels use private invite links, so a Telegram bot can't read them (bots only read
channels where they are admin). The sync job logs in as **your** Telegram account instead. For
safety, use a spare Telegram account that has joined the channels.

## Setup (one time)

1. **Telegram API keys:** at <https://my.telegram.org> → *API development tools*, create an app
   and note the `api_id` and `api_hash`. Put them in `backend/.env` (copy `.env.example`).
2. **Session string:** run this and enter your phone number and the code Telegram sends you:
   ```
   cd backend
   .venv\Scripts\python.exe login.py --string
   ```
   It prints a long session string. Keep it secret: it gives access to that Telegram account.
3. **GitHub repo:** create a **public** repo named `offers-hub` and push this folder. A public
   repo is required for free Pages and gives unlimited Actions minutes. The repo contains only
   code; the session string is stored as a secret.
4. **Secrets:** in the repo, go to *Settings → Secrets and variables → Actions* and add
   `TG_API_ID`, `TG_API_HASH` and `TG_SESSION_STRING`. Or with the GitHub CLI:
   ```
   gh secret set TG_API_ID
   gh secret set TG_API_HASH
   gh secret set TG_SESSION_STRING
   ```
5. **First run:** go to *Actions → Sync deals → Run workflow*. The first run imports the latest
   100 posts per channel and creates the `gh-pages` branch.
6. **Pages:** go to *Settings → Pages* and set *Source* to "Deploy from a branch", branch
   `gh-pages`, folder `/ (root)`. The feed is then at
   `https://<your-user>.github.io/offers-hub/offers.json`.
7. **App:** install `OffersHub.apk`. It points at `https://kirisos-guna.github.io/offers-hub` by
   default. If you used a different repo or user name, change the address with ⚙.

After setup, the job runs every 10 minutes. GitHub sometimes starts scheduled runs late, so
expect deals to appear about 10–20 minutes after they're posted. Deals are kept for 7 days.

### Limits to know
- GitHub only runs scheduled workflows in repos with recent activity. After **60 days without a
  commit**, the schedule is paused and GitHub emails you; re-enable it on the Actions tab.
- GitHub's terms ask that Actions be used for the repo's software project. A small scheduled
  job that publishes data to Pages is common, but heavy use can get a repo flagged.
- If the sync job fails with "not logged in", Telegram ended the session. Run
  `login.py --string` again and update the secret.
- **dcloots.in** answers automated requests with 403, so it isn't read. Its deals also come
  through the "DC LOOTS Offers And Deals" Telegram channel.

## Running everything on your PC instead (optional)

`backend\run.bat` starts a live server (`server.py`) that serves the same `/offers.json`, with
deals appearing within seconds. It needs `login.py` (without `--string`) once. In the app, set
the address to `http://<your PC's IP>:8000`.

## Development
- Parser tests: `cd backend && .venv\Scripts\python.exe test_parser.py`
- Sample deals for a local server: `seed_demo.py` (remove with `seed_demo.py --clear`)
- App: open `android/` in Android Studio, or run `gradlew assembleRelease`
- The release APK is signed with the debug key. Use your own keystore before publishing it.
- The links are the channel owners' affiliate links. Ask them before releasing the app publicly.
