# Glasses AV Capture (Ray-Ban Meta Gen 2, Android)

Stage-1 capture foundation for the ADA University project *"English-Azerbaijani Translation of the
Target Speaker's Speech with Ray-Ban Meta (Gen 2) Glasses"*.

The app shows **live glasses video** and **live audio** in an Android app. On screen it shows
**where the audio physically comes from**, along with the evidence for that, and it keeps the
timestamps that later audiovisual (face/lip) processing will need.

> Status: **compiled successfully** (Gradle `assembleDebug`, SDK 1.1.0). It has **not yet been run
> on real glasses**. Every audio-source claim below must be confirmed with the hardware checklist
> in section 5.

---

## 1. What the SDK offers (verified 2026-11-24 against the official repo, tag "Release 1.1.0")

| Question | Answer | Source |
|---|---|---|
| Ray-Ban Meta Gen 2 supported? | Yes ("Ray-Ban Meta glasses (Gen 1 and Gen 2)") | wearables.developer.meta.com/docs/getting-started-toolkit |
| Versions for SDK 1.1.0 | Meta AI app **V292**, glasses firmware **V130**. SDK 1.0.1 needs firmware V128 | docs/develop/dat/version-dependencies |
| Android | Android 10+ per the docs. This app needs **Android 12+** (minSdk 31, same as Meta's sample) | getting-started, sample `app/build.gradle.kts` |
| Video | `Stream.videoStream`: I420 frames (or HEVC), 720x1280 / 504x896 / 360x640, 2/7/15/24/30 fps. Resolution and fps drop automatically when Bluetooth bandwidth is low (never below 15 fps) | docs/develop/dat/streaming |
| **Glasses mic through the SDK?** | **Yes, experimental, since SDK 1.0.0.** Set `StreamConfiguration.audioCodec = AudioCodec.PCM(...)`, then collect `Stream.audioStream` (`AudioFrame(buffer, presentationTimeUs)`). Rates: 16000/44100/48000 Hz. Needs `Permission.MICROPHONE` in the Meta AI app | CHANGELOG 1.0.0 "Camera audio streaming"; repo `plugins/mwdat-android/skills/audio-streaming/SKILL.md` |
| Same API as video? | Same `Stream` object, **separate Flow**. Audio shares the stream's state and errors | same skill file |
| Publishing | Experimental features "cannot be published yet"; fine for development and beta | CHANGELOG header, skill file |
| Second route | Standard Bluetooth **HFP/SCO**. Meta's own 1.x sample records the glasses mic this way, alongside the camera stream | sample `stream/AudioInputHandler.kt` |
| Camera and mic at the same time | SDK audio is by design inside the camera stream. HFP plus stream is used by Meta's sample, which notes that wideband SCO "eats camera-stream airtime" | same |

**Not documented, so it must be tested:** which of the glasses' microphones (or which beamformed
mix) feeds each route; whether HFP processing favours the wearer's voice; the clock origin of
`presentationTimeUs`; how far audio and video PTS are offset.

From inspecting the SDK binary (a hypothesis, but a strong one): the glasses send **AAC**, and the
SDK decodes it on the phone with `MediaCodec` (log tag `DAT:STREAM:AudioDecoder`). The AAC
compression adds latency and some coding loss, and the PCM is most likely 16-bit. The app measures
this ("bytes per sample", "measured rate").

## 2. Fixed capture settings and playback

The app has no settings screen. Every capture uses the same fixed configuration (defaults in
`UiState`, `capture/CaptureViewModel.kt`):

| Setting | Value |
|---|---|
| Audio | **Glasses mic via the DAT SDK stream** (`Stream.audioStream`), 16 kHz mono. Shown in green as "Glasses microphone (DAT SDK audio stream, experimental)" |
| Video | `VideoQuality.MEDIUM` (504x896) at 24 fps; the SDK may lower this under Bluetooth load |
| Saving to files | Only when you tap **Save** after a capture (see below) |

The Bluetooth HFP route and the labelled phone-mic fallback are still in the code
(`audio/AudioRecordCapture.kt`, `AudioMode` in `CaptureViewModel.kt`) but cannot be selected from the
app. Every `AudioChunk` carries an `AudioOrigin`, so phone audio can never be passed off as glasses
audio further down the pipeline.

**Listening:** after **Stop**, tap **Play last capture on glasses**. The audio plays through the
**glasses' speakers** (their Bluetooth media output), and the screen shows "Playing on: <device>",
which is the device Android actually routed to. If the glasses' output isn't available, or Android
routes elsewhere, playback stops with a red error; it never falls back to the phone speaker. Up to
the last 10 minutes are kept **in memory only** and replaced on the next Start.

**Saving (SR2):** while capturing, the app writes the session to a temporary folder. After **Stop**,
tap **Save** to keep it. Without Save, it is deleted when the next capture starts or the app
closes (SR9). A saved session is in
`/sdcard/Android/data/az.ada.glassesav/files/sessions/<yyyyMMdd_HHmmss_SSS>/`:

| File | Contents |
|---|---|
| `video.mp4` | H.264 video from the glasses camera (no audio track) |
| `audio.wav` | Glasses microphone audio exactly as received, 16-bit mono 16 kHz |
| `video_frames.csv` | every frame: `seq, arrival_elapsed_ns, pts_us, width, height, bytes` |
| `audio_chunks.csv` | every audio chunk: `seq, origin, arrival_elapsed_ns, pts_us, samples, sample_rate, channels, rms_dbfs` |
| `events.log` | state changes, errors, **Marker** presses, and a summary line (frames encoded / skipped) |

`video.mp4` timestamps are `pts_us` minus the first frame's `pts_us`. To align audio and video, use
`arrival_elapsed_ns` in the two CSVs (one phone clock for both). If the SDK lowers the resolution
during a capture, frames at the new size are skipped in the MP4 but still listed in the CSV.
Copy sessions to the laptop:
```powershell
adb pull /sdcard/Android/data/az.ada.glassesav/files/sessions .
```

## 3. Windows setup, build, install

1. **Android Studio** (any recent version). **Gradle JDK must be 17 or 21.** Gradle 8.14.1 cannot
   run on JDK 25, and the build then fails with the bare message `25.0.4`.
   *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* → choose
   `jbr-21` (download one from that menu if it isn't listed).
2. Unzip `GlassesAVCapture.zip`, then **File → Open** the `GlassesAVCapture` folder. Studio
   creates `local.properties` (SDK path) itself.
3. Phone: Android 12+, **Developer options → USB debugging** on, connect by USB, accept the RSA prompt.
4. Run ▶ (`app`). From a terminal instead:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

### Glasses and Meta AI configuration 
1. Update the Meta AI app to **V292+** and the glasses firmware to **V130+** (Meta AI → glasses →
   General → About → Version). After the v128 update, Meta's known-issues page says to reboot the glasses.
2. Turn on **Developer Mode**: Meta AI → Settings → App Info → tap the app version **5 times** →
   toggle Developer Mode → Enable. With Developer Mode, `APPLICATION_ID`/`CLIENT_TOKEN` stay `0`
   (the build default).
3. Without Developer Mode (beta testers), register the app in the Wearables Developer Center,
   **enable the Camera and Audio Streaming capabilities**, and put the credentials in
   `local.properties` (never commit it):
   ```
   mwdat_application_id=YOUR_ID
   mwdat_client_token=YOUR_TOKEN
   ```
4. In the app: **Register** (opens Meta AI, approve, come back), then **Start**. On first start
   Meta AI asks for **Camera** and **Microphone**.
5. For playback on the glasses: in Android Bluetooth settings, **"Media audio"** must be turned on
   for the glasses.

Lifecycle: capture runs only while the app is visible. Home, screen-off, glasses folded or
disconnected all stop it cleanly. Errors appear in red and in the event log.

## 4. Main files

```
app/src/main/java/az/ada/glassesav/
  MainActivity.kt                  Android + Meta AI permission dialogs, lifecycle stop
  capture/GlassesStreamController  DeviceSession -> Camera -> Stream; video + SDK audio flows
  capture/CaptureViewModel.kt      modes, state machine, source verdict, statistics
  audio/AudioRecordCapture.kt      HFP route and phone-mic fallback (kept, not selectable in the UI)
  audio/AudioRouteDiagnostics.kt   what Android reports about routing (Logcat tag AudioRoute)
  audio/AudioStats.kt              level meter, measured sample rate
  audio/ReplayBuffer.kt            in-memory copy of the last capture, played on the glasses
  pipeline/MediaFrames.kt          GlassesVideoFrame, AudioChunk, AudioOrigin, processor interfaces
  pipeline/MediaHub.kt             fan-out to processors (video: latest-wins, audio: lossless FIFO)
  pipeline/I420.kt                 YUV->Bitmap, luma plane for detectors
  pipeline/SessionRecorder.kt      writes a session: MP4 + WAV + timestamp CSVs + events.log
  pipeline/Mp4VideoWriter.kt       I420 frames -> H.264 MP4 (Android MediaCodec)
  ui/CaptureScreen.kt              Compose UI
```

**Plug-in point:** implement `VideoFrameProcessor` (face detection → face-centre baseline, Eq. 1;
lip ROI tracks for ASD) and `AudioChunkProcessor` (VAD, ASD audio branch, TSE input buffer). Then
register them in `CaptureViewModel.init`:
```kotlin
hub.addVideoProcessor(myFaceDetector)
hub.addAudioProcessor(myAsdAudioBuffer)
```
`I420.lumaPlane(frame)` gives a grayscale image straight away. Use `arrivalElapsedNs` (one phone
clock for both streams) to align audio and video, and `presentationTimeUs` to check spacing
within a stream.


### Logcat to collect if something fails
```powershell
adb logcat -v time -s GlassesAV:* AudioRoute:* AudioRecordCapture:* GlassesStream:* MediaHub:* "DAT:STREAM:AudioDecoder:*" ReplayBuffer:* Mp4VideoWriter:* AudioRecord:* AudioService:* BluetoothHeadset:*
```
Send: that output, the on-screen **Audio route report** (tap Refresh while capturing), the app
version strings (Meta AI, glasses firmware, SDK 1.1.0), and the phone model and Android version.

Common failures: `Stream error: PERMISSIONS_DENIED` (grant Camera/Microphone in Meta AI → App
connections), `createSession failed: NOT_REGISTERED` (tap Register), no SDK audio frames (check
Developer Mode and the Audio Streaming capability, then share the `DAT:STREAM:AudioDecoder` log).

## 5. Sources
* github.com/meta/meta-wearables-dat-android: `CHANGELOG.md`, `samples/CameraAccess`,
  `plugins/mwdat-android/skills/{audio-streaming,camera-streaming,session-lifecycle}/SKILL.md`
* wearables.developer.meta.com/docs: getting-started-toolkit, develop/dat/streaming,
  develop/dat/version-dependencies, develop/dat/knownissues, build-integration-android
* Android: AudioManager.setCommunicationDevice, AudioRecord.getRoutedDevice,
  AudioManager.getActiveRecordingConfigurations, AudioTimestamp (developer.android.com)
