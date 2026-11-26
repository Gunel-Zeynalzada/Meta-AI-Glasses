package az.ada.glassesav.pipeline

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Encodes the glasses' I420 frames into an H.264 MP4 file (video only; audio is saved separately as
 * WAV so it stays bit-exact for analysis). Uses Android's built-in MediaCodec encoder.
 *
 * Timestamps in the MP4 come from the SDK's VideoFrame.presentationTimeUs (relative to the first
 * frame), so the video's timing matches video_frames.csv.
 *
 * Not thread-safe: call [write] and [finish] from one thread (SessionRecorder's IO thread).
 */
class Mp4VideoWriter(private val file: File, private val frameRate: Int = 24) {
  private var codec: MediaCodec? = null
  private var muxer: MediaMuxer? = null
  private var track = -1
  private var muxerStarted = false
  private var width = 0
  private var height = 0
  private var firstPtsUs = -1L
  private var lastPtsUs = -1L
  private val info = MediaCodec.BufferInfo()

  var framesWritten = 0L
    private set
  var framesDropped = 0L
    private set
  /** Set if the encoder could not be used; video.mp4 is then missing, everything else is saved. */
  var failure: String? = null
    private set

  fun write(frame: GlassesVideoFrame) {
    if (failure != null) return
    try {
      if (codec == null) start(frame.width, frame.height)
      // The encoder has a fixed size. If the SDK lowers the resolution mid-capture, those frames are
      // skipped here (they are still listed in video_frames.csv).
      if (frame.width != width || frame.height != height) {
        framesDropped++
        return
      }
      val c = codec ?: return
      val index = c.dequeueInputBuffer(10_000)
      if (index < 0) {
        framesDropped++
        drain(endOfStream = false)
        return
      }
      val image = c.getInputImage(index)
      if (image == null) {
        c.queueInputBuffer(index, 0, 0, 0, 0)
        fail("Encoder gave no input image")
        return
      }
      copyI420(frame, image)
      if (firstPtsUs < 0) firstPtsUs = frame.presentationTimeUs
      var pts = frame.presentationTimeUs - firstPtsUs
      if (pts <= lastPtsUs) pts = lastPtsUs + 1 // MP4 needs strictly increasing timestamps
      lastPtsUs = pts
      c.queueInputBuffer(index, 0, I420.sizeOf(width, height), pts, 0)
      framesWritten++
      drain(endOfStream = false)
    } catch (e: Exception) {
      fail("Video encoding failed: ${e.message}")
    }
  }

  /** Flushes the encoder and closes the file. Safe to call more than once. */
  fun finish() {
    val c = codec ?: return
    try {
      val index = c.dequeueInputBuffer(100_000)
      if (index >= 0) {
        c.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
      }
    } catch (e: Exception) {
      Log.w(TAG, "finish failed", e)
    }
    release()
    if (!file.exists() || file.length() == 0L || framesWritten == 0L) file.delete()
  }

  private fun start(w: Int, h: Int) {
    width = w
    height = h
    val format =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
          setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
          setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec = c
    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    c.start()
    muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    Log.i(TAG, "Encoder started ${w}x$h @$frameRate fps -> ${file.name}")
  }

  private fun drain(endOfStream: Boolean) {
    val c = codec ?: return
    var idleTries = 0
    while (true) {
      val out = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
      when {
        out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!endOfStream || ++idleTries > 100) return
        }
        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val m = muxer ?: return
          track = m.addTrack(c.outputFormat)
          m.start()
          muxerStarted = true
        }
        out >= 0 -> {
          val data = c.getOutputBuffer(out)
          if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
          if (data != null && info.size > 0 && muxerStarted) {
            data.position(info.offset)
            data.limit(info.offset + info.size)
            muxer?.writeSampleData(track, data, info)
          }
          c.releaseOutputBuffer(out, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  private fun copyI420(frame: GlassesVideoFrame, image: Image) {
    val w = frame.width
    val h = frame.height
    val cw = (w + 1) / 2
    val ch = (h + 1) / 2
    val planes = image.planes
    copyPlane(frame.i420, 0, w, h, planes[0])
    copyPlane(frame.i420, w * h, cw, ch, planes[1]) // U
    copyPlane(frame.i420, w * h + cw * ch, cw, ch, planes[2]) // V
  }

  private fun copyPlane(src: ByteArray, offset: Int, pw: Int, ph: Int, plane: Image.Plane) {
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    for (row in 0 until ph) {
      val rowStart = row * rowStride
      val srcStart = offset + row * pw
      if (pixelStride == 1) {
        buf.position(rowStart)
        buf.put(src, srcStart, pw)
      } else {
        for (col in 0 until pw) buf.put(rowStart + col * pixelStride, src[srcStart + col])
      }
    }
  }

  private fun fail(message: String) {
    Log.e(TAG, message)
    failure = message
    release()
    file.delete()
  }

  private fun release() {
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    codec = null
    if (muxerStarted) runCatching { muxer?.stop() }
    runCatching { muxer?.release() }
    muxer = null
    muxerStarted = false
  }

  private companion object {
    const val TAG = "Mp4VideoWriter"
  }
}
