# NIRVANA

A three-friend league for cutting Instagram time: daily, weekly and monthly winners.

- **Website**: https://nayanrathi1845.github.io/NIRVANA/ (the `docs` folder). Sign up with email and password, join with the league's invite code, log your days and see the standings. On iPhone, open it in Safari and tap Share → **Add to Home Screen** to use it like an app.
- **Android app** (the `app` folder): reads Instagram and other short-form minutes, app opens, and the first and last hour of your day straight from the phone. Sign in with your NIRVANA email and password, and **Save to NIRVANA** puts the day on the leaderboard.
- **Database and logins**: Firebase project `nirvana-2e2d1`. The security rules are in `firebase/firestore.rules`. The real invite code is kept out of this public repository.

## Joining
1. Open the website and choose **Create account**.
2. Enter the invite code from Nayan, your name and your Week 0 baseline: your average daily Instagram minutes and app opens from a normal week.
3. Android users: install `NIRVANA.apk` from [Releases](https://github.com/nayanrathi1845/NIRVANA/releases/latest), turn on usage access when the app asks, and sign in with the same email and password.

## What the Android app reads
- **Short-form minutes**: time in Instagram, Instagram Lite, Moj and Josh by default. YouTube, Facebook, Threads and Snapchat can be switched on in the app. YouTube Shorts can't be separated from the rest of YouTube.
- **App opens**: reopening the same app within 30 seconds counts as one open.
- **First hour after waking**: the first unlock after 4 am counts as waking up.
- **Last hour before sleep**: the start of the longest screen-off stretch (3 hours or more) between 6 pm and 10 am. This is known the next morning, so switch to **Yesterday** and save again.

Saving from the app only updates what the app measures, so a one-line takeaway you added on the website is kept.

## Building
Every push to `main` builds the Android app with GitHub Actions and publishes `NIRVANA.apk` on the Releases page. Each new build installs over the old one.
