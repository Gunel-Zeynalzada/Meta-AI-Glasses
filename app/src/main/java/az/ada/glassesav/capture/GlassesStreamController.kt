package az.ada.glassesav.capture

import android.os.SystemClock
import android.util.Log
import az.ada.glassesav.pipeline.AudioChunk
import az.ada.glassesav.pipeline.AudioOrigin
import az.ada.glassesav.pipeline.GlassesVideoFrame
import az.ada.glassesav.pipeline.I420
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioFrame
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns ONE glasses connection: DeviceSession -> Camera -> Stream, following the documented DAT
 * flow (createSession, subscribe, start, wait for STARTED, addCamera, subscribe, stream.start()).
 *
 * Video and SDK audio arrive through the SAME Stream object but on two separate Flows:
 *   stream.videoStream  -> VideoFrame (I420 by default)       -> [onVideo]
 *   stream.audioStream  -> AudioFrame (decoded PCM, 16-bit)   -> [onSdkAudio]
 * Audio only flows when StreamConfiguration.audioCodec is non-null (experimental in SDK 1.x) and
 * the app holds Permission.MICROPHONE in the Meta AI app.
 */
class GlassesStreamController(
    private val scope: CoroutineScope,
    private val onVideo: (GlassesVideoFrame) -> Unit,
    private val onSdkAudio: (AudioChunk, rawBytes: Int) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
  data class Settings(
      val videoQuality: VideoQuality = VideoQuality.MEDIUM,
      val frameRate: Int = 24, // valid: 2, 7, 15, 24, 30
      val sdkAudio: Boolean = true,
      val sdkAudioRate: AudioSampleRate = AudioSampleRate.RATE_16000,
  )

  private val _sessionState = MutableStateFlow<DeviceSessionState?>(null)
  val sessionState: StateFlow<DeviceSessionState?> = _sessionState.asStateFlow()
  private val _streamState = MutableStateFlow<StreamState?>(null)
  val streamState: StateFlow<StreamState?> = _streamState.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var settings = Settings()
  private val sessionJobs = mutableListOf<Job>()
  private val streamJobs = mutableListOf<Job>()
  private var videoSeq = 0L
  private var audioSeq = 0L
  private var warnedFrameSize = false

  val isActive: Boolean
    get() = session != null

  fun start(settings: Settings) {
    if (session != null) {
      onEvent("Session already exists; ignoring start")
      return
    }
    this.settings = settings
    videoSeq = 0
    audioSeq = 0
    warnedFrameSize = false
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { s ->
          session = s
          // Subscribe BEFORE start() so no transition is missed (SDK guidance).
          sessionJobs +=
              scope.launch {
                s.state.collect { state ->
                  _sessionState.value = state
                  onEvent("DeviceSession -> $state")
                  when (state) {
                    DeviceSessionState.STARTED -> if (camera == null) attachCamera(s)
                    DeviceSessionState.PAUSED ->
                        onEvent("Session PAUSED by glasses (e.g. tap / another experience). Waiting.")
                    DeviceSessionState.STOPPED -> onSessionStopped(s)
                    else -> Unit
                  }
                }
              }
          sessionJobs +=
              scope.launch {
                s.errors.collect { e -> onError("Session error: ${e.name}: ${e.description}") }
              }
          s.start()
        }
        .onFailure { e, _ -> onError("createSession failed: ${e.name}: ${e.description}") }
  }

  private fun attachCamera(s: DeviceSession) {
    val config =
        StreamConfiguration(
            audioCodec =
                if (settings.sdkAudio) AudioCodec.PCM(sampleRate = settings.sdkAudioRate, numberOfChannels = 1)
                else null,
            videoQuality = settings.videoQuality,
            frameRate = settings.frameRate,
            compressVideo = false, // SDK decodes to I420 for us -> pixels available for processing
        )
    onEvent("addCamera(quality=${settings.videoQuality}, fps=${settings.frameRate}, sdkAudio=${settings.sdkAudio})")
    s.addCamera(config)
        .onSuccess { cam ->
          camera = cam
          val stream = cam.stream
          subscribe(stream)
          stream.start().onFailure { e, _ -> onError("stream.start failed: ${e.name}: ${e.description}") }
        }
        .onFailure { e, _ -> onError("addCamera failed: ${e.name}: ${e.description}") }
  }

  private fun subscribe(stream: Stream) {
    streamJobs +=
        scope.launch(Dispatchers.Default) { stream.videoStream.collect { handleVideo(it) } }
    if (settings.sdkAudio) {
      // Collect audio before start(); process off the main thread (slow collectors drop frames).
      streamJobs +=
          scope.launch(Dispatchers.IO) { stream.audioStream.collect { handleAudio(it) } }
    }
    streamJobs +=
        scope.launch {
          stream.state.collect { st ->
            _streamState.value = st
            onEvent("Stream -> $st")
          }
        }
    streamJobs +=
        scope.launch {
          stream.errorStream.collect { e ->
            // PERMISSIONS_DENIED covers a missing CAMERA *or* MICROPHONE grant in the Meta AI app.
            onError("Stream error: ${e.name}: ${e.description}")
          }
        }
  }

  private fun handleVideo(frame: VideoFrame) {
    val arrival = SystemClock.elapsedRealtimeNanos()
    if (frame.isCompressed) return // not expected with compressVideo=false
    val buf = frame.buffer
    val bytes = ByteArray(buf.remaining())
    buf.duplicate().get(bytes)
    val size = I420.resolveSize(frame.width, frame.height, bytes.size)
    if (size == null) {
      if (!warnedFrameSize) {
        warnedFrameSize = true
        onError("Unexpected video frame: ${bytes.size} bytes for reported ${frame.width}x${frame.height}")
      }
      return
    }
    if ((size.first != frame.width || size.second != frame.height) && !warnedFrameSize) {
      warnedFrameSize = true
      onEvent("Frame size ${size.first}x${size.second} differs from reported ${frame.width}x${frame.height}")
    }
    onVideo(GlassesVideoFrame(bytes, size.first, size.second, frame.presentationTimeUs, arrival, videoSeq++))
  }

  private fun handleAudio(frame: AudioFrame) {
    val arrival = SystemClock.elapsedRealtimeNanos()
    val buf = frame.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val rawBytes = buf.remaining()
    // The SDK decodes the glasses' AAC with MediaCodec, whose default output is 16-bit PCM.
    // The app verifies this on-device by comparing sample count against elapsed time.
    val sb = buf.asShortBuffer()
    val pcm = ShortArray(sb.remaining())
    sb.get(pcm)
    val rate =
        when (settings.sdkAudioRate) {
          AudioSampleRate.RATE_16000 -> 16_000
          AudioSampleRate.RATE_44100 -> 44_100
          AudioSampleRate.RATE_48000 -> 48_000
        }
    onSdkAudio(
        AudioChunk(pcm, rate, 1, AudioOrigin.GLASSES_DAT_STREAM, frame.presentationTimeUs, arrival, audioSeq++),
        rawBytes,
    )
  }

  /** Stops everything. Order follows the SDK docs: cancel stream collectors, stop camera, stop session. */
  fun stop() {
    streamJobs.forEach { it.cancel() }
    streamJobs.clear()
    val s = session ?: return
    camera?.let { cam ->
      runCatching { cam.stop() }
      runCatching { s.removeCamera() }
    }
    camera = null
    if (s.state.value != DeviceSessionState.STOPPED) {
      onEvent("Stopping DeviceSession")
      s.stop()
      // Safety net: if the SDK never reports STOPPED (e.g. Bluetooth already gone), clean up anyway.
      scope.launch {
        delay(3000)
        if (session === s) onSessionStopped(s)
      }
    } else {
      onSessionStopped(s)
    }
  }

  private fun onSessionStopped(s: DeviceSession) {
    if (session !== s) return
    Log.i(TAG, "Session stopped; releasing")
    streamJobs.forEach { it.cancel() }
    streamJobs.clear()
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    camera = null
    session = null
    _streamState.value = null
    _sessionState.value = DeviceSessionState.STOPPED
  }

  private companion object {
    const val TAG = "GlassesStream"
  }
}
