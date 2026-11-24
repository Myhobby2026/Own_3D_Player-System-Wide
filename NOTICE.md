# Third-party notices

## RootlessJamesDSP (system-wide session discovery/muting approach)

The rootless system-wide audio approach (session discovery via DUMP, session muting,
and MediaProjection playback-capture pipeline) is inspired by and adapted from:

- Project: RootlessJamesDSP
- Author: timschneeb
- Source: https://github.com/timschneeb/RootlessJamesDSP
- License: GPL-3.0

Files influenced by this approach include:
- `app/src/main/java/com/neonplayer/systemwide/systemwide/AudioSessionDumpParser.kt`
- `app/src/main/java/com/neonplayer/systemwide/systemwide/ServiceDumpReader.kt`
- `app/src/main/java/com/neonplayer/systemwide/systemwide/RootlessSessionMuter.kt`
- `app/src/main/java/com/neonplayer/systemwide/systemwide/SystemWideEffectService.kt`

If you distribute a combined binary, review GPL-3.0 compatibility obligations.
This notice is not legal advice.
