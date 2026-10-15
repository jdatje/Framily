# Framily

An Android live wallpaper that rotates your best family photos, with an iPhone-style clock that people can stand in front of.

Everything runs on the phone: photos are never uploaded.

## Installing on your phone

1. On your phone, open **github.com/jdatje/framily/releases/latest** (log in to GitHub if asked).
2. Download `Framily-<number>.apk` and open it.
3. The first time, Android asks to allow installing from your browser. Allow it, go back, and install.
4. New versions install the same way, over the old one. Your settings are kept.

Every push to `main` builds a new APK automatically (Actions tab → "Build APK").

## First run

1. Open Framily and tap **Allow**. Choose **Allow all** for photos.
2. Tap **Set wallpaper** and choose **Home screen**.
3. The scan starts automatically. With ~50,000 photos it takes a few hours: plug in your phone and leave it. The first wallpaper appears as soon as a good photo is found.

## How photos are chosen

1. **Filter:** portrait photos only, from the folders you tick, with at least one face bigger than the minimum face size.
2. **Score (0–100):** face size 25 · sharpness 25 · eyes open 15 · smiling 15 · looking at the camera 10 · faces well placed 10.
3. **Rotation:** only the best N photos (setting). Every photo in the pool comes by before one repeats.

## Feedback

- **Notification** "Current wallpaper" with *Skip*, *Not family* and *Never show*.
- **Double-tap** an empty spot on the home screen opens the same choices (depends on the launcher passing taps through).
- In the app, tap any photo in the rotation to show it now or remove it.

## Clock

Hours on top, minutes below, in a color taken from the photo. With **Depth effect** on, people are cut out of the photo and drawn over the clock. If they would cover too much of the time, the clock stays on top for that photo.

The lock screen option sets the same photo there without the Framily clock, since the phone shows its own.

## Roadmap

- **Next:** face recognition so only family counts (enroll family members from a few photos; "Not family" feedback trains it).
- **Later:** separate lock/home photos, "on this day" mode, adjustable score weights.

## Building it yourself

Open the folder in Android Studio and press Run, or `./gradlew assembleDebug`.
