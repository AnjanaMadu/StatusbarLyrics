# Now Lyrics

Now Lyrics displays synchronized lyrics for the track playing on Android. Follow along in the app or, on a rooted device with Vector, show the current line in the status bar.

[Download the APK](../../releases)

![Now Lyrics showing synchronized lyrics in the Android status bar](docs/status-bar-demo.webp)

## Features

- Reads the current track and playback position from Android media sessions. Music apps such as Spotify, YouTube Music, and Apple Music can work when they expose this information.
- Finds timed lyrics through LRCLIB and caches results locally for repeat plays.
- Shows the current lyric in the native status-bar clock on supported ROMs. Long lines scroll once, and the clock returns when playback pauses or no timed line is available.
- Includes a **Bar** toggle and a diagnostics panel that shows the active media session, lookup result, and playback timing.

## Getting started

1. Install the signed APK from [GitHub Releases](../../releases) on Android 8.0 or newer.
2. Open Now Lyrics and grant notification access so it can read the active media session.
3. Play a track in your music app and open Now Lyrics.

### Enable status-bar lyrics

1. On a rooted device, enable **Now Lyrics** in Vector with modern Xposed API 101 support.
2. Set the module scope to **System UI** (`com.android.systemui`).
3. Restart SystemUI or reboot, then turn on **Bar** in Now Lyrics.

| SystemUI | Status |
| --- | --- |
| LineageOS-based ROMs | The existing v2 hook works on tested builds. |
| Samsung One UI | The Samsung clock hook was confirmed on the experimental branch. The integrated v2 build and width adjustment still need Galaxy validation. |
| Pixel / AOSP-style ROMs | The legacy status-bar hook was tested on a rooted Pixel 5 running Android 17 with Vector 2.2; the modern API migration needs device validation. |

Compatibility depends on the ROM's SystemUI layout. If lyrics appear in the app but the status bar still shows the clock, check **Bar**, confirm the Vector scope, and restart SystemUI. When reporting an issue, include your device, ROM version, and any SystemUI log entries tagged `NowLyrics`.

## Build from source

Use JDK 17, Android SDK Platform 36, and Android SDK Build Tools 36.0.0. From the repository root, run the Gradle wrapper.

```sh
./gradlew :app:assembleDebug :app:assembleRelease
```

On Windows, use `gradlew.bat` with the same tasks. Gradle writes APKs to `app/build/outputs/apk/`. The local release APK is unsigned.

### Publish a release

GitHub Actions builds the app for pushes and pull requests targeting `main`. To publish a release, update `versionCode` and `versionName` in `app/build.gradle`, push the commit, and push a tag that matches `versionName` (for example, `v2.1` for version `2.1`). The [Android workflow](.github/workflows/android.yml) uses the `ANDROID_KEYSTORE_BASE64` repository secret to sign the APK, verifies the signature, and publishes the APK to GitHub Releases.
