# Quiet Night – snore tracker for Android

Records overnight through the phone's microphone, detects snoring on the device, and shows
each night as a timeline with a snore score, plus trends over days, weeks and months.

- **Record** – start/stop, fall-asleep delay, factors and remedies to tag the night with.
- **Results** – nights by month; each night has a loudness timeline, time snoring by level
  (mild / loud / heavy), score, and up to 15 ten-second clips of the loudest snoring.
- **Trends** – average score per day/week/month (empty periods stay visible), a month calendar
  shaded by score (tap a day to open it), when in the night you snore, patterns (7-night average
  and direction, quiet-night run, quietest/loudest night, by night of the week), nights with and
  without each factor or remedy, and an optional weight log charted next to the snore score.
- **Settings** – detection sensitivity, audio clips on/off, bedtime reminder, weight log, backup /
  restore / CSV export, sample nights, the introduction again, delete data.

A short three-step introduction is shown on first launch (skippable).

Everything stays on the phone; the app has no network permission.

## Build

Needs JDK 17+ and the Android SDK (platform 35).

    ./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
    ./gradlew :app:testDebugUnitTest    # detector tests + screen renders

Add `-Proborazzi.test.record=true` to the test command to write screen PNGs to `app/build/shots`.

Builds are signed with `keystore/sideload.jks` (password `android`), a non-secret key kept in
the repo so that every build can update an installed copy without losing data.

## Trends: how the numbers are worked out

- A night belongs to the day it ended on (calendar, Results); "night of the week" uses the evening
  it started, so a recording begun at 00:30 on Saturday counts as Friday night.
- Weeks start on the phone's locale first day of week.
- *Nights with and without* compares a tag only once there are at least 5 nights with it and 5
  without. The bar is a rough 95% range for the difference (normal approximation); if it crosses
  zero the app says "no clear difference". It shows what went together, not what caused what.
- *When you snore* is the share of recorded time in each clock hour spent snoring, over the last
  30 nights; hours recorded on few nights are faded.
- Weight and the 7-night average score are drawn as two separate charts on the same dates, never
  on a shared or dual axis.

## Your data

- **Backup** writes a zip (`nights.json` plus `clips/<night>/<clip>.wav`) wherever you choose via
  the system file picker; no storage permission is needed. Sample nights are left out.
- **Restore** adds nights from such a zip and keeps any night already on the phone (matched by id).
  It ignores unexpected paths, caps file sizes, checks every night and saves nothing if the file is
  damaged. It keeps running, and its result stays shown, if you leave Settings.
- **Export CSV** writes one row per night: date, start, end, duration, minutes mild/loud/heavy,
  score, tags, and weight_kg when a weight was logged for that date.
- The weight log (a separate small file in app storage) is included in the backup; on restore,
  days already logged on the phone are kept. Older backups without it restore fine.

## Report and widget

- **30-night report** (Settings → Your data): a one-page PDF on a white background for printing or
  sending to someone, saved where you choose and then shareable from the app. It has the date range,
  nights recorded, average score, the nightly score chart, time snoring by level, the
  with/without table (same minimum-nights rule) and weight if logged, and states that it is a
  sound-based estimate, not a diagnosis.
- **Home-screen widget**: last night's score and time snoring; tap to open the app. It refreshes
  when a night is saved and once a day.

## Bedtime reminder

An optional daily notification at a time you choose. It uses an inexact alarm (no exact-alarm
permission), so it may come a few minutes late, and is re-armed after a reboot, an app update or a
clock/time-zone change. On Android 13+ turning it on asks for the notification permission.
Tapping the notification opens the Record tab.

## How detection works

`SnoreDetector` works on 50 ms frames at whatever rate the microphone gives (16, 44.1 or 48 kHz):

- **Loudness** above the room's noise floor: the quietest frame of the last ~10 s, so a fan
  switching on is absorbed within seconds.
- **Pitch**: the share of energy between 70 and 900 Hz.
- **Flutter**: how much the low band pulses at 15–70 Hz, as many snores do; voices and music don't.

Loud, low-pitched bursts 0.35–4 s long become candidates. They count as snoring once three of them
repeat at a steady breathing interval (2–10 s apart); after that, each burst on the rhythm counts
straight away. Bursts chopped up like syllables are treated as talking, and perfectly regular
repeats (a ringer, an alarm) as a machine. Loud sounds that are not snoring can be reported
separately as "talking" or "other".

Levels (mild / loud / heavy: 18 and 28 dB thresholds) are measured against the quietest floor
that held for a full minute that night, so snoring stays loud after a fan comes on. With a fan
running all night there is nothing quieter to compare with, and the same snore reads milder than
in a silent room: the phone's microphone is not calibrated, so there is no absolute level. The
score is the share of the listened night spent snoring, weighted mild 0.4×, loud 1×, heavy 2×.
It is a heuristic, not a medical measurement.

The unit tests run the detector over a synthetic corpus of snoring and night-time noises at all
three rates and print a hit-rate table (`app/build/reports/detector-corpus.txt`), plus a simulated
8-hour night. That catches regressions; it does not prove accuracy on real recordings. Known weak
spots: creaky talking can still pass as snoring now and then, two people snoring together are
merged, very quiet snoring on low-gain phones is missed at low sensitivity, and phones with heavy
noise suppression may report levels too high.
