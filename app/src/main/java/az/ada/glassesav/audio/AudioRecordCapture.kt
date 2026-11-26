package az.ada.glassesav.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import az.ada.glassesav.pipeline.AudioChunk
import az.ada.glassesav.pipeline.AudioOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Captures audio with Android's AudioRecord, in one of two clearly separated roles:
 *
 * - [Role.GLASSES_HFP]: route input to the glasses over Bluetooth HFP (SCO) and deliver PCM ONLY
 *   while Android reports the AudioRecord's routedDevice as TYPE_BLUETOOTH_SCO. If routing cannot
 *   be proven, no audio is delivered (we never silently fall back to the phone mic).
 *   Adapted from Meta's CameraAccess sample (SDK 1.1.0) stream/AudioInputHandler.kt.
 *
 * - [Role.PHONE_MIC]: deliberately record the PHONE's built-in microphone. Every chunk is labelled
 *   [AudioOrigin.PHONE_MIC_FALLBACK]. Use only as a labelled comparison/fallback.
 *
 * Bluetooth HFP limits: the SCO link itself carries 8 kHz (CVSD, narrowband) or 16 kHz (mSBC,
 * wideband) mono audio. Requesting 16 kHz from AudioRecord does not guarantee wideband content.
 */
class AudioRecordCapture(
    context: Context,
    private val role: Role,
    private val diagnostics: AudioRouteDiagnostics,
    private val onChunk: (AudioChunk) -> Unit,
    private val onProblem: (String) -> Unit,
) {
  enum class Role {
    GLASSES_HFP,
    PHONE_MIC,
  }

  private val context = context.applicationContext
  private val audioManager = this.context.getSystemService(AudioManager::class.java)

  @Volatile var record: AudioRecord? = null
    private set

  @Volatile private var active = false
  private var thread: Thread? = null
  private var routingListener: AudioRouting.OnRoutingChangedListener? = null
  private var changedCommunicationRoute = false
  private var previousMode = AudioManager.MODE_NORMAL

  /** Human-readable description of the device Android actually routed us to (after start). */
  @Volatile var verifiedDevice: String? = null
    private set

  /**
   * Prepares routing and starts capture. Returns null on success or an error message.
   * [expectedGlassesName] (from the DAT device metadata) is used to pick the right SCO device when
   * several Bluetooth headsets are connected.
   */
  suspend fun start(expectedGlassesName: String?): String? {
    if (active) return null
    if (
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      return "Android RECORD_AUDIO permission not granted"
    }
    val preferredInput: AudioDeviceInfo =
        when (role) {
          Role.GLASSES_HFP -> prepareHfpRoute(expectedGlassesName) ?: return lastError
          Role.PHONE_MIC -> {
            // Make sure no earlier Bluetooth communication route is still forcing SCO.
            runCatching { audioManager.clearCommunicationDevice() }
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
              it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            } ?: return "No built-in phone microphone found"
          }
        }
    return openAndStart(preferredInput)
  }

  private var lastError: String? = null

  private suspend fun prepareHfpRoute(expectedName: String?): AudioDeviceInfo? {
    val scoCandidates =
        audioManager.availableCommunicationDevices.filter {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    diagnostics.emit(
        "HFP: SCO communication candidates = " +
            scoCandidates.joinToString { AudioRouteDiagnostics.describe(it) }.ifEmpty { "none" }
    )
    if (scoCandidates.isEmpty()) {
      lastError =
          "No Bluetooth SCO (HFP) device available. Is the glasses' 'calls/audio' Bluetooth " +
              "profile connected in Android Bluetooth settings?"
      return null
    }
    // Prefer the SCO device whose name matches the glasses reported by the DAT SDK.
    val sco =
        scoCandidates.firstOrNull { matchesName(it, expectedName) }
            ?: scoCandidates.first().also {
              if (scoCandidates.size > 1 || expectedName != null) {
                diagnostics.emit(
                    "HFP WARNING: no SCO device name matches glasses \"$expectedName\"; using " +
                        "\"${it.productName}\". Disconnect other headsets to be sure."
                )
              }
            }

    previousMode = audioManager.mode
    changedCommunicationRoute = true
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    val accepted = runCatching { audioManager.setCommunicationDevice(sco) }.getOrDefault(false)
    val routed =
        accepted &&
            withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
              while (audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                delay(100)
              }
              true
            } == true
    if (!routed) {
      restoreRoute()
      lastError = "Android refused or timed out switching the communication device to SCO"
      return null
    }
    val input =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
              (it.address == sco.address || it.productName == sco.productName)
        }
    if (input == null) {
      restoreRoute()
      lastError = "SCO output routed but no matching SCO INPUT device was listed"
      return null
    }
    diagnostics.emit("HFP: communication device set to ${AudioRouteDiagnostics.describe(sco)}")
    return input
  }

  private fun matchesName(d: AudioDeviceInfo, expected: String?): Boolean {
    if (expected.isNullOrBlank()) return false
    val a = d.productName.toString().lowercase()
    val b = expected.lowercase()
    return a.contains(b) || b.contains(a)
  }

  @SuppressLint("MissingPermission") // checked in start()
  private fun openAndStart(preferredInput: AudioDeviceInfo): String? {
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    if (minBuf <= 0) {
      restoreRoute()
      return "AudioRecord.getMinBufferSize failed ($minBuf)"
    }
    // VOICE_RECOGNITION: minimal phone-side processing and, per Meta's sample, avoids the extra
    // SCO bandwidth that VOICE_COMMUNICATION negotiates (which hurt the camera stream).
    val source =
        if (role == Role.GLASSES_HFP) MediaRecorder.AudioSource.VOICE_RECOGNITION
        else MediaRecorder.AudioSource.MIC
    val r =
        try {
          AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 4)
        } catch (e: Exception) {
          restoreRoute()
          return "AudioRecord constructor failed: ${e.message}"
        }
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release()
      restoreRoute()
      return "AudioRecord failed to initialize"
    }
    if (!r.setPreferredDevice(preferredInput)) {
      diagnostics.emit("setPreferredDevice(${preferredInput.productName}) was not accepted")
    }
    record = r
    active = true
    thread = Thread({ captureLoop(r, minBuf) }, "audiorecord-${role.name}").apply { start() }
    return null
  }

  private fun captureLoop(r: AudioRecord, minBuf: Int) {
    try {
      r.startRecording()
      // routedDevice is null until data flows; poll up to ~1 s.
      var routed: AudioDeviceInfo? = r.routedDevice
      var attempts = 0
      while (routed == null && attempts++ < 20 && active) {
        Thread.sleep(50)
        routed = r.routedDevice
      }
      val routedType = routed?.type
      val ok =
          when (role) {
            Role.GLASSES_HFP -> routedType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            Role.PHONE_MIC -> routedType == AudioDeviceInfo.TYPE_BUILTIN_MIC
          }
      verifiedDevice = routed?.let { AudioRouteDiagnostics.describe(it) } ?: "unknown (null)"
      diagnostics.emit("${role.name}: AudioRecord.routedDevice = $verifiedDevice")
      if (!ok) {
        if (role == Role.GLASSES_HFP) {
          onProblem(
              "ROUTE CHECK FAILED: Android routed capture to $verifiedDevice, not Bluetooth SCO. " +
                  "No audio is delivered (refusing to pass off phone audio as glasses audio)."
          )
          return
        } else {
          diagnostics.emit("PHONE_MIC: routed to $verifiedDevice (expected built-in mic)")
        }
      }
      val listener =
          AudioRouting.OnRoutingChangedListener { router ->
            val now = router.routedDevice
            diagnostics.emit(
                "${role.name}: routing CHANGED -> ${now?.let { AudioRouteDiagnostics.describe(it) } ?: "null"}"
            )
            if (role == Role.GLASSES_HFP && now != null && now.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
              active = false
              onProblem("Glasses HFP route LOST (now ${AudioRouteDiagnostics.typeName(now.type)}); audio stopped.")
            }
          }
      r.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
      routingListener = listener

      val shorts = ShortArray(minBuf / 2)
      val ts = AudioTimestamp()
      var framesRead = 0L
      var seq = 0L
      while (active) {
        val n = r.read(shorts, 0, shorts.size)
        if (n < 0) {
          onProblem("AudioRecord.read error $n (mic taken by another app or a call?)")
          break
        }
        if (n == 0) continue
        val arrival = SystemClock.elapsedRealtimeNanos()
        // BOOTTIME = same clock as SystemClock.elapsedRealtimeNanos(), so audio and video share it.
        val ptsUs =
            if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
              (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / SAMPLE_RATE) / 1000
            } else null
        framesRead += n
        onChunk(
            AudioChunk(
                pcm = shorts.copyOf(n),
                sampleRate = SAMPLE_RATE,
                channels = 1,
                origin =
                    if (role == Role.GLASSES_HFP) AudioOrigin.GLASSES_BLUETOOTH_HFP
                    else AudioOrigin.PHONE_MIC_FALLBACK,
                presentationTimeUs = ptsUs,
                arrivalElapsedNs = arrival,
                sequence = seq++,
            )
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "capture loop failed", e)
      onProblem("Audio capture failed: ${e.message}")
    } finally {
      runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
    }
  }

  fun stop() {
    active = false
    thread?.join(1500)
    thread = null
    routingListener?.let { l -> runCatching { record?.removeOnRoutingChangedListener(l) } }
    routingListener = null
    record?.release()
    record = null
    restoreRoute()
  }

  private fun restoreRoute() {
    if (!changedCommunicationRoute) return
    changedCommunicationRoute = false
    runCatching { audioManager.clearCommunicationDevice() }
    runCatching { audioManager.mode = previousMode }
  }

  companion object {
    private const val TAG = "AudioRecordCapture"
    const val SAMPLE_RATE = 16_000 // what the planned ASR/TSE models (Whisper, SeamlessM4T) expect
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val SCO_CONNECT_TIMEOUT_MS = 3000L
  }
}
