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

`SnoreDetector` looks at 50 ms frames: loudness above the room's noise floor (the quietest frame
of the last ~10 s, so a fan switching on is absorbed within seconds), the share of energy below
~900 Hz, and how much the low band flutters at 15–70 Hz. Loud, low-pitched bursts 0.35–4 s long
count once three of them repeat at a steady breathing interval (2–10 s); bursts chopped up like
syllables are ignored unless they flutter like a snore. The score is the share of the night spent
snoring, weighted mild 0.4×, loud 1×, heavy 2×. It is a heuristic, not a medical measurement.

The unit tests run it over a synthetic corpus of snoring and night-time noises and print a hit-rate
table (`app/build/reports/detector-corpus.txt`). That catches regressions; it does not prove
accuracy on real recordings.
