# NIRVANA for Android

The Android companion to the NIRVANA web page. It reads your short-form app time straight from the phone, works out your day score, and sends the numbers to NIRVANA with one tap.

## What it reads automatically
- **Short-form minutes**: time in Instagram, Instagram Lite, Moj and Josh by default. You can add YouTube, Facebook, Threads or Snapchat in the app's settings.
- **App opens**: reopening the same app within 30 seconds counts as one open.
- **First hour after waking**: your first unlock after 4 am counts as waking up. The hour is kept if no short-form app was opened in the 60 minutes after it.
- **Last hour before sleep**: bedtime is the start of the longest screen-off stretch (3 hours or more) between 6 pm and 10 am. This is only known the next morning, so choose **Yesterday** to get it.

You enter focus blocks, diverse inputs and your Week 0 baseline yourself.

YouTube Shorts can't be separated from the rest of YouTube in Android's usage data. If you switch YouTube on, all of your YouTube time counts. All three of you should switch on the same apps.

## Build the app (one time, about 10 minutes, free)
You need a free GitHub account. GitHub builds the app for you, so nothing needs installing on your computer.

1. Sign in at github.com and create a **new repository** named `nirvana`. Leave "Add a README" unticked.
2. On the empty repository page, click **uploading an existing file**.
3. Unzip this project. Drag **everything inside the `nirvana-android` folder** into the upload box, including the hidden `.github` folder, then click **Commit changes**.
   - If `.github` doesn't upload (some computers hide it), click **Add file → Create new file**, name it `.github/workflows/build.yml`, paste in the contents of that file from the zip, and commit.
4. Open the **Actions** tab. A run called "Build NIRVANA app" starts on its own and takes about 5 minutes. If Actions asks you to enable workflows, click the green button.
5. When the run shows a green tick, open **Releases** on the right side of the repository page and download **NIRVANA.apk**.

## Install on each Android phone
1. Send `NIRVANA.apk` to the Android users (WhatsApp works), or open the Releases page on the phone.
2. Tap the file. If Android blocks it, allow **Install unknown apps** for WhatsApp, Chrome or Files, then tap it again. If Play Protect shows a warning, choose **Install anyway**. The warning appears for any app that isn't from the Play Store.
3. Open NIRVANA and tap **Open settings**. Find NIRVANA and turn on **Permit usage access**.
4. Enter your Week 0 baseline minutes and opens.

## Daily use
- Before bed: open the app, add focus blocks and diverse inputs, and tap **Send to NIRVANA**. The web page opens with your numbers filled in. Pick your name and tap **Save day**.
- Next morning (optional): switch to **Yesterday** and send again. This adds the night hour.
- **Share to the group** posts a summary to WhatsApp and replaces the screenshot rule for Android users.

iPhone users keep logging by hand on the NIRVANA page. Apple doesn't let a sideloaded app read Screen Time.

## Updating the app
Change a file on GitHub (for example `versionCode` in `app/build.gradle`) and commit. A new build appears under Releases, and it installs over the old one without losing your settings.
