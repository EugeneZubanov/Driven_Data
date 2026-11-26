# Quiet Night – snore tracker for Android

Records overnight through the phone's microphone, detects snoring on the device, and shows
each night as a timeline with a snore score, plus trends over days, weeks and months.

- **Record** – start/stop, fall-asleep delay, factors and remedies to tag the night with.
- **Results** – nights by month; each night has a loudness timeline, time snoring by level
  (mild / loud / heavy), score, and up to 15 ten-second clips of the loudest snoring.
- **Trends** – average score per day/week/month and how each factor or remedy compares.
- **Settings** – detection sensitivity, audio clips on/off, sample nights, delete data.

Everything stays on the phone; the app has no network permission.

## Build

Needs JDK 17+ and the Android SDK (platform 35).

    ./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
    ./gradlew :app:testDebugUnitTest    # detector tests + screen renders

Add `-Proborazzi.test.record=true` to the test command to write screen PNGs to `app/build/shots`.

Builds are signed with `keystore/sideload.jks` (password `android`), a non-secret key kept in
the repo so that every build can update an installed copy without losing data.

## How detection works

`SnoreDetector` looks at 50 ms frames: loudness above an adaptive room-noise floor, and the
share of energy below ~900 Hz. Loud, low-pitched bursts 0.25–4 s long that repeat every 2–10 s
(a breathing rhythm) count as snores. The score is the share of the night spent snoring,
weighted mild 0.4×, loud 1×, heavy 2×. It is a heuristic, not a medical measurement.
