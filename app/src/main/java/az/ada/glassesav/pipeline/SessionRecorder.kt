package az.ada.glassesav.pipeline

import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Writes one capture session to [dir], for offline analysis (e.g. in Python) and SR2 of the paper.
 *
 * Every capture is written to a temporary folder; it is only kept if the user taps Save, otherwise
 * it is deleted (SR9: nothing is kept by default). See CaptureViewModel for that logic.
 *
 * Files:
 *   video.mp4           H.264 video from the glasses camera (no audio track)
 *   audio.wav           16-bit PCM exactly as received from the glasses (mono, 16 kHz)
 *   video_frames.csv    seq, arrival_elapsed_ns, pts_us, width, height, bytes   (every frame)
 *   audio_chunks.csv    seq, origin, arrival_elapsed_ns, pts_us, samples, rate, channels, rms_dbfs
 *   events.log          state changes, route diagnostics, Marker presses (clap test)
 *
 * video.mp4 timestamps = video_frames.csv pts_us minus the first frame's pts_us. Use the CSVs (both
 * share the phone clock arrival_elapsed_ns) to align audio and video.
 */
class SessionRecorder(val dir: File) : AudioChunkProcessor {
  init {
    dir.mkdirs()
  }

  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "session-recorder") }
  private val audioCsv = writer("audio_chunks.csv", "seq,origin,arrival_elapsed_ns,pts_us,samples,sample_rate,channels,rms_dbfs")
  private val videoCsv = writer("video_frames.csv", "seq,arrival_elapsed_ns,pts_us,width,height,bytes")
  private val events = writer("events.log", null)
  private val video = Mp4VideoWriter(File(dir, "video.mp4"))
  private var wav: RandomAccessFile? = null
  private var wavDataBytes = 0L
  private var rate = 16000
  private var channels = 1
  @Volatile private var closed = false

  private fun submit(block: () -> Unit) {
    try {
      io.execute(block)
    } catch (_: RejectedExecutionException) {
      // recorder already closed
    }
  }

  private fun writer(name: String, header: String?): BufferedWriter =
      File(dir, name).bufferedWriter().also { w -> header?.let { w.write(it); w.newLine() } }

  /** Called for EVERY video frame (bypasses the hub's frame dropping so nothing is missed). */
  fun logVideoFrame(f: GlassesVideoFrame) {
    if (closed) return
    submit {
      videoCsv.write("${f.sequence},${f.arrivalElapsedNs},${f.presentationTimeUs},${f.width},${f.height},${f.i420.size}")
      videoCsv.newLine()
      video.write(f)
    }
  }

  /** AudioChunkProcessor: appends to the WAV and logs timing. */
  override fun onAudioChunk(chunk: AudioChunk) {
    if (closed) return
    val rms = az.ada.glassesav.audio.AudioStats.rmsDbfs(chunk.pcm)
    submit {
      val out = wav ?: openWav(chunk.sampleRate, chunk.channels).also { wav = it }
      val bytes = ByteArray(chunk.pcm.size * 2)
      for (i in chunk.pcm.indices) {
        val s = chunk.pcm[i].toInt()
        bytes[2 * i] = (s and 0xFF).toByte()
        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
      }
      out.write(bytes)
      wavDataBytes += bytes.size
      audioCsv.write(
          "${chunk.sequence},${chunk.origin.name},${chunk.arrivalElapsedNs},${chunk.presentationTimeUs ?: ""}," +
              "${chunk.pcm.size / chunk.channels},${chunk.sampleRate},${chunk.channels},${"%.1f".format(Locale.US, rms)}"
      )
      audioCsv.newLine()
    }
  }

  fun logEvent(message: String) {
    if (closed) return
    val t = SystemClock.elapsedRealtimeNanos()
    submit {
      events.write("$t\t$message")
      events.newLine()
    }
  }

  private fun openWav(rate: Int, channels: Int): RandomAccessFile {
    val f = RandomAccessFile(File(dir, "audio.wav"), "rw")
    f.setLength(0)
    f.write(wavHeader(rate, channels, 0))
    this.rate = rate
    this.channels = channels
    return f
  }

  /**
   * Finishes all files and BLOCKS until they are complete (up to 20 s). Call off the main thread.
   * Returns a one-line summary, e.g. for the event log.
   */
  fun close(): String {
    if (closed) return ""
    closed = true
    var summary = ""
    try {
      io.execute {
        video.finish()
        summary =
            "Video: ${video.framesWritten} frames encoded, ${video.framesDropped} skipped" +
                (video.failure?.let { " (MP4 not saved: $it)" } ?: "") +
                "; audio: ${wavDataBytes / (2 * channels) / rate.coerceAtLeast(1)} s"
        events.write("${SystemClock.elapsedRealtimeNanos()}\t$summary")
        events.newLine()
        wav?.let {
          it.seek(0)
          it.write(wavHeader(rate, channels, wavDataBytes))
          it.close()
        }
        audioCsv.close()
        videoCsv.close()
        events.close()
      }
    } catch (_: RejectedExecutionException) {}
    io.shutdown()
    io.awaitTermination(20, TimeUnit.SECONDS)
    return summary
  }

  private fun wavHeader(rate: Int, ch: Int, dataLen: Long): ByteArray {
    val byteRate = rate * ch * 2
    val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(rate)
    b.putInt(byteRate).putShort((ch * 2).toShort()).putShort(16)
    b.put("data".toByteArray()).putInt(dataLen.toInt())
    return b.array()
  }
}
