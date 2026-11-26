# Quiet Night – snore tracker for Android

Quiet Night records overnight through the phone's microphone, detects snoring on the phone, and
shows each night as a timeline with a snore score, plus trends over days, weeks and months.
No account, no ads, no internet: everything stays on the phone unless you export it yourself.

## Install

Builds are signed with `keystore/sideload.jks` (password `android`). It is a non-secret key kept in
the repository so that every build can update an installed copy without losing data. Install
`app/build/outputs/apk/release/app-release.apk` by sideloading (see Build below). Android 8.0
(API 26) or newer.

## Your first night

A short three-step introduction is shown on first launch (skippable, and available again from
Settings): put the phone near your head in the same spot each night, keep it plugged in, and what
the score means. Before a real night, try the 5-minute test run on the Record tab. The checklist in
[TESTING.md](TESTING.md) covers what to try on a phone and what to send back if something fails.

## The four tabs

**Record** – start and stop (hold to stop), a fall-asleep delay before listening starts, factors
and remedies to tag the night with, a microphone check, an optional wake-up time, optional
whole-night audio, and phone-specific steps to keep the recording running. A test run is kept
apart from real nights.

**Results** – nights by month. Each night has a loudness timeline, time snoring by level (mild,
loud, heavy), the score, up to 15 ten-second clips of the loudest snoring and, if it was kept, the
whole night's audio. You can mark a stretch as "not snoring", add tags or a note afterwards, search
notes, compare two nights, delete a night (with undo) or delete just its audio.

**Trends** – the average score per day, week or month (periods without a recording stay visible),
a month calendar shaded by score (tap a day to open it), when in the night you snore, patterns
(7-night average and its direction, run of quiet nights, quietest and loudest night, night of the
week), nights with and without each factor or remedy, an optional weight log, and a 30-night report.

**Settings** – detection sensitivity and a detection check, audio clips on or off, your data
(backup, restore, CSV, report), audio storage, a bedtime reminder, the weight log, sample nights,
how the score works, the introduction again, and delete everything. The app version is at the bottom.

A home-screen widget shows last night's score and time snoring; tapping it opens the app. It
refreshes when a night is saved and once a day.

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

## How detection works

`SnoreDetector` works on 50 ms frames at whatever rate the microphone gives (16, 44.1 or 48 kHz):

- **Loudness** above the room's noise floor: the quietest frame of the last ~10 s, so a fan
  switching on is absorbed within seconds.
- **Pitch**: the share of energy between 70 and 900 Hz.
- **Flutter**: how much the low band pulses at 15–70 Hz, as many snores do; voices and music don't.

Loud, low-pitched bursts 0.35–4 s long become candidates. They count as snoring once three of them
repeat at a steady breathing interval (2–10 s apart); after that, each burst on the rhythm counts
straight away. Bursts chopped up like syllables are treated as talking, and perfectly regular
repeats (a ringer, an alarm) as a machine. Loud sounds that are not snoring can be shown separately
as "talking" or "other".

Levels are measured against the night's quietest minute: the lowest noise floor that held for a
whole minute so far. Snoring 18 dB or more above it is loud, 28 dB or more heavy, anything less
mild. A sound must first rise 11, 8 or 5.5 dB above the current floor (low, normal or high
sensitivity) to count at all. These numbers live in one place, `Level` in `data/Session.kt`.

**The score** is the share of the listened night spent snoring, weighted mild 0.4×, loud 1×,
heavy 2×. 0 means no snoring; lower is quieter. Stretches you mark as not snoring, and stretches
with no audio, are not counted.

**Honest limits.** This is a heuristic from sound, not a medical measurement. The microphone is not
calibrated, so there is no absolute level. Because the reference is the quietest minute, snoring
stays loud after a fan comes on, but with a fan running all night the same snore reads milder than
in a silent room. Known weak spots:

- creaky talking can still pass as snoring now and then
- two people snoring together are merged
- very quiet snoring on low-gain phones is missed at low sensitivity
- phones with heavy noise suppression may report levels too high

Each recorded night keeps the detector's diagnostics in `detector.json` in its clip folder (when
clips are on). Settings → Detection check shows them for last night and can share them as plain
text, with the phone model, Android and app version, and capture rate. If recent nights suggest a
different sensitivity, the card says why; the setting only changes when you press "Use …".

## How Trends counts

- Trends, the calendar, the report, the CSV and the widget leave out sample nights, test runs and
  nights with under an hour actually listened to.
- A night belongs to the day it ended on. "Night of the week" uses the evening it started, so a
  recording begun at 00:30 on Saturday counts as Friday night. Weeks start on the locale's first day.
- *Nights with and without* compares a tag only once there are at least 5 nights with it and 5
  without. The bar is a rough 95% range for the difference (normal approximation). If the range
  crosses zero the app says "no clear difference". It shows what went together, not what caused what.
- *When you snore* is the share of listened time in each clock hour spent snoring, over the last 30
  nights. Hours recorded on few nights are faded.
- Weight and the 7-night average score are two separate charts on the same dates, never one chart
  with two axes.
- Times follow the phone's 12/24-hour setting.

## Your data and privacy

- **Stays on the phone.**
  - The app has no internet permission; the merged release manifest was checked.
  - Android's cloud backup and phone-to-phone transfer are turned off for this app:
    `android:allowBackup="false"`, plus `data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` (older) that exclude everything.
  - Recordings, clips, nights, weights and settings move only when you use Back up, Export or Share yourself.
- **Back up** writes one zip wherever you choose with the system file picker; no storage permission
  is needed. It contains:
  - `nights.json`: the nights and the weight log
  - `clips/<night>/` folders with the clips, any whole-night audio (`night-audio.json` and
    `audio-NNN.m4a`) and `detector.json`

  Sample nights are left out. Whole-night audio makes backups large: about 7 MB per hour.
- **Restore** adds the nights that are not already on the phone (matched by id), and weight days
  not already logged. It runs in the background, and its result stays shown if you leave Settings.
  - **Checks before anything is saved:** about twice the backup's size must be free, every night
    is validated, and unexpected paths are ignored. A damaged or oversized file is refused.
  - **Limits:**
    - 16 MB per clip, 64 MB per audio segment, 1 MB per small JSON file
    - 512 MB per night, 8 GB per backup
    - 20,000 files and 5,000 nights
  - **Audio segments:** only plainly named `.m4a` files that are in the backup and fall inside the night are kept.
- **Export CSV**: one row per night, with:
  - date, start, end, duration
  - minutes mild, loud and heavy
  - score, tags
  - `weight_kg` when a weight was logged that day
- **30-night report** (Trends or Settings): a one-page printable PDF. It has the date range, nights
  recorded, average score, the nightly chart, time snoring by level, the with/without table and
  weight if logged. It says it is a sound-based estimate, not a diagnosis. It is saved where you
  choose and can then be shared.
- **Audio storage** shows the space used by clips and whole-night audio. It can delete the audio of
  nights older than 30 or 90 days, at once and each time the app starts; the nights stay.
- **Bedtime reminder**: an optional daily notification at a time you choose. Tapping it opens the
  Record tab.
  - It uses an inexact alarm, so it may come a few minutes late.
  - It is re-armed after a reboot, an app update, or a clock or time-zone change.
  - On Android 13+ turning it on asks for the notification permission.
- If a backup, report or CSV fails partway, the half-written file is deleted.

## Build and test

Needs JDK 17+ and the Android SDK (platform 35).

    ./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
    ./gradlew :app:testDebugUnitTest    # unit tests, detector corpus and screen renders
    ./gradlew :app:lintRelease

Add `-Proborazzi.test.record=true` to the test command to write screen PNGs (and the PDF report
render) to `app/build/shots`.

The unit tests run the detector over a synthetic corpus of snoring and night-time noises at all
three rates and print a hit-rate table (`app/build/reports/detector-corpus.txt`), plus a simulated
8-hour night. That catches regressions; it does not prove accuracy on real recordings. To check the
detector against real recordings, put 16-bit PCM `.wav` files in a folder and run:

    ./gradlew :app:testDebugUnitTest --tests '*WavReplayTest*' -Pwavs=/path/to/folder

(or set `QN_WAVS=/path/to/folder`). It prints, and writes to `app/build/reports/wav-replay.txt`,
the snores found in each file with their levels, the 10 s timeline and the detector's diagnostics.

## Project layout

    app/src/main/java/com/quietnight/app/
      MainActivity.kt, QuietNightApp.kt   app root, tabs, start-up work
      Reminder.kt, LastNightWidget.kt     bedtime reminder, home-screen widget
      audio/   microphone, recording service, SnoreDetector, whole-night audio, diagnostics
      data/    Session and its storage, settings, trends and insights, backup/CSV, report, weight log
      ui/      one file per screen or card, plus Theme.kt (colours) and Components.kt
    app/src/test/java/com/quietnight/app/
      unit tests, ScreenshotTest (renders), corpus/ (detector corpus and WAV replay)
    TESTING.md   on-phone checklist
