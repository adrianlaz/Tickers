# Tickers · by Aari

Android home-screen widgets for crypto and stock prices (BTC, ETH, SPY, NVDA by default, add any
Yahoo Finance symbol). Widgets show price and 24h change; tapping opens the app on a chart.

## Build (Docker, nothing installed on the host)

```sh
./dw assembleDebug        # first run builds the image + Gradle wrapper (~5 min)
./dw testDebugUnitTest    # unit tests (Yahoo parsing against saved fixtures, 24h change rule)
./dw install              # build, then install on a USB-connected phone with the host's adb
./dw --rebuild-image      # after changing docker/Dockerfile
./dw keygen               # once: create the signing key (already done on this PC)
./dw verify-signing [apk] # show which certificate an APK is signed with
```

The Gradle cache lives in `~/.cache/tickers-gradle` (on /home, not in Docker's data-root).
The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Phone setup: Settings → About phone → tap Build number 7× → Developer options → USB debugging on,
plug in, accept the prompt, check `adb devices`.

## Portfolio

Record Revolut X buys and sells (Trades tab, + button) to track profit. Each buy is a position;
a sell uses the oldest positions first (FIFO) unless you pick positions yourself. The Portfolio tab
shows value, realized/unrealized profit, ROI, average and break-even price, fees, win rate, a value
vs. cost chart, every open position, and a sale simulator. USDC is valued at $1.
Trades live only on the phone: use Settings → Export to keep a backup file.

## Widgets

- **Ticker**: one asset per widget; you pick it when placing (long-press later to reconfigure).
- **Watchlist**: all assets, resizable, with a refresh button and "Updated HH:mm".

Background refresh runs every 15 minutes (Android's minimum) when online.

## 24h change

Uses Yahoo's `regularMarketChangePercent`, which is a rolling 24h change for crypto and the change
vs. the previous close for stocks/ETFs (verified against live data). If Yahoo omits it, crypto falls
back to the bar nearest 24h ago and stocks to `previousClose`; see `data/ChangeCalculator.kt`.

## Data source

Yahoo Finance's unofficial endpoints (personal use). Everything goes through
`data/MarketDataSource.kt`, so another provider can be swapped in by implementing that interface.

## Signing key (important)

Every APK, debug and release, is signed with one key in `~/.config/tickers-signing/`
(outside the repo, never committed). Android only installs an update if it is signed with the
same key as the installed app. **Back that folder up.** If it is lost, the app must be uninstalled
and reinstalled, and the in-app updater stops working for existing installs.

## Releases and in-app updates

The app checks `https://github.com/adrianlaz/Tickers` releases (set by `tickers.githubRepo` in
`gradle.properties`): automatically at most every 6 hours on app open, or via Settings → Check for updates.
When a newer version exists it shows an Install button, downloads the APK and opens Android's
install prompt. The first time, Android asks you to allow Tickers to install apps.

The repo must be public, because the app checks GitHub without a token and a token embedded in the
APK could be extracted by anyone.

One-time setup:

```sh
gh auth login
git add -A && git commit -m "Initial commit"
gh repo create adrianlaz/Tickers --public --source . --push
```

Each release:

```sh
./release.sh 0.2.0   # bumps version, builds a signed APK, tags, pushes, creates the GitHub release
```

The first install on the phone is manual (`./dw install`, or download the APK from the release page).
Every later version can be installed from inside the app.
