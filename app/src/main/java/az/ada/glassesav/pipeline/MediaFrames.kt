package az.ada.glassesav.pipeline

/**
 * Where an audio chunk physically came from. Every audio chunk carries this label so that no
 * downstream code (or thesis figure) can confuse phone audio with glasses audio.
 */
enum class AudioOrigin(val label: String, val isGlasses: Boolean) {
  /** PCM delivered by the DAT SDK inside the glasses camera stream (Stream.audioStream). */
  GLASSES_DAT_STREAM("Glasses microphone (DAT SDK audio stream, experimental)", true),

  /** PCM read with AudioRecord while Android reported the routed input as Bluetooth SCO (HFP). */
  GLASSES_BLUETOOTH_HFP("Glasses microphone (Bluetooth HFP/SCO route, verified)", true),

  /** PCM from the PHONE's own microphone. Fallback only; never glasses audio. */
  PHONE_MIC_FALLBACK("PHONE MICROPHONE - NOT GLASSES AUDIO", false),
}

/**
 * One decoded video frame from the glasses.
 *
 * @property i420 Planar YUV 4:2:0 bytes (Y plane, then U, then V), as delivered by the SDK when
 *   StreamConfiguration.compressVideo = false (the default). Owned copy: safe to keep.
 * @property presentationTimeUs Timestamp supplied by the SDK (VideoFrame.presentationTimeUs). Its
 *   clock origin is not documented, so only use differences between frames of the same stream.
 * @property arrivalElapsedNs Phone clock (SystemClock.elapsedRealtimeNanos) when the frame reached
 *   this app. Comparable across video AND audio, so it is the common clock for A/V alignment.
 * @property sequence 0-based frame counter within the current stream.
 */
class GlassesVideoFrame(
    val i420: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val arrivalElapsedNs: Long,
    val sequence: Long,
)

/**
 * One chunk of 16-bit mono/interleaved PCM audio.
 *
 * @property presentationTimeUs Source timestamp. For [AudioOrigin.GLASSES_DAT_STREAM] it is
 *   AudioFrame.presentationTimeUs from the SDK. For AudioRecord-based origins it is derived from
 *   AudioRecord.getTimestamp() (CLOCK_MONOTONIC, converted to microseconds) when available,
 *   otherwise null.
 * @property arrivalElapsedNs Phone clock (SystemClock.elapsedRealtimeNanos) when the chunk reached
 *   this app. Same clock as [GlassesVideoFrame.arrivalElapsedNs].
 */
class AudioChunk(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val origin: AudioOrigin,
    val presentationTimeUs: Long?,
    val arrivalElapsedNs: Long,
    val sequence: Long,
) {
  val durationUs: Long
    get() = if (sampleRate <= 0 || channels <= 0) 0 else pcm.size * 1_000_000L / (sampleRate * channels)
}

/**
 * Implement this to receive video frames, e.g. a face detector or lip-region tracker.
 * Called on a background thread, one frame at a time. If you are slower than the frame rate,
 * frames are dropped for you (latest frame wins), so never block for long.
 */
fun interface VideoFrameProcessor {
  fun onVideoFrame(frame: GlassesVideoFrame)
}

/**
 * Implement this to receive audio, e.g. a VAD, an active-speaker model or a TSE model buffer.
 * Called on a background thread for every chunk, in order. Audio is never dropped by the hub,
 * so keep processing fast or hand the samples to your own queue.
 */
fun interface AudioChunkProcessor {
  fun onAudioChunk(chunk: AudioChunk)
}
