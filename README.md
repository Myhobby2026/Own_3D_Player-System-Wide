# Own 3D Player - System-Wide (Android)

Neon-styled Android 3D audio player with **system-wide** DSP effects, inspired by the same rootless approach used in [Myhobby2026/Neon3D_System-Wide_Player](https://github.com/Myhobby2026/Neon3D_System-Wide_Player).

## Features

- **Neon GUI** - dark neon cyan/magenta theme with glowing rounded controls.
- **In-app 3D Player** - open and play local audio files.
- **5-band Equalizer** - 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz.
- **Virtual 3D** - spatial widening effect with a **rounded MIX level control** to adjust desired effect intensity.
- **Surround 7.1** - virtual surround effect with a **rounded MIX level control** to adjust desired effect intensity.
- **Bass Boost** - low-shelf boost with rounded level control.
- **Treble Boost** - high-shelf boost with rounded level control.
- **PAN** - left/right balance control.
- **System-wide effects** - applies DSP to other apps' audio using the same rootless capture approach.

## System-Wide Approach

The system-wide mode follows the same architecture as the reference project:

1. **Audio playback capture** via `MediaProjection` (`AudioPlaybackCaptureConfiguration`).
2. **Session discovery** via ADB-granted `android.permission.DUMP` reading `audio`, `media.audio_flinger`, and `media.audio_policy` dumps.
3. **Original session muting** via DynamicsProcessing/Volume audio effects to prevent double audio.
4. **DSP processing** of captured PCM16 stereo audio through the app's neon DSP engine.
5. **Output** to a non-capturable `AudioTrack` (`ALLOW_CAPTURE_BY_NONE`).

The in-app player uses Android `AudioEffect` APIs (Equalizer/BassBoost/Virtualizer/LoudnessEnhancer). The system-wide service uses the internal PCM `DspEngine` so Virtual 3D and Surround 7.1 mix levels are applied as real wet/dry DSP mixes.

## Requirements

- Android 10 (API 29) or newer for system-wide capture.
- Android 8.0 (API 26) minimum for the app package.
- ADB access for one-time system-wide setup.
- Microphone/capture permission and MediaProjection consent.
- Notification permission on Android 13+ recommended.

## ADB Setup (System-Wide)

Grant the required permissions once:

```sh
adb shell pm grant com.neonplayer.systemwide android.permission.DUMP
adb shell appops set com.neonplayer.systemwide PROJECT_MEDIA allow
adb shell appops set com.neonplayer.systemwide SYSTEM_ALERT_WINDOW allow
```

Then open the app, tap **System-Wide Effects**, grant runtime permissions, and press **Start System-Wide Effects**.

To revoke:

```sh
adb shell pm revoke com.neonplayer.systemwide android.permission.DUMP
adb shell appops set com.neonplayer.systemwide PROJECT_MEDIA deny
```

## Build

This is a standard Gradle Android project.

```sh
./gradlew assembleDebug
```

If `gradle/wrapper/gradle-wrapper.jar` is missing in your environment, generate it with a local Gradle installation:

```sh
gradle wrapper --gradle-version 8.7
```

## Project Structure

```text
app/src/main/java/com/neonplayer/systemwide/
  dsp/                 # Effect settings and PCM DSP engine
  player/              # In-app AudioEffect controller
  systemwide/          # DUMP reader, session muter, capture service, setup UI
  ui/                  # MainActivity and NeonKnobView
```

## Notes / Limitations

- Apps that opt out of playback capture (`ALLOW_CAPTURE_BY_NONE`, DRM/protected content) cannot be processed.
- Only `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` are captured.
- System-wide capture adds latency and is not intended for low-latency monitoring.
- Some devices/OEMs may reject mute effects or expose incompatible dump formats; the service stops safely to avoid double audio.
- Android 14+ requires fresh MediaProjection consent for each start.
- Android 15+ may require developer option **Disable screen share protections** for playback capture to work as expected.

## Attribution

The rootless session-discovery and mute-effect approach is adapted from [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) (GPL-3.0). See [NOTICE.md](NOTICE.md).
