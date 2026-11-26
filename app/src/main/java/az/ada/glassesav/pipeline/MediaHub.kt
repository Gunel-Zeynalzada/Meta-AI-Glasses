package az.ada.glassesav.pipeline

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fan-out point between capture and processing.
 *
 * Capture code calls [publishVideo] / [publishAudio]; processing code calls [addVideoProcessor] /
 * [addAudioProcessor]. Each side runs on its own single background thread:
 * - video: "latest frame wins" (stale frames are skipped so the preview and detectors stay live)
 * - audio: FIFO queue, nothing is dropped (speech models need contiguous samples)
 *
 * FUTURE WORK hooks in here: register a face detector / lip tracker as a VideoFrameProcessor and a
 * VAD / active-speaker / target-speaker-extraction model as an AudioChunkProcessor.
 */
class MediaHub {
  private val videoProcessors = CopyOnWriteArrayList<VideoFrameProcessor>()
  private val audioProcessors = CopyOnWriteArrayList<AudioChunkProcessor>()

  private val pendingVideo = AtomicReference<GlassesVideoFrame?>(null)
  private val videoExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "video-processors") }

  private val audioQueue = LinkedBlockingQueue<AudioChunk>()
  @Volatile private var running = true
  private val audioThread =
      Thread(
              {
                while (running) {
                  val chunk = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                  for (p in audioProcessors) {
                    try {
                      p.onAudioChunk(chunk)
                    } catch (t: Throwable) {
                      Log.e(TAG, "Audio processor ${p.javaClass.simpleName} failed", t)
                    }
                  }
                }
              },
              "audio-processors",
          )
          .apply {
            isDaemon = true
            start()
          }

  /** Number of video frames skipped because processors were busy (useful for latency analysis). */
  @Volatile var droppedVideoFrames: Long = 0
    private set

  fun addVideoProcessor(p: VideoFrameProcessor) = videoProcessors.add(p)

  fun removeVideoProcessor(p: VideoFrameProcessor) = videoProcessors.remove(p)

  fun addAudioProcessor(p: AudioChunkProcessor) = audioProcessors.add(p)

  fun removeAudioProcessor(p: AudioChunkProcessor) = audioProcessors.remove(p)

  fun publishVideo(frame: GlassesVideoFrame) {
    if (!running) return
    // If a frame was still waiting, it is replaced (dropped) by the newer one.
    if (pendingVideo.getAndSet(frame) != null) {
      droppedVideoFrames++
      return
    }
    videoExecutor.execute {
      val f = pendingVideo.getAndSet(null) ?: return@execute
      for (p in videoProcessors) {
        try {
          p.onVideoFrame(f)
        } catch (t: Throwable) {
          Log.e(TAG, "Video processor ${p.javaClass.simpleName} failed", t)
        }
      }
    }
  }

  fun publishAudio(chunk: AudioChunk) {
    if (running) audioQueue.offer(chunk)
  }

  fun resetCounters() {
    droppedVideoFrames = 0
  }

  fun shutdown() {
    running = false
    videoExecutor.shutdownNow()
    audioThread.interrupt()
  }

  private companion object {
    const val TAG = "MediaHub"
  }
}
