# XMR CPU Miner

An Android app that mines Monero (XMR) using the device's own CPU via the
RandomX algorithm, and pays out directly to a wallet address the user
controls.

## What this is (and isn't)

- **Real, on-device mining only.** All hashing happens on the phone this
  app is installed on, via a real xmrig subprocess. There is no
  cloud/remote mining component, and the app doesn't collect, pool, or
  redirect anyone's wallet address or hashrate.
- **The pool and wallet are user-controlled.** Both are entered in the app
  by whoever installs it; nothing is hardcoded, and there's no developer
  wallet skimming a cut beyond xmrig's own configurable, disclosed
  donation setting (default 1%, adjustable to 0 in the app).
- **Not on the Play Store.** Google Play's Developer Program Policy
  prohibits apps that mine cryptocurrency on-device, so this is meant to
  be sideloaded (direct APK), not published there.

## Before you run this

Mining is CPU-intensive by design:

- It will drain the battery quickly and keep the device warm/hot.
- Sustained heat can throttle performance or affect battery longevity over
  time.
- On most phones, the electricity/battery-wear cost is close to or higher
  than the (very small) amount of XMR a phone can realistically mine —
  this is a real mining client, not a way to profit from idle phones.

The app shows this disclosure before starting and keeps a persistent,
stoppable notification visible the entire time it mines (Android requires
foreground services to stay visible while running).

## Setup

1. Build or obtain an official xmrig binary for Android ARM and place it
   at `app/src/main/jniLibs/<abi>/libxmrig.so` — see
   `app/src/main/jniLibs/README.md` for exact steps. This repo does not
   ship a compiled binary.
2. `./gradlew assembleDebug` (or open in Android Studio).
3. Install the APK, open the app, enter a pool (`host:port`) and your own
   Monero wallet address, pick a thread count, and tap Start.

## How it works

- `MainActivity` collects pool/wallet/thread/donation settings and shows
  the mining disclosure dialog before starting.
- `MiningService` is a foreground service that runs the xmrig binary as a
  subprocess with those settings, parses its stdout for live hashrate, and
  shows it in a persistent notification and in the app.
- Stopping (from the app or the notification's Stop action) kills the
  subprocess, releases the wake lock, and removes the foreground
  notification.
