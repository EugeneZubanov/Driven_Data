# Quiet Night – snore tracker for Android

Records overnight through the phone's microphone, detects snoring on the device, and shows
each night as a timeline with a snore score, plus trends over days, weeks and months.

Trying it on a phone? Follow [TESTING.md](TESTING.md), a one-page checklist.

- **Record** – Start, a 5-minute test run, and a *Tonight* card: fall-asleep delay, wake-up alarm,
  factors and remedies, optional full-night audio and a microphone check. While recording the
  screen dims to near-black after 30 s (tap to wake) and Stop needs a 1.5 s hold.
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

## Recording reliability

A night runs in a foreground service with a notification (elapsed time, Stop and save) and a
partial wake lock, and is saved every 2 minutes.

- **Before starting**: the app warns if the phone is not charging or storage is low, and shows a
  card when Android may stop it to save battery, with a button to the setting and phone-specific
  steps (Samsung, Xiaomi, Huawei/Honor, OnePlus/Oppo, Pixel).
- **If the app is closed in the night** (by the phone, a crash, or the microphone permission
  being taken away) the next launch keeps the part saved so far, marks it as cut short and says
  so on the Record tab. Android 14+ does not let the app restart the microphone from the
  background, so the night cannot resume by itself.
- **Calls and other apps**: when the microphone is taken or muted, recording pauses and tries
  again, and that stretch is kept as a gap without sound; the timeline stays on the real clock.
  Audio lost to a stall is added back as quiet, so the night never shrinks.
- **Microphone rate**: 16 kHz if the phone allows it, otherwise 48 or 44.1 kHz.
- **Safety stops**: the night is stopped and saved at 5% battery when unplugged, and after 12 hours.
- **Wake-up alarm**: at the set time the night stops, saves and an alarm rings until you act
  (snooze 5 or 10 minutes, or turn off). The time can be changed or turned off during the night.
  It needs no exact-alarm permission because the running recording watches the clock, so it
  only rings if the app is still running; keep your usual alarm as well. Snooze uses
  `setAlarmClock` (falls back to an inexact alarm if exact alarms are not allowed).
- **Full-night audio** (off by default): AAC at 16 kHz mono, about 11 MB an hour, in 10-minute
  files that are each finished before the next starts, listed in `night-audio.json`. It turns
  itself off with a note if storage runs low or the phone's encoder fails; the night carries on.
- **Screen and battery**: the screen may turn off after 10 minutes in the dimmed view; the live
  meter updates less often when dimmed or off. Expect very roughly 1.5–4% battery an hour unplugged
  (an estimate, not measured).
- Times follow the phone's 12/24-hour setting.

What the app cannot do: see the end of [TESTING.md](TESTING.md).

## Trends: how the numbers are worked out

- Trends, the calendar, the report, the CSV and the widget leave out test runs and nights with under
  an hour actually listened to. They use corrected numbers: stretches you marked as not snoring and
  stretches with no audio are not counted as snoring.

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

- **Backup** writes a zip (`nights.json`, `clips/<night>/<clip>.wav`, and for nights that kept
  whole-night audio `clips/<night>/night-audio.json` plus its `audio-NNN.m4a` segments) wherever you
  choose via the system file picker; no storage permission is needed. Sample nights are left out.
  Whole-night audio makes backups large (about 11 MB per hour).
- **Restore** adds nights from such a zip and keeps any night already on the phone (matched by id).
  It ignores unexpected paths and checks every night. Limits: 16 MB per clip, 64 MB per audio
  segment, 512 MB per night, 8 GB per backup, 20,000 files and 5,000 nights; a segment list only
  keeps plainly named `.m4a` files that are in the backup and inside the night. A damaged or
  oversized file is refused before anything is saved. It keeps running, and its result stays shown, if you leave Settings.
- Backups also carry each night's detector details (`detector.json`, 1 MB cap; dropped on restore
  if unreadable). Before restoring, the app checks there is about twice the backup's size free and
  says so plainly if not.
- **Detection check** shows a sensitivity suggestion when your recent nights point to one (with the
  reason; it is never applied without you pressing "Use …"), and last night's detector details,
  which you can share as plain text with the phone model, Android and app version and capture rate.
- **Audio storage** shows how much space clips and whole-night audio use, and can delete the audio
  of nights older than 30 or 90 days, at once and each time the app starts. The nights stay.
- **Export CSV** writes one row per night: date, start, end, duration, minutes mild/loud/heavy,
  score, tags, and weight_kg when a weight was logged for that date.
- The weight log (a separate small file in app storage) is included in the backup; on restore,
  days already logged on the phone are kept. Older backups without it restore fine.

## Report and widget

- **30-night report** (Trends, or Settings → Your data): a one-page PDF on a white background for printing or
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

Levels are measured against the night's quietest minute: the lowest noise floor that held for a
whole minute so far. Snoring 18 dB or more above it is loud, 28 dB or more heavy, anything less
mild; a sound must first rise 11, 8 or 5.5 dB above the current floor (low, normal, high
sensitivity) to count at all. These numbers live in one place, `Level` in `data/Session.kt`.
Because the reference is the quietest minute, snoring stays loud after a fan comes on; with a fan
running all night there is nothing quieter to compare with, and the same snore reads milder than
in a silent room. The phone's microphone is not calibrated, so there is no absolute level. The
score is the share of the listened night spent snoring, weighted mild 0.4×, loud 1×, heavy 2×.
It is a heuristic, not a medical measurement.

The unit tests run the detector over a synthetic corpus of snoring and night-time noises at all
three rates and print a hit-rate table (`app/build/reports/detector-corpus.txt`), plus a simulated
8-hour night. That catches regressions; it does not prove accuracy on real recordings. Known weak
spots: creaky talking can still pass as snoring now and then, two people snoring together are
merged, very quiet snoring on low-gain phones is missed at low sensitivity, and phones with heavy
noise suppression may report levels too high.

To check the detector against real recordings (for example clips someone shares), put 16-bit PCM
`.wav` files in a folder and run:

    ./gradlew :app:testDebugUnitTest --tests '*WavReplayTest*' -Pwavs=/path/to/folder

(or set `QN_WAVS=/path/to/folder`). It prints, and writes to `app/build/reports/wav-replay.txt`,
the snores found in each file with their levels, the 10 s timeline and the detector's diagnostics.
Each recorded night also keeps those diagnostics in `detector.json` in its clip folder.
