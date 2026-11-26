package az.ada.glassesav.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import az.ada.glassesav.pipeline.AudioChunk
import az.ada.glassesav.pipeline.AudioChunkProcessor
import az.ada.glassesav.pipeline.AudioOrigin

/**
 * Keeps the audio of the most recent capture IN MEMORY ONLY (nothing is written to storage, SR9)
 * so it can be listened to after Stop. Capped at [MAX_SECONDS]; older audio is discarded.
 *
 * Playback goes to the GLASSES' SPEAKERS (their Bluetooth media output). It never falls back to the
 * phone speaker: if the glasses' output can't be found or Android routes elsewhere, playback stops
 * with an error. Playback is only allowed while not capturing, so the glasses' microphones are not
 * recording their own speakers.
 */
class ReplayBuffer(context: Context) : AudioChunkProcessor {
  private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val chunks = ArrayDeque<ShortArray>()
  private var totalSamples = 0L
  @Volatile private var sampleRate = 16_000
  @Volatile var origin: AudioOrigin? = null
    private set

  @Volatile private var track: AudioTrack? = null
  private var playThread: Thread? = null

  val seconds: Double
    @Synchronized get() = totalSamples.toDouble() / sampleRate

  @Synchronized
  override fun onAudioChunk(chunk: AudioChunk) {
    if (chunk.channels != 1) return // all current sources are mono
    sampleRate = chunk.sampleRate
    origin = chunk.origin
    chunks.addLast(chunk.pcm)
    totalSamples += chunk.pcm.size
    val max = MAX_SECONDS.toLong() * sampleRate
    while (totalSamples > max && chunks.isNotEmpty()) totalSamples -= chunks.removeFirst().size
  }

  @Synchronized
  fun clear() {
    chunks.clear()
    totalSamples = 0
    origin = null
  }

  @Synchronized
  private fun snapshot(): ShortArray {
    val out = ShortArray(totalSamples.toInt())
    var pos = 0
    for (c in chunks) {
      c.copyInto(out, pos)
      pos += c.size
    }
    return out
  }

  /**
   * Finds the glasses' Bluetooth media output. Prefers a device whose name matches [glassesName]
   * (the name reported by the DAT SDK). Returns null if no Bluetooth media output exists.
   */
  fun findGlassesOutput(glassesName: String?): AudioDeviceInfo? {
    val outputs =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in GLASSES_OUTPUT_TYPES }
    if (outputs.isEmpty()) return null
    val wanted = glassesName?.lowercase()?.takeIf { it.isNotBlank() }
    return outputs.firstOrNull { d ->
      val n = d.productName.toString().lowercase()
      wanted != null && (n.contains(wanted) || wanted.contains(n))
    } ?: outputs.first()
  }

  /**
   * Plays the buffered audio once on the glasses' speakers.
   * Returns null on success, or an error message (nothing is played on the phone speaker).
   * [onRouted] reports the device Android actually routed to; [onFinished] runs when playback ends.
   * Both run on a background thread.
   *
   * [startOffsetMs] lines the audio up with the video: positive = wait that long before playing
   * (audio started after the video), negative = skip that much audio (audio started first).
   */
  fun play(
      glassesName: String?,
      onRouted: (String) -> Unit,
      onError: (String) -> Unit,
      onFinished: () -> Unit,
      startOffsetMs: Long = 0,
  ): String? {
    stopPlayback()
    val all = snapshot()
    val skip = if (startOffsetMs < 0) (-startOffsetMs * sampleRate / 1000).toInt().coerceAtMost(all.size) else 0
    val pcm = if (skip > 0) all.copyOfRange(skip, all.size) else all
    val delayMs = startOffsetMs.coerceAtLeast(0)
    if (pcm.isEmpty()) return "Nothing to play yet: capture first."
    val target =
        findGlassesOutput(glassesName)
            ?: return "Glasses speakers not found. Check that the glasses are connected and that " +
                "'Media audio' is turned on for them in Android Bluetooth settings."
    val rate = sampleRate
    val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val t =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    if (!t.setPreferredDevice(target)) {
      t.release()
      return "Android refused to route playback to \"${target.productName}\"."
    }
    track = t
    playThread =
        Thread(
                {
                  try {
                    // Wait in small steps so Stop still works during the delay.
                    var waited = 0L
                    while (waited < delayMs && track === t) {
                      Thread.sleep(minOf(20L, delayMs - waited))
                      waited += 20
                    }
                    if (track !== t) return@Thread
                    t.play()
                    var pos = 0
                    var routeChecked = false
                    // Non-blocking writes, so Stop can always end this loop promptly.
                    while (pos < pcm.size && track === t) {
                      val n = t.write(pcm, pos, minOf(4096, pcm.size - pos), AudioTrack.WRITE_NON_BLOCKING)
                      when {
                        n < 0 -> break
                        n == 0 -> Thread.sleep(10)
                        else -> pos += n
                      }
                      // Verify where Android actually sends the sound (known once data flows).
                      if (!routeChecked) {
                        val routed = t.routedDevice
                        if (routed != null) {
                          routeChecked = true
                          if (routed.type !in GLASSES_OUTPUT_TYPES) {
                            onError(
                                "Playback was routed to ${AudioRouteDiagnostics.describe(routed)}, " +
                                    "not the glasses. Stopped."
                            )
                            break
                          }
                          onRouted(AudioRouteDiagnostics.describe(routed))
                        }
                      }
                    }
                    // Let the last buffer drain before releasing.
                    if (track === t) Thread.sleep(300)
                  } catch (e: Exception) {
                    Log.e(TAG, "Playback failed", e)
                  } finally {
                    runCatching { t.stop() }
                    t.release()
                    if (track === t) track = null
                    onFinished()
                  }
                },
                "replay",
            )
            .apply { start() }
    return null
  }

  fun stopPlayback() {
    val t = track ?: return
    track = null // signals the play loop to end; the thread stops and releases the track
    runCatching { t.pause() }
    playThread?.join(1000)
    playThread = null
  }

  private companion object {
    const val TAG = "ReplayBuffer"
    const val MAX_SECONDS = 600 // 10 min = ~19 MB at 16 kHz mono 16-bit

    /** Bluetooth output types the glasses' speakers can appear as (classic media or LE Audio). */
    val GLASSES_OUTPUT_TYPES =
        setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
  }
}
