package az.ada.glassesav.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import az.ada.glassesav.audio.AudioRecordCapture
import az.ada.glassesav.audio.AudioRouteDiagnostics
import az.ada.glassesav.audio.AudioStats
import az.ada.glassesav.pipeline.AudioChunk
import az.ada.glassesav.pipeline.AudioOrigin
import az.ada.glassesav.pipeline.GlassesVideoFrame
import az.ada.glassesav.pipeline.I420
import az.ada.glassesav.pipeline.MediaHub
import az.ada.glassesav.pipeline.SessionRecorder
import az.ada.glassesav.pipeline.VideoFrameProcessor
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which audio path to use for a capture run. Exactly one is active at a time. */
enum class AudioMode(val title: String, val detail: String) {
  GLASSES_SDK_STREAM(
      "Glasses mic - DAT SDK stream (recommended)",
      "PCM inside the camera stream. Experimental; needs MICROPHONE permission in Meta AI.",
  ),
  GLASSES_HFP(
      "Glasses mic - Bluetooth HFP route",
      "AudioRecord on the Bluetooth SCO input; delivered only if Android confirms the route.",
  ),
  PHONE_MIC_FALLBACK(
      "PHONE mic (fallback - NOT glasses)",
      "For comparison tests only. Clearly labelled as phone audio.",
  ),
  NONE("No audio (video only)", ""),
}

data class DeviceUi(
    val id: String,
    val name: String,
    val type: String,
    val link: String,
    val firmware: String,
    val battery: Int,
)

data class UiState(
    val sdkInitialized: Boolean = false,
    val sdkInitError: String? = null,
    val bluetoothPermission: Boolean = false,
    val isDevMode: Boolean? = null,
    val registration: RegistrationState? = null,
    val devices: List<DeviceUi> = emptyList(),
    val cameraPermission: String = "unknown",
    val micPermission: String = "unknown",
    val audioMode: AudioMode = AudioMode.GLASSES_SDK_STREAM,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val frameRate: Int = 24,
    val capturing: Boolean = false,
    val sessionState: DeviceSessionState? = null,
    val streamState: StreamState? = null,
    // Source panel
    val videoSource: String = "-",
    val audioSource: String = "-",
    /** true = glasses audio verified, false = phone audio, null = no audio / not verified yet */
    val audioIsGlasses: Boolean? = null,
    val audioVerification: String = "",
    // Stats
    val videoFrames: Long = 0,
    val videoFps: Double = 0.0,
    val videoSize: String = "-",
    val droppedForProcessing: Long = 0,
    val audio: AudioStats.Snapshot = AudioStats.Snapshot(),
    val sdkAudioBytesPerSample: Double? = null,
    val avArrivalGapMs: Double? = null,
    val avPtsGapMs: Double? = null,
    val routeReport: String = "",
    /** The last capture is in the temporary folder and will be deleted unless the user taps Save. */
    val hasUnsavedCapture: Boolean = false,
    val isSaving: Boolean = false,
    /** Folder of the most recently saved capture (for `adb pull`). */
    val savedDir: String? = null,
    val replaySeconds: Double = 0.0,
    val replayLabel: String = "",
    val isPlaying: Boolean = false,
    /** Output device Android actually routed playback to (verified after playback starts). */
    val playbackDevice: String = "",
    val lastError: String? = null,
    val events: List<String> = emptyList(),
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val _preview = MutableStateFlow<Bitmap?>(null)
  val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

  /** Register your future processors here (see README "Where to plug in face/lip processing"). */
  val hub = MediaHub()

  private val diagnostics = AudioRouteDiagnostics(app) { log(it) }
  private val audioStats = AudioStats()
  /** In-memory copy of the last capture's audio for the Play button (never written to disk). */
  private val replay = az.ada.glassesav.audio.ReplayBuffer(app)
  private var audioCapture: AudioRecordCapture? = null
  private var recorder: SessionRecorder? = null

  // Captures are written to pendingRoot while recording; Save moves them to sessionsRoot.
  private val filesRoot: File = app.getExternalFilesDir(null) ?: app.filesDir
  private val pendingRoot = File(filesRoot, "pending")
  private val sessionsRoot = File(filesRoot, "sessions")
  @Volatile private var unsavedDir: File? = null
  private var closingJob: Job? = null
  private var statsJob: Job? = null
  private var monitoringStarted = false
  private val metadataJobs = mutableMapOf<DeviceIdentifier, Job>()

  // Video timing
  @Volatile private var videoCount = 0L
  @Volatile private var lastVideoArrivalNs = 0L
  @Volatile private var lastVideoPtsUs = 0L
  @Volatile private var videoSize = "-"
  private var fpsWindowStartNs = 0L
  private var fpsWindowCount = 0L
  @Volatile private var sdkAudioBytes = 0L

  private val controller =
      GlassesStreamController(
          scope = viewModelScope,
          onVideo = ::onVideoFrame,
          onSdkAudio = { chunk, raw ->
            sdkAudioBytes += raw
            onAudioChunk(chunk)
          },
          onEvent = { log(it) },
          onError = { setError(it) },
      )

  /** Preview renderer: an example VideoFrameProcessor (converts at most ~15 fps for display). */
  private val previewRenderer =
      object : VideoFrameProcessor {
        private var lastNs = 0L
        private val buffers = arrayOfNulls<Bitmap>(2)
        private var next = 0

        override fun onVideoFrame(frame: GlassesVideoFrame) {
          if (frame.arrivalElapsedNs - lastNs < 66_000_000L) return
          lastNs = frame.arrivalElapsedNs
          // Two bitmaps alternate so we never overwrite the one Compose is currently showing.
          val bmp = I420.toBitmap(frame, buffers[next])
          buffers[next] = bmp
          next = 1 - next
          _preview.value = bmp
        }
      }

  init {
    hub.addVideoProcessor(previewRenderer)
    hub.addAudioProcessor(replay)
    // Remove unsaved captures left behind if the app was killed before (SR9).
    // Only folders that exist right now are deleted, so a capture started meanwhile is safe.
    val stale = pendingRoot.listFiles()?.toList().orEmpty()
    if (stale.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { stale.forEach { it.deleteRecursively() } }
    viewModelScope.launch {
      controller.sessionState.collect { s ->
        _state.update { it.copy(sessionState = s) }
        if (s == DeviceSessionState.STOPPED && _state.value.capturing) {
          setError("Glasses session ended (disconnected, folded, or stopped by the glasses).")
          stopCapture()
        }
      }
    }
    viewModelScope.launch {
      controller.streamState.collect { st ->
        _state.update { it.copy(streamState = st) }
        if (st == StreamState.STREAMING) onStreaming()
        if ((st == StreamState.STOPPED || st == StreamState.CLOSED) && _state.value.capturing) {
          log("Stream ended ($st)")
        }
      }
    }
  }

  // ---------------------------------------------------------------- setup

  /** Called by the Activity once BLUETOOTH_CONNECT is granted. Wearables.initialize is required first. */
  fun onBluetoothPermission(granted: Boolean) {
    _state.update { it.copy(bluetoothPermission = granted) }
    if (!granted) {
      setError("Bluetooth permission is required to talk to the glasses.")
      return
    }
    if (_state.value.sdkInitialized) return
    Wearables.initialize(getApplication())
        .onSuccess {
          _state.update { it.copy(sdkInitialized = true, isDevMode = runCatching { Wearables.isDevMode }.getOrNull()) }
          log("Wearables.initialize OK (devMode=${_state.value.isDevMode})")
          startMonitoring()
        }
        .onFailure { e, _ ->
          _state.update { it.copy(sdkInitError = "${e.name}: ${e.description}") }
          setError("Wearables.initialize failed: ${e.description}")
        }
  }

  private fun startMonitoring() {
    if (monitoringStarted) return
    monitoringStarted = true
    diagnostics.register()
    viewModelScope.launch {
      Wearables.registrationState.collect { r ->
        _state.update { it.copy(registration = r) }
        log("Registration -> $r")
        if (r == RegistrationState.REGISTERED) refreshWearablePermissions()
      }
    }
    viewModelScope.launch {
      Wearables.registrationErrorStream.collect { e -> setError("Registration error: ${e.name}: ${e.description}") }
    }
    viewModelScope.launch {
      Wearables.devices.collect { ids ->
        (metadataJobs.keys - ids).forEach { metadataJobs.remove(it)?.cancel() }
        _state.update { s -> s.copy(devices = s.devices.filter { d -> ids.any { it.toString() == d.id } }) }
        (ids - metadataJobs.keys).forEach { id ->
          metadataJobs[id] =
              viewModelScope.launch {
                Wearables.devicesMetadata[id]?.collect { d ->
                  val ui =
                      DeviceUi(
                          id = id.toString(),
                          name = d.name,
                          type = d.deviceType.name,
                          link = d.linkState.name,
                          firmware = d.firmwareInfo ?: "?",
                          battery = d.batteryLevel,
                      )
                  _state.update { s -> s.copy(devices = s.devices.filter { it.id != ui.id } + ui) }
                }
              }
        }
      }
    }
  }

  fun register(activity: Activity) = Wearables.startRegistration(activity)

  fun unregister(activity: Activity) = Wearables.startUnregistration(activity)

  fun refreshWearablePermissions() {
    viewModelScope.launch {
      val cam = permissionText(Permission.CAMERA)
      val mic = permissionText(Permission.MICROPHONE)
      _state.update { it.copy(cameraPermission = cam, micPermission = mic) }
    }
  }

  private suspend fun permissionText(p: Permission): String =
      Wearables.checkPermissionStatus(p).fold(
          { if (it == PermissionStatus.Granted) "granted" else "not granted" },
          { e, _ -> "error: ${e.description}" },
      )

  fun setAudioMode(m: AudioMode) = _state.update { if (it.capturing) it else it.copy(audioMode = m) }

  fun setVideoQuality(q: VideoQuality) = _state.update { if (it.capturing) it else it.copy(videoQuality = q) }

  fun setFrameRate(f: Int) = _state.update { if (it.capturing) it else it.copy(frameRate = f) }


  // ---------------------------------------------------------------- capture

  /**
   * Start a capture run. Permission requests go through the Activity:
   * [requestWearablesPermission] opens the Meta AI app; [requestRecordAudio] is the Android dialog.
   */
  fun startCapture(
      requestWearablesPermission: suspend (Permission) -> PermissionStatus,
      requestRecordAudio: suspend () -> Boolean,
  ) {
    val s = _state.value
    if (s.capturing) return
    if (s.isSaving) return setError("Wait until saving has finished.")
    stopPlayback()
    if (!s.sdkInitialized) return setError("SDK not initialised (grant Bluetooth permission).")
    if (s.registration != RegistrationState.REGISTERED) {
      return setError("App is not registered with Meta AI. Tap Register first.")
    }
    viewModelScope.launch {
      clearError()
      if (!ensureWearablePermission(Permission.CAMERA, requestWearablesPermission)) {
        return@launch setError("Camera permission was not granted in the Meta AI app.")
      }
      when (s.audioMode) {
        AudioMode.GLASSES_SDK_STREAM ->
            if (!ensureWearablePermission(Permission.MICROPHONE, requestWearablesPermission)) {
              return@launch setError("Microphone permission was not granted in the Meta AI app.")
            }
        AudioMode.GLASSES_HFP,
        AudioMode.PHONE_MIC_FALLBACK ->
            if (!requestRecordAudio()) return@launch setError("Android RECORD_AUDIO permission denied.")
        AudioMode.NONE -> Unit
      }
      refreshWearablePermissions()
      beginRun(s)
    }
  }

  private suspend fun ensureWearablePermission(
      p: Permission,
      request: suspend (Permission) -> PermissionStatus,
  ): Boolean {
    val current = Wearables.checkPermissionStatus(p).getOrNull()
    if (current == PermissionStatus.Granted) return true
    log("Requesting $p via Meta AI app")
    return request(p) == PermissionStatus.Granted
  }

  private fun beginRun(s: UiState) {
    resetStats()
    replay.clear()
    // A new capture replaces the previous unsaved one (SR9: unsaved captures are not kept).
    discardUnsavedCapture()
    // Every capture is written to a temporary folder; Save moves it to sessions/.
    val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    recorder =
        SessionRecorder(File(pendingRoot, name)).also {
          hub.addAudioProcessor(it)
          it.logEvent("start mode=${s.audioMode} quality=${s.videoQuality} fps=${s.frameRate}")
        }
    _state.update { it.copy(savedDir = null) }
    _state.update {
      it.copy(
          capturing = true,
          videoSource = "Glasses camera (DAT Stream.videoStream) - waiting for frames",
          audioSource = audioLabelPending(s.audioMode),
          audioIsGlasses = null,
          audioVerification = "",
      )
    }
    log("=== Capture start: audio=${s.audioMode}, video=${s.videoQuality}@${s.frameRate}fps ===")
    log("Route report BEFORE capture:\n" + diagnostics.snapshot(null))
    controller.start(
        GlassesStreamController.Settings(
            videoQuality = s.videoQuality,
            frameRate = s.frameRate,
            sdkAudio = s.audioMode == AudioMode.GLASSES_SDK_STREAM,
        )
    )
    statsJob = viewModelScope.launch { while (isActive) { publishStats(); delay(500) } }
  }

  private fun audioLabelPending(m: AudioMode) =
      when (m) {
        AudioMode.GLASSES_SDK_STREAM -> "Glasses (DAT SDK stream) - waiting for audio frames"
        AudioMode.GLASSES_HFP -> "Glasses (Bluetooth HFP) - route not verified yet"
        AudioMode.PHONE_MIC_FALLBACK -> AudioOrigin.PHONE_MIC_FALLBACK.label
        AudioMode.NONE -> "None (video only)"
      }

  /** Stream is live: start the AudioRecord-based path now (Meta's sample also waits for this). */
  private fun onStreaming() {
    val mode = _state.value.audioMode
    if (!_state.value.capturing || audioCapture != null) return
    if (mode != AudioMode.GLASSES_HFP && mode != AudioMode.PHONE_MIC_FALLBACK) return
    val role = if (mode == AudioMode.GLASSES_HFP) AudioRecordCapture.Role.GLASSES_HFP else AudioRecordCapture.Role.PHONE_MIC
    val capture =
        AudioRecordCapture(
            getApplication(),
            role,
            diagnostics,
            onChunk = ::onAudioChunk,
            onProblem = { msg ->
              setError(msg)
              _state.update { it.copy(audioIsGlasses = if (role == AudioRecordCapture.Role.PHONE_MIC) false else null, audioVerification = msg) }
            },
        )
    audioCapture = capture
    val glassesName = _state.value.devices.firstOrNull { it.link == "CONNECTED" }?.name
    viewModelScope.launch {
      val err = capture.start(glassesName)
      if (err != null) {
        setError("Audio ($mode) failed: $err")
        _state.update { it.copy(audioSource = "NONE - $err", audioIsGlasses = null) }
        audioCapture = null
      }
    }
  }

  private fun onVideoFrame(f: GlassesVideoFrame) {
    videoCount++
    lastVideoArrivalNs = f.arrivalElapsedNs
    lastVideoPtsUs = f.presentationTimeUs
    videoSize = "${f.width}x${f.height}"
    recorder?.logVideoFrame(f)
    hub.publishVideo(f)
  }

  private fun onAudioChunk(c: AudioChunk) {
    audioStats.add(c)
    hub.publishAudio(c)
  }

  private fun publishStats() {
    val now = SystemClock.elapsedRealtimeNanos()
    if (fpsWindowStartNs == 0L) {
      fpsWindowStartNs = now
      fpsWindowCount = videoCount
    }
    val fps =
        if (now - fpsWindowStartNs > 0) (videoCount - fpsWindowCount) * 1e9 / (now - fpsWindowStartNs) else 0.0
    if (now - fpsWindowStartNs > 2_000_000_000L) {
      fpsWindowStartNs = now
      fpsWindowCount = videoCount
    }
    val a = audioStats.snapshot()
    val mode = _state.value.audioMode
    val (label, isGlasses, verification) = currentAudioVerdict(mode, a)
    val bytesPerSample =
        if (mode == AudioMode.GLASSES_SDK_STREAM && a.lastPtsUs != null && a.measuredRateByPtsHz != null && a.totalSamples > 0) {
          sdkAudioBytes.toDouble() / (a.totalSamples)
        } else null
    val avArrival =
        a.lastArrivalNs?.let { if (lastVideoArrivalNs > 0) (it - lastVideoArrivalNs) / 1e6 else null }
    // Only meaningful when audio and video PTS come from the same SDK stream.
    val avPts =
        if (mode == AudioMode.GLASSES_SDK_STREAM) {
          a.lastPtsUs?.let { if (videoCount > 0) (it - lastVideoPtsUs) / 1000.0 else null }
        } else null
    _state.update {
      it.copy(
          videoFrames = videoCount,
          videoFps = fps,
          videoSize = videoSize,
          videoSource =
              if (videoCount > 0) "Glasses camera (DAT Stream.videoStream, I420 $videoSize)"
              else it.videoSource,
          droppedForProcessing = hub.droppedVideoFrames,
          audio = a,
          audioSource = label ?: it.audioSource,
          audioIsGlasses = isGlasses ?: it.audioIsGlasses,
          audioVerification = verification ?: it.audioVerification,
          sdkAudioBytesPerSample = bytesPerSample,
          avArrivalGapMs = avArrival,
          avPtsGapMs = avPts,
      )
    }
  }

  /** Decides the label shown in the AUDIO SOURCE box, based on evidence only. */
  private fun currentAudioVerdict(mode: AudioMode, a: AudioStats.Snapshot): Triple<String?, Boolean?, String?> {
    return when (mode) {
      AudioMode.GLASSES_SDK_STREAM ->
          if (a.chunks > 0) {
            val openRecords = getApplication<Application>().getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.size
            Triple(
                AudioOrigin.GLASSES_DAT_STREAM.label,
                true,
                "Evidence: PCM arrives on Stream.audioStream over the glasses link. Android reports " +
                    "$openRecords active AudioRecord session(s) on this phone (expected 0 = no " +
                    "phone or HFP microphone is being captured).",
            )
          } else Triple(null, null, "No SDK audio frames yet. If this persists, check MICROPHONE permission and Logcat tag DAT:STREAM:AudioDecoder.")
      AudioMode.GLASSES_HFP -> {
        val dev = audioCapture?.verifiedDevice
        if (a.chunks > 0 && dev != null) {
          Triple(AudioOrigin.GLASSES_BLUETOOTH_HFP.label + " - $dev", true, "Evidence: AudioRecord.routedDevice = $dev")
        } else Triple(null, null, null)
      }
      AudioMode.PHONE_MIC_FALLBACK ->
          Triple(AudioOrigin.PHONE_MIC_FALLBACK.label, false, "Phone built-in mic: ${audioCapture?.verifiedDevice ?: "?"}")
      AudioMode.NONE -> Triple("None (video only)", null, "")
    }
  }

  fun stopCapture() {
    if (!_state.value.capturing) return
    log("=== Capture stop ===")
    statsJob?.cancel()
    statsJob = null
    audioCapture?.let { cap ->
      log("Route report AT STOP:\n" + diagnostics.snapshot(cap.record))
      cap.stop()
    }
    audioCapture = null
    controller.stop()
    recorder?.let { r ->
      hub.removeAudioProcessor(r)
      unsavedDir = r.dir
      // Finishing the MP4/WAV can take a moment; do it off the main thread.
      closingJob =
          viewModelScope.launch(Dispatchers.IO) {
            val summary = r.close()
            if (summary.isNotEmpty()) log(summary)
          }
    }
    recorder = null
    publishStats()
    _state.update {
      it.copy(
          capturing = false,
          replaySeconds = replay.seconds,
          replayLabel = replay.origin?.label ?: "",
          hasUnsavedCapture = unsavedDir != null,
      )
    }
  }

  /** Keeps the last capture: moves it from the temporary folder to sessions/. */
  fun saveLastCapture() {
    val src = unsavedDir ?: return
    if (_state.value.capturing || _state.value.isSaving) return
    _state.update { it.copy(isSaving = true) }
    viewModelScope.launch(Dispatchers.IO) {
      closingJob?.join() // make sure the files are complete
      sessionsRoot.mkdirs()
      val dest = File(sessionsRoot, src.name)
      if (src.renameTo(dest)) {
        unsavedDir = null
        log("Saved capture to ${dest.absolutePath}")
        _state.update { it.copy(isSaving = false, hasUnsavedCapture = false, savedDir = dest.absolutePath) }
      } else {
        setError("Could not save the capture (move to ${dest.absolutePath} failed).")
        _state.update { it.copy(isSaving = false) }
      }
    }
  }

  /** Deletes the last capture if it was not saved. */
  private fun discardUnsavedCapture() {
    val dir = unsavedDir ?: return
    val job = closingJob
    unsavedDir = null
    _state.update { it.copy(hasUnsavedCapture = false) }
    viewModelScope.launch(Dispatchers.IO) {
      job?.join()
      dir.deleteRecursively()
    }
  }

  /** Plays the last capture's audio on the glasses' speakers (only when not capturing). */
  fun playLastCapture() {
    if (_state.value.capturing) return
    clearError()
    val glassesName = _state.value.devices.firstOrNull { it.link == "CONNECTED" }?.name
    // Set before starting, so a very short playback can't finish before this flag is set.
    _state.update { it.copy(playbackDevice = "", isPlaying = true) }
    val error =
        replay.play(
            glassesName = glassesName,
            onRouted = { device ->
              log("Playback routed to $device")
              _state.update { it.copy(playbackDevice = device) }
            },
            onError = { msg -> setError(msg) },
            onFinished = { _state.update { it.copy(isPlaying = false) } },
        )
    if (error == null) {
      log("Playing last capture on glasses: ${"%.1f".format(replay.seconds)} s, source = ${replay.origin?.label}")
    } else {
      _state.update { it.copy(isPlaying = false) }
      setError(error)
    }
  }

  fun stopPlayback() {
    replay.stopPlayback()
    _state.update { it.copy(isPlaying = false) }
  }

  /** Writes a timestamped marker (e.g. at a hand clap) into the event log and recording. */
  fun addMarker() {
    val t = SystemClock.elapsedRealtimeNanos()
    log("MARKER at elapsed_ns=$t")
  }

  fun refreshRouteReport() {
    val report = diagnostics.snapshot(audioCapture?.record)
    _state.update { it.copy(routeReport = report) }
    Log.i(AudioRouteDiagnostics.TAG, report)
  }

  private fun resetStats() {
    audioStats.reset()
    hub.resetCounters()
    videoCount = 0
    lastVideoArrivalNs = 0
    lastVideoPtsUs = 0
    fpsWindowStartNs = 0
    fpsWindowCount = 0
    sdkAudioBytes = 0
    _preview.value = null
  }

  private fun log(msg: String) {
    Log.i(TAG, msg)
    recorder?.logEvent(msg)
    _state.update { it.copy(events = (it.events + msg).takeLast(80)) }
  }

  private fun setError(msg: String) {
    Log.e(TAG, msg)
    recorder?.logEvent("ERROR $msg")
    _state.update { it.copy(lastError = msg, events = (it.events + "ERROR: $msg").takeLast(80)) }
  }

  private fun clearError() = _state.update { it.copy(lastError = null) }

  override fun onCleared() {
    // The app is closing: an unsaved capture is deleted. viewModelScope is already cancelled here,
    // so coroutines launched by stopCapture() won't run; close and delete on a plain thread instead.
    val active = recorder
    stopCapture()
    val dir = unsavedDir
    unsavedDir = null
    if (dir != null) {
      Thread {
            active?.close() // no-op if it was already closed
            dir.deleteRecursively()
          }
          .start()
    }
    diagnostics.unregister()
    stopPlayback()
    hub.shutdown()
    super.onCleared()
  }

  private companion object {
    const val TAG = "GlassesAV"
  }
}
