# Playback startup measurements

Measured on 2026-10-31 using the connected OPD2403 tablet and the existing network connection, without artificial network throttling.

## Final build

- Branch: `feature/fast-playback-start`
- Installed package: `com.lastwave.app.codextest`
- APK SHA-256: `9ace2c4b411fa2e601ad9ead2c060e57eea542f1ca8dc083ebcfbe343f2ac7b4`
- The APK pulled back from the tablet matched the built APK before testing.
- Installation used an update; application data and the production installation were retained.

Some earlier measurements used an installed APK that did not match the current build. Those estimates are not a controlled before/after baseline. Computer and tablet wall clocks also differed by about 0.57 seconds, so the results below use the app's monotonic clock exclusively.

## Results

`LastWaveStartup` measures from `TAP_TRACK` to the player becoming `READY` with `isPlaying=true`. It includes stream resolution and initial buffering. It does not measure app launch time or speaker hardware latency.

| Home selection | Stream URL cache hit | Extraction | Request to playing |
| --- | --- | --- | --- |
| Waqt (Soul Version) | false | 734 ms | **1,241 ms** |
| bargad | false | 1,084 ms | **1,539 ms** |
| Don't Let Go | true | skipped | **638 ms** |

Waqt was the first playback after a process restart. Player/cipher prewarming had completed while Home was available; no track stream extraction ran before the tap. Both uncached rows logged `extracted=true` and fetched a new stream URL. These are a small device sample, not a network-independent latency guarantee.

The final uncached runs decoded all six offered audio formats in 118–125 ms. The verified older JavaScript solver took about 1.6 seconds to decode only two formats on the same tablet. The old race also held a usable winner for another 2.3 seconds while a losing synchronous NewPipe request finished cancellation.

Quality was retained: Waqt started with 48 kHz Opus and then published a 16-bit/44.1 kHz lossless upgrade. bargad upgraded to 24-bit/96 kHz. The player quality publication followed the active stream.

## Implementation

- Upgrade InnerTubeX from 0.4.1 to 0.11.2, which uses the compiled Kotlin cipher solver.
- Share the cipher service with the config parser and precompile the exact player used for playback. Wait for account loading before warming account-bound config. Reuse that config for three hours within the process.
- Warm token and cipher resources concurrently. Home does not eagerly resolve multiple Quick Picks streams during startup.
- Let the player's media request validate the first direct URL instead of making a separate CDN probe first. A rejected first URL receives one retry with format probes before normal client cooldown applies. Retry state is isolated by video/account/client and bounded in size and lifetime.
- Return the winning extraction without joining cancellation of a blocking loser. Cancel both workers on exit. For signed-in playback, start the fallback after 1.5 seconds, or immediately when the primary returns no stream. Anonymous playback retains an immediate fallback race.

The existing lossless preference and automatic upgrade path remain in use. A fallback that has already entered synchronous network I/O can take time to notice cancellation, but it no longer holds the successful playback result.

## Validation

The debug APK build succeeded. All 24 focused tests passed:

- `PlaybackCandidateRaceTest`: 10 tests, including non-cancellable loser cleanup, fallback timing, primary misses, and caller cancellation.
- `PlaybackUrlValidationPolicyTest`: 6 tests covering the validated retry, isolation, and expiry.
- `PlaybackCipherContextTest`: 4 tests.
- `PlaybackCipherInputTest`: 4 tests.

The unrestricted unit-test task is currently blocked by existing compilation errors in `AudioQualityMixBadgeTest` (`transitionStyle`) and `SettingsClearAllDataTest` (`autoMixPreferences`). The focused run used a temporary external Gradle init script to select only the four test source files above; it did not edit those unrelated tests or the repository's test configuration.

For another on-device measurement, capture `LastWaveStartup`, `LastWaveStream`, `InnerTubeXStreamExtractor`, and `MusicPlayer` continuously. Choose a Home song, verify `cacheHit=false` in its `[STARTUP]` line, and use `totalMs`. A cached run is useful separately but must not be counted as an uncached start.
