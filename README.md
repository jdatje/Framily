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
2. **Score (0–100):** face size 15 · sharpness 15 · eyes open 15 · smiling 15 · room above the head for the clock 15 · no cut-off faces 10 · centered 5 · family members in the photo 10. Scores are computed from values stored during the scan, so the formula can change without rescanning.
3. **Similar photos:** every photo gets a visual fingerprint. Burst shots of the same moment (and copies, like the WhatsApp copy of your own photo) are grouped; only the best-scoring one is used. Strictness is a setting.
4. **Rotation:** only the best N photos (setting). Every photo in the pool comes by before one repeats.

## Family recognition

1. During the scan every face gets a "face print" (192 numbers from the MobileFaceNet model, on the phone). Photos of the same person give similar prints.
2. Framily groups faces that look alike. In **Family**, you name each group once ("Family" with a name, or "Not family"). Groups that look like someone you already named show "Yes, <name>".
3. Every face is compared with the faces you named. A photo counts when a family member is in it and *their* face meets the minimum face size.
4. "Not family" (notification, app, or a group) is remembered as a counter-example, so similar-looking people are skipped too. In a person's page you can tap faces that aren't them.

The recognition strictness is adjustable (loose ↔ strict). Photos scanned by an earlier version get their faces analysed again by the next scan.

Model: MobileFaceNet (`mobilefacenet.tflite`, BSD-3-Clause, see `assets/mobilefacenet-LICENSE.txt`), run through Google Play services' TensorFlow Lite; face detection also comes from Play services, which keeps the app small.

## Weather

Optional: current weather ("⛅ 18°") and a strip with the next 4 hours (time, icon, temperature, chance of rain from 20%). It uses the clock's font, color and depth effect.

- **Location:** your phone's approximate location (updated whenever you open Framily, so it follows you when you travel) or a fixed city.
- **Position:** beside the clock (current weather left, next hours right, vertically), under the clock, or anywhere: drag them on the **Arrange** screen.
- Separate size sliders for the current weather and the forecast.
- Data from [Open-Meteo](https://open-meteo.com) (free, no account), refreshed once an hour while online.

## Readability & extras

- **Background** behind weather and caption (optionally the clock): none, soft shape or frosted glass, with opacity, roundness and color.
- **Outline** around letters, and **Auto contrast** (light or dark text per spot, based on the photo behind it).
- **Night dimming** after sunset (sunrise/sunset of the weather location, otherwise 21:00–07:00).
- **Rain alert** line ("🌧 Rain from 17:00" / "🌤 Dry from 18:00").
- **Tap the weather** to open your weather app (Pixel Weather by default, or pick one).
- **Caption** with month/year and place from the photo's GPS ("June 2021 · Texel"); draggable.
- **On this day:** photos from this date in earlier years go first ("7 years ago today").
- **Lock screen** can show a different photo than the home screen.

Settings are grouped on pages (Photos & rotation, Clock, Weather, Readability, Extras, Scanning); look pages have a pinned live preview.

## Settings backup

Extras → Settings backup exports all settings to a JSON file and imports it again (scan results and family names stay on the phone).

## Battery

The first scan of a large library takes a while, so it is careful with the battery:

- **Only while charging** (default on): pauses when unplugged, continues when plugged in again.
- **Speed:** Gentle (default) / Normal / Fast — how long it rests between photos.
- Always: slower while the screen is on, slows down when the battery drops even though it's plugged in (slow charger), pauses when the phone gets warm, and stops below 30% when running on battery. While charging there's no "battery low" wait.
- The Scanning page says why a scan is waiting (charger, battery level, or when it retries).
- A quick, cheap face check runs first; the precise (heavier) one only for photos that actually have faces.
- Stop it anytime from the app or the scan notification. It always continues where it left off.

## Feedback

- **Double-tap** an empty spot on the home screen: next photo (depends on the launcher passing taps through).
- **Notification** "Current wallpaper" with *Skip*, *Not family* and *Never show*.
- In the app: *Not family* / *Never show* under the current photo, and the same options on any photo in the rotation grid.

## Clock

Fonts, stacked (07 / 50) or one line (07:50), size, color (from the photo, white or black), opacity, height and an optional date. The app shows a live preview using the real wallpaper code.

**Position:** *Behind heads (auto)* places the clock so the main person's head covers its lower part, like on an iPhone; or a fixed height. On the **Arrange** screen you can drag the clock anywhere.

While you drag a slider, a small floating preview shows the result.

With **Depth effect** on, people are cut out of the photo and drawn over the clock. If they would cover too much of the time, the clock stays on top for that photo.

Bundled fonts (Anton, Bebas Neue, Righteous, DM Serif Display, Abril Fatface, Pacifico) are from Google Fonts under the SIL Open Font License.

The lock screen option sets the same photo there without the Framily clock, since the phone shows its own.

## Roadmap

- **Done:** face recognition (see above).
- **Later:** separate lock/home photos, "on this day" mode, adjustable score weights.

## Building it yourself

Open the folder in Android Studio and press Run, or `./gradlew assembleDebug`.
