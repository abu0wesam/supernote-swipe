package com.swipeswitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Image work for the PDF Tool plugin, with no third-party library:
 *  - processImage: straighten, widen the text and darken a PNG, for the preview;
 *  - detectSkew: how much a scanned page is tilted;
 *  - applyToPdf: redraw every page of a PDF the same way and write it over the file,
 *    after keeping a copy of the original;
 *  - restore: put the kept copy back.
 *
 * "Widen" keeps the page size and stretches the printed area (the text) towards the left and
 * right edges of the page, level 0 = as it is, level 4 = almost to the edges.
 */
class PdfToolModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

  override fun getName(): String = "PdfTool"

  // ---------------------------------------------------------------- preview

  @ReactMethod
  fun processImage(inPath: String, outPath: String, darkLevel: Int, rotateDeg: Double, widthLevel: Int, promise: Promise) {
    Thread {
      try {
        val opts = BitmapFactory.Options()
        opts.inMutable = true
        val decoded = BitmapFactory.decodeFile(inPath, opts) ?: throw IllegalStateException("Cannot read $inPath")
        val result = enhance(decoded, rotateDeg, widthLevel, darkLevel)
        val out = grayToBitmap(result.bytes, result.w, result.h)
        FileOutputStream(outPath).use { stream -> out.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        out.recycle()
        promise.resolve(true)
      } catch (e: Throwable) {
        promise.reject("E_IMAGE", e.message ?: e.toString())
      }
    }.start()
  }

  /** Degrees to rotate the picture clockwise to straighten it (0 when it is straight enough). */
  @ReactMethod
  fun detectSkew(inPath: String, promise: Promise) {
    Thread {
      try {
        val bmp = BitmapFactory.decodeFile(inPath) ?: throw IllegalStateException("Cannot read $inPath")
        val deg = autoDegrees(bmp)
        bmp.recycle()
        promise.resolve(deg)
      } catch (e: Throwable) {
        promise.reject("E_SKEW", e.message ?: e.toString())
      }
    }.start()
  }

  // ------------------------------------------------------------------ apply

  /** mode: "off", "auto" (each page is straightened by its own measured tilt) or "manual" (manualDeg for every page). */
  @ReactMethod
  fun applyToPdf(
      path: String,
      backupPath: String,
      widthLevel: Int,
      darkLevel: Int,
      mode: String,
      manualDeg: Double,
      promise: Promise
  ) {
    Thread {
      try {
        promise.resolve(runApply(path, backupPath, widthLevel, darkLevel, mode, manualDeg))
      } catch (e: Throwable) {
        promise.reject("E_APPLY", e.message ?: e.toString())
      }
    }.start()
  }

  @ReactMethod
  fun restore(path: String, backupPath: String, promise: Promise) {
    Thread {
      try {
        val backup = File(backupPath)
        if (!backup.isFile) throw IllegalStateException("No backup of this file")
        copyFile(backup, File(path))
        backup.delete()
        promise.resolve(true)
      } catch (e: Throwable) {
        promise.reject("E_RESTORE", e.message ?: e.toString())
      }
    }.start()
  }

  private fun runApply(
      path: String,
      backupPath: String,
      widthLevel: Int,
      darkLevel: Int,
      mode: String,
      manualDeg: Double
  ): WritableMap {
    val src = File(path)
    if (!src.isFile) throw IllegalStateException("File not found: $path")
    val backup = File(backupPath)
    backup.parentFile?.mkdirs()
    val work = File(backup.parentFile, "work_output.pdf")

    var total = 0
    val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
      val renderer = PdfRenderer(pfd)
      try {
        total = renderer.pageCount
        val writer = MiniPdfWriter(work)
        try {
          for (i in 0 until total) {
            val page = renderer.openPage(i)
            val pw = page.width
            val ph = page.height
            val wPx = min(MAX_WIDTH, max(1, (TARGET_HEIGHT.toDouble() * pw / ph).roundToInt()))
            val bmp = Bitmap.createBitmap(wPx, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val deg = when (mode) {
              "auto" -> autoDegrees(bmp)
              "manual" -> manualDeg
              else -> 0.0
            }
            val result = enhance(bmp, deg, widthLevel, darkLevel)
            writer.addPage(pw, ph, result.w, result.h, result.bytes)
            emit(i + 1, total)
          }
          writer.finish()
        } catch (e: Throwable) {
          writer.abort()
          throw e
        }
      } finally {
        renderer.close()
      }
    } finally {
      pfd.close()
    }

    // Keep the very first original: a second run must not replace it with an already adjusted file.
    if (!backup.exists()) copyFile(src, backup)
    try {
      copyFile(work, src)
    } catch (e: Throwable) {
      copyFile(backup, src)
      throw e
    } finally {
      work.delete()
    }

    val result = Arguments.createMap()
    result.putInt("pages", total)
    result.putDouble("bytes", src.length().toDouble())
    return result
  }

  private fun emit(done: Int, total: Int) {
    try {
      val map = Arguments.createMap()
      map.putInt("done", done)
      map.putInt("total", total)
      ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("PdfToolProgress", map)
    } catch (e: Throwable) {
      // progress is only informative
    }
  }

  private class Gray(val w: Int, val h: Int, val bytes: ByteArray)

  companion object {
    /** Pixels of a page's height in the new PDF (the Manta screen is 2560 high). */
    private const val TARGET_HEIGHT = 2048
    private const val MAX_WIDTH = 4096

    /** Number of steps between width level 0 and the last level (5 levels). */
    private const val WIDTH_STEPS = 4

    /** At the last width level the text ends this close to the page edge (share of the width). */
    private const val EDGE = 0.02

    private fun copyFile(from: File, to: File) {
      FileInputStream(from).use { input ->
        FileOutputStream(to).use { output ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
          }
          output.flush()
        }
      }
    }

    /** Straighten, widen, then darken. The input bitmap is recycled. */
    private fun enhance(input: Bitmap, rotateDeg: Double, widthLevel: Int, darkLevel: Int): Gray {
      var bmp = input
      if (abs(rotateDeg) >= 0.05) {
        val rotated = rotate(bmp, rotateDeg)
        bmp.recycle()
        bmp = rotated
      }
      if (widthLevel > 0) {
        val widened = widen(bmp, widthLevel)
        if (widened !== bmp) {
          bmp.recycle()
          bmp = widened
        }
      }
      val w = bmp.width
      val h = bmp.height
      val raw = grayBytes(bmp)
      bmp.recycle()
      return Gray(w, h, darken(raw, w, h, darkLevel))
    }

    // ---------------------------------------------------------------- gray

    /** 8-bit gray of a bitmap (transparent parts count as white). */
    private fun grayBytes(bmp: Bitmap): ByteArray {
      val w = bmp.width
      val h = bmp.height
      val px = IntArray(w * h)
      bmp.getPixels(px, 0, w, 0, 0, w, h)
      val out = ByteArray(w * h)
      for (i in px.indices) {
        val p = px[i]
        val a = (p ushr 24) and 0xFF
        val g0 = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        out[i] = (255 - ((255 - g0) * a) / 255).toByte()
      }
      return out
    }

    private fun grayToBitmap(gray: ByteArray, w: Int, h: Int): Bitmap {
      val px = IntArray(w * h)
      for (i in px.indices) {
        val v = gray[i].toInt() and 0xFF
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
      }
      return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------- darkening

    /**
     * Darkness level 1..10:
     *  1. the paper is made pure white (its brightness is read from the page);
     *  2. a curve pushes every gray towards black, more with each level;
     *  3. from level 3 the strokes are made thicker (1 px at level 6, 2 px at level 10, blended in between).
     */
    private fun darken(gray: ByteArray, w: Int, h: Int, level: Int): ByteArray {
      if (level <= 0) return gray
      val hist = IntArray(256)
      for (b in gray) hist[b.toInt() and 0xFF]++
      val target = (gray.size * 0.7).toLong()
      var acc = 0L
      var white = 255
      for (v in 0..255) {
        acc += hist[v]
        if (acc >= target) {
          white = v
          break
        }
      }
      white = max(150, white)
      val exponent = 1.0 + 0.4 * level
      val table = IntArray(256) { v ->
        val n = min(1.0, v / white.toDouble())
        (255.0 * n.pow(exponent)).roundToInt().coerceIn(0, 255)
      }
      val curved = ByteArray(gray.size) { i -> table[gray[i].toInt() and 0xFF].toByte() }

      var out = curved
      val w1 = ((level - 2) / 4.0).coerceIn(0.0, 1.0)
      if (w1 > 0.0) out = blend(out, minFilter(curved, w, h, 1), w1)
      val w2 = ((level - 6) / 4.0).coerceIn(0.0, 1.0)
      if (w2 > 0.0) out = blend(out, minFilter(curved, w, h, 2), w2)
      return out
    }

    private fun blend(a: ByteArray, b: ByteArray, weightOfB: Double): ByteArray =
        ByteArray(a.size) { i ->
          val x = a[i].toInt() and 0xFF
          val y = b[i].toInt() and 0xFF
          (x + (y - x) * weightOfB).roundToInt().toByte()
        }

    /** Each pixel takes the darkest value within r pixels: dark strokes grow by r pixels on every side. */
    private fun minFilter(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
      val tmp = ByteArray(w * h)
      for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
          var m = 255
          val x0 = max(0, x - r)
          val x1 = min(w - 1, x + r)
          for (k in x0..x1) {
            val v = src[row + k].toInt() and 0xFF
            if (v < m) m = v
          }
          tmp[row + x] = m.toByte()
        }
      }
      val out = ByteArray(w * h)
      for (y in 0 until h) {
        val y0 = max(0, y - r)
        val y1 = min(h - 1, y + r)
        for (x in 0 until w) {
          var m = 255
          for (k in y0..y1) {
            val v = tmp[k * w + x].toInt() and 0xFF
            if (v < m) m = v
          }
          out[y * w + x] = m.toByte()
        }
      }
      return out
    }

    // ---------------------------------------------------------------- widening

    /**
     * Stretches the printed area to the left and right: the part of the page between the first and
     * the last column that holds ink is moved towards the page edges (level / 4 of the way to
     * the edge margin); the blank strips outside it shrink. Same page size, same height.
     */
    private fun widen(src: Bitmap, level: Int): Bitmap {
      val box = contentColumns(src) ?: return src
      val t = min(level, WIDTH_STEPS) / WIDTH_STEPS.toDouble()
      val cl = box.first
      val cr = box.second
      val nl = cl + (min(cl, EDGE) - cl) * t
      val nr = cr + (max(cr, 1.0 - EDGE) - cr) * t
      if (nl == cl && nr == cr) return src

      val w = src.width
      val h = src.height
      val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(out)
      canvas.drawColor(Color.WHITE)
      val paint = Paint(Paint.FILTER_BITMAP_FLAG)
      fun x(f: Double): Int = (f * w).roundToInt().coerceIn(0, w)
      val from = intArrayOf(0, x(cl), x(cr), w)
      val to = intArrayOf(0, x(nl), x(nr), w)
      for (i in 0..2) {
        if (from[i + 1] > from[i] && to[i + 1] > to[i]) {
          canvas.drawBitmap(src, Rect(from[i], 0, from[i + 1], h), Rect(to[i], 0, to[i + 1], h), paint)
        }
      }
      return out
    }

    /** The first and last columns holding ink, as shares of the width; null for a page with almost no ink. */
    private fun contentColumns(src: Bitmap): Pair<Double, Double>? {
      val w = 600
      val h = max(1, (src.height.toDouble() * w / src.width).roundToInt())
      val small = Bitmap.createScaledBitmap(src, w, h, true)
      val px = IntArray(w * h)
      small.getPixels(px, 0, w, 0, 0, w, h)
      if (small !== src) small.recycle()
      val cols = IntArray(w)
      for (y in 0 until h) {
        for (x in 0 until w) {
          val p = px[y * w + x]
          val a = (p ushr 24) and 0xFF
          val g0 = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
          val g = 255 - ((255 - g0) * a) / 255
          if (g < 170) cols[x]++
        }
      }
      val need = max(3, (h * 0.008).roundToInt())
      var left = 0
      while (left < w && cols[left] < need) left++
      var right = w - 1
      while (right >= 0 && cols[right] < need) right--
      if (left >= right) return null
      val cl = left.toDouble() / w
      val cr = (right + 1).toDouble() / w
      if (cr - cl < 0.3) return null
      return Pair(cl, cr)
    }

    // ---------------------------------------------------------------- tilt

    /** Rotates clockwise around the centre, on white. */
    private fun rotate(src: Bitmap, deg: Double): Bitmap {
      val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(out)
      canvas.drawColor(Color.WHITE)
      val m = Matrix()
      m.postRotate(deg.toFloat(), src.width / 2f, src.height / 2f)
      canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
      return out
    }

    /** Clockwise degrees that straighten the page, or 0 when the tilt is below a third of a degree. */
    private fun autoDegrees(bmp: Bitmap): Double {
      val theta = detectTilt(bmp)
      return if (abs(theta) < 0.3) 0.0 else -theta
    }

    /**
     * Tilt of the text lines, in degrees (positive = the lines go down towards the right).
     * The dark points are projected on the vertical axis after each trial rotation;
     * the right angle is the one where the lines pile up in the fewest rows.
     */
    private fun detectTilt(src: Bitmap): Double {
      val w = 480
      val h = max(1, (src.height.toDouble() * w / src.width).roundToInt())
      val small = Bitmap.createScaledBitmap(src, w, h, true)
      val px = IntArray(w * h)
      small.getPixels(px, 0, w, 0, 0, w, h)
      if (small !== src) small.recycle()

      val xs = IntArray(px.size)
      val ys = IntArray(px.size)
      var n = 0
      for (y in 0 until h) {
        for (x in 0 until w) {
          val p = px[y * w + x]
          val g = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
          if (g < 140) {
            xs[n] = x
            ys[n] = y
            n++
          }
        }
      }
      if (n < 400) return 0.0

      val off = 64
      val bins = IntArray(h + 2 * off)
      fun score(theta: Double): Double {
        java.util.Arrays.fill(bins, 0)
        val s = sin(theta * PI / 180.0)
        val c = cos(theta * PI / 180.0)
        for (i in 0 until n) {
          val yy = (-xs[i] * s + ys[i] * c).toInt() + off
          if (yy >= 0 && yy < bins.size) bins[yy]++
        }
        var sum = 0.0
        for (b in bins) sum += b.toDouble() * b
        return sum
      }

      val base = score(0.0)
      var best = 0.0
      var bestScore = base
      var a = -4.0
      while (a <= 4.0001) {
        val sc = score(a)
        if (sc > bestScore) {
          bestScore = sc
          best = a
        }
        a += 0.5
      }
      val center = best
      a = center - 0.5
      while (a <= center + 0.5001) {
        val sc = score(a)
        if (sc > bestScore) {
          bestScore = sc
          best = a
        }
        a += 0.1
      }
      return if (bestScore > base * 1.02) best else 0.0
    }
  }
}

/**
 * Writes a PDF one page at a time (a gray image per page, Flate-compressed), so the memory used
 * does not grow with the number of pages the way android.graphics.pdf.PdfDocument does.
 */
private class MiniPdfWriter(private val file: File) {
  private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
  private var pos = 0L
  private val offsets = HashMap<Int, Long>()
  private val pageObjects = ArrayList<Int>()
  private var nextObject = 3 // 1 = catalog, 2 = page tree

  init {
    put("%PDF-1.4\n")
    put(byteArrayOf(37, -30, -29, -49, -45, 10))
  }

  private fun put(bytes: ByteArray) {
    out.write(bytes)
    pos += bytes.size
  }

  private fun put(text: String) = put(text.toByteArray(Charsets.ISO_8859_1))

  private fun begin(number: Int) {
    offsets[number] = pos
    put("$number 0 obj\n")
  }

  fun addPage(widthPt: Int, heightPt: Int, imgW: Int, imgH: Int, gray: ByteArray) {
    val image = nextObject++
    val content = nextObject++
    val page = nextObject++
    val z = deflate(gray)
    begin(image)
    put(
        "<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace /DeviceGray " +
            "/BitsPerComponent 8 /Filter /FlateDecode /Length ${z.size} >>\nstream\n")
    put(z)
    put("\nendstream\nendobj\n")
    val drawing = "q $widthPt 0 0 $heightPt 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.ISO_8859_1)
    begin(content)
    put("<< /Length ${drawing.size} >>\nstream\n")
    put(drawing)
    put("endstream\nendobj\n")
    begin(page)
    put(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $widthPt $heightPt] " +
            "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>\nendobj\n")
    pageObjects.add(page)
  }

  fun finish() {
    begin(1)
    put("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    begin(2)
    put("<< /Type /Pages /Count ${pageObjects.size} /Kids [" + pageObjects.joinToString(" ") { "$it 0 R" } + "] >>\nendobj\n")
    val xref = pos
    put("xref\n0 $nextObject\n")
    put("0000000000 65535 f \n")
    for (i in 1 until nextObject) {
      put(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
    }
    put("trailer\n<< /Size $nextObject /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    out.flush()
    out.close()
  }

  fun abort() {
    try {
      out.close()
    } catch (e: Throwable) {
      // already closed
    }
    file.delete()
  }

  private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(6)
    deflater.setInput(data)
    deflater.finish()
    val bytes = ByteArrayOutputStream(data.size / 8 + 1024)
    val buffer = ByteArray(1 shl 16)
    while (!deflater.finished()) {
      val n = deflater.deflate(buffer)
      bytes.write(buffer, 0, n)
    }
    deflater.end()
    return bytes.toByteArray()
  }
}
