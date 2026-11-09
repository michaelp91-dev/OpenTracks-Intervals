# OpenTracks-Intervals

A privacy-respecting sport tracker with interval workouts and spoken cues. This is a fork of [OpenTracks](https://github.com/OpenTracksApp/OpenTracks), now maintained upstream on [Codeberg](https://codeberg.org/OpenTracksApp/OpenTracks).

Download the latest APK from [Releases](https://github.com/michaelp91-dev/OpenTracks-Intervals/releases).

## Screenshots

<p>
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot1.png" alt="Track list">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot2.png" alt="Recording a track">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot3.png" alt="Track statistics">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot4.png" alt="Track map">
</p>
<p>
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot5.png" alt="Track detail">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot6.png" alt="Charts">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot7.png" alt="Settings">
    <img width="23%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot8.png" alt="More tracking screens">
</p>

## How to use

### Install

1. Get the APK from [Releases](https://github.com/michaelp91-dev/OpenTracks-Intervals/releases/latest).
2. Open it on your phone and install. Android may ask you to allow installs from your browser or files app.

### Record a track

Tap the red record button on the main screen. You still get GPS tracking, voice announcements, markers, photos, and Bluetooth sensors.

### Interval workouts

1. On the track list, open **Workouts**.
2. Pick a built-in plan or tap **Add workout**.
3. Name it, then add segments or rounds. Segment types are warm-up walk, warm-up jog, prepare, run, walk, cool-down jog, and cool-down walk. Each one has its own duration, and you can drag anything (including rounds) to reorder it.
4. For a distance workout, set an optional goal such as `5k`. You can also set a goal finish time and hear whether you are ahead or behind.
5. Save, then start the workout from the list.
6. If a run/walk workout ends with a walk, you can choose **Run the last walk** to join that walk onto the run before it.

### Voice cues

At the start of each segment you hear what to do. During the segment you also get:

- Halfway
- 30 seconds left
- 10 seconds left
- A 5-second countdown
- On segments of 3 minutes or longer, each remaining full minute

In Settings, under **Workout cues**, you can beep or vibrate before each cue.

### Heart rate

Pair a Bluetooth heart-rate strap (including WHOOP) in Android settings, then choose it in the app’s sensor settings.

### After a workout

The recording is saved like any other track. Open it to see workout splits (type, time, distance, and speed, plus totals for all runs and all walks). Export as KMZ, KML, or GPX, including automatic export after recording if you turn that on.

## Build

Use the `intervals` product flavor (`dev.mpuckett.opentracks.intervals`). Open the project in Android Studio, or build with Gradle the same way you would for OpenTracks.

## License

[Apache License 2.0](LICENSE). OpenTracks is copyright its original authors. Interval-workout additions are copyright 2026 Michael Puckett.
