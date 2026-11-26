package az.ada.glassesav.pipeline

import android.graphics.Bitmap

/** Helpers for the planar YUV 4:2:0 (I420) frames that the DAT SDK delivers by default. */
object I420 {
  /** Byte size of an I420 image. */
  fun sizeOf(width: Int, height: Int): Int = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)

  // Portrait 9:16 sizes documented for VideoQuality HIGH / MEDIUM / LOW, plus their landscape forms.
  private val knownSizes =
      listOf(720 to 1280, 504 to 896, 360 to 640, 1280 to 720, 896 to 504, 640 to 360)

  /**
   * The SDK can lower the resolution when Bluetooth bandwidth drops. If the reported width/height
   * do not match the buffer size, try the documented resolution ladder. Returns null if unknown.
   */
  fun resolveSize(reportedW: Int, reportedH: Int, byteCount: Int): Pair<Int, Int>? {
    if (reportedW > 0 && reportedH > 0 && sizeOf(reportedW, reportedH) == byteCount) {
      return reportedW to reportedH
    }
    return knownSizes.firstOrNull { (w, h) -> sizeOf(w, h) == byteCount }
  }

  /** Luma (Y) plane only: a ready-made grayscale image for face/lip detectors. */
  fun lumaPlane(frame: GlassesVideoFrame): ByteArray = frame.i420.copyOfRange(0, frame.width * frame.height)

  /**
   * Converts to ARGB_8888 (BT.601 limited range). Pure Kotlin for readability; ~5-10 ms for
   * 504x896 on a recent phone. [reuse] is recycled when the size matches.
   */
  fun toBitmap(frame: GlassesVideoFrame, reuse: Bitmap? = null): Bitmap {
    val w = frame.width
    val h = frame.height
    val data = frame.i420
    val cw = (w + 1) / 2
    val uOffset = w * h
    val vOffset = uOffset + cw * ((h + 1) / 2)
    val argb = IntArray(w * h)
    var idx = 0
    for (y in 0 until h) {
      val uvRow = (y shr 1) * cw
      for (x in 0 until w) {
        val c = (data[idx].toInt() and 0xFF) - 16
        val uvIdx = uvRow + (x shr 1)
        val d = (data[uOffset + uvIdx].toInt() and 0xFF) - 128
        val e = (data[vOffset + uvIdx].toInt() and 0xFF) - 128
        val c298 = 298 * c + 128
        val r = clamp((c298 + 409 * e) shr 8)
        val g = clamp((c298 - 100 * d - 208 * e) shr 8)
        val b = clamp((c298 + 516 * d) shr 8)
        argb[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    val bmp =
        if (reuse != null && reuse.width == w && reuse.height == h && reuse.isMutable) reuse
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(argb, 0, w, 0, 0, w, h)
    return bmp
  }

  private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
