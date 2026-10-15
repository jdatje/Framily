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
3. **Similar photos:** every photo gets a visual fingerprint. Burst shots of the same moment (and copies, like the WhatsApp copy of your own photo) are grouped; only the best-scoring one is used. Strictness is a setting.
4. **Rotation:** only the best N photos (setting). Every photo in the pool comes by before one repeats.

## Weather

Optional: current weather ("⛅ 18°") and a strip with the next 4 hours (time, icon, temperature, chance of rain from 20%). It uses the clock's font, color and depth effect.

- **Location:** your phone's approximate location (updated whenever you open Framily, so it follows you when you travel) or a fixed city.
- **Position:** right under the clock, or at its own height.
- Data from [Open-Meteo](https://open-meteo.com) (free, no account), refreshed once an hour while online.

## Battery

The first scan of a large library takes a while, so it is careful with the battery:

- **Only while charging** (default on): pauses when unplugged, continues when plugged in again.
- **Speed:** Gentle (default) / Normal / Fast — how long it rests between photos.
- Always: slower while the screen is on, slows down when the battery drops even though it's plugged in (slow charger), pauses when the phone gets warm, and stops below 30% when running on battery.
- A quick, cheap face check runs first; the precise (heavier) one only for photos that actually have faces.
- Stop it anytime from the app or the scan notification. It always continues where it left off.

## Feedback

- **Double-tap** an empty spot on the home screen: next photo (depends on the launcher passing taps through).
- **Notification** "Current wallpaper" with *Skip*, *Not family* and *Never show*.
- In the app: *Not family* / *Never show* under the current photo, and the same options on any photo in the rotation grid.

## Clock

Fonts, stacked (07 / 50) or one line (07:50), size, color (from the photo, white or black), opacity, height and an optional date. The app shows a live preview using the real wallpaper code.

With **Depth effect** on, people are cut out of the photo and drawn over the clock. If they would cover too much of the time, the clock stays on top for that photo.

Bundled fonts (Anton, Bebas Neue, Righteous, DM Serif Display, Abril Fatface, Pacifico) are from Google Fonts under the SIL Open Font License.

The lock screen option sets the same photo there without the Framily clock, since the phone shows its own.

## Roadmap

- **Next:** face recognition so only family counts (enroll family members from a few photos; "Not family" feedback trains it).
- **Later:** separate lock/home photos, "on this day" mode, adjustable score weights.

## Building it yourself

Open the folder in Android Studio and press Run, or `./gradlew assembleDebug`.
