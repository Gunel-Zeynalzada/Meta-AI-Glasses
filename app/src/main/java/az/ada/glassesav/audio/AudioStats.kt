package az.ada.glassesav.audio

import az.ada.glassesav.pipeline.AudioChunk
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Running statistics for one audio source. Used for the live level meter and for checking the
 * stream format empirically: if "samples per second of wall-clock time" is close to the declared
 * sample rate, the 16-bit interpretation of the PCM bytes is correct.
 */
class AudioStats {
  data class Snapshot(
      val chunks: Long = 0,
      val totalSamples: Long = 0,
      val levelDbfs: Double = -120.0,
      val peakDbfs: Double = -120.0,
      val measuredRateByArrivalHz: Double? = null,
      val measuredRateByPtsHz: Double? = null,
      val lastPtsUs: Long? = null,
      val lastArrivalNs: Long? = null,
      val maxArrivalGapMs: Double = 0.0,
  )

  private var chunks = 0L
  private var totalSamples = 0L
  private var firstArrivalNs = 0L
  private var lastArrivalNs = 0L
  private var firstPtsUs: Long? = null
  private var lastPtsUs: Long? = null
  private var samplesSinceFirstPts = 0L
  private var peak = -120.0
  private var level = -120.0
  private var maxGapMs = 0.0

  @Synchronized
  fun add(c: AudioChunk) {
    val frames = c.pcm.size / max(1, c.channels)
    if (chunks == 0L) {
      firstArrivalNs = c.arrivalElapsedNs
      firstPtsUs = c.presentationTimeUs
      samplesSinceFirstPts = 0
    } else {
      maxGapMs = max(maxGapMs, (c.arrivalElapsedNs - lastArrivalNs) / 1e6)
      samplesSinceFirstPts += lastFrames
    }
    lastFrames = frames.toLong()
    chunks++
    totalSamples += frames
    lastArrivalNs = c.arrivalElapsedNs
    lastPtsUs = c.presentationTimeUs
    level = rmsDbfs(c.pcm)
    peak = max(peak - 0.5, level) // slow-decaying peak hold
  }

  private var lastFrames = 0L

  @Synchronized
  fun snapshot(): Snapshot {
    val wallSec = (lastArrivalNs - firstArrivalNs) / 1e9
    val first = firstPtsUs
    val last = lastPtsUs
    val ptsSec = if (first != null && last != null) (last - first) / 1e6 else 0.0
    return Snapshot(
        chunks = chunks,
        totalSamples = totalSamples,
        levelDbfs = level,
        peakDbfs = peak,
        // Exclude the last chunk's samples from the arrival-based estimate (fencepost).
        measuredRateByArrivalHz = if (wallSec > 2) (totalSamples - lastFrames) / wallSec else null,
        measuredRateByPtsHz = if (ptsSec > 2) samplesSinceFirstPts / ptsSec else null,
        lastPtsUs = last,
        lastArrivalNs = if (chunks > 0) lastArrivalNs else null,
        maxArrivalGapMs = maxGapMs,
    )
  }

  @Synchronized
  fun reset() {
    chunks = 0
    totalSamples = 0
    firstPtsUs = null
    lastPtsUs = null
    peak = -120.0
    level = -120.0
    maxGapMs = 0.0
    lastFrames = 0
  }

  companion object {
    fun rmsDbfs(pcm: ShortArray): Double {
      if (pcm.isEmpty()) return -120.0
      var sum = 0.0
      for (s in pcm) sum += s.toDouble() * s
      val rms = sqrt(sum / pcm.size) / 32768.0
      return if (rms <= 1e-6) -120.0 else 20 * log10(rms)
    }
  }
}
