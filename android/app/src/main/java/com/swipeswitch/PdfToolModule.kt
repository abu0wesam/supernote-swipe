package com.swipeswitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Image work for the PDF Tool plugin, with no third-party library:
 *  - processImage: clean the background, stretch the text, sharpen and darken a PNG, for the preview;
 *  - detectSkew: how much a scanned page is tilted;
 *  - applyToPdf: redraw every page of a PDF the same way and write it over the file,
 *    after keeping a copy of the original; cancelApply stops it and leaves the file untouched;
 *  - restore: put the kept copy back.
 *
 * Order of work on a page: straighten, make gray, clean the background, stretch the text towards
 * the page edges (sideways and up-and-down separately), sharpen, darken and thicken.
 */
class PdfToolModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

  override fun getName(): String = "PdfTool"

  /** Set by cancelApply; checked between pages. The file is only touched after the last page is done. */
  @Volatile private var cancelRequested = false

  private class CancelledException : RuntimeException("Cancelled")

  /** Levels chosen by the user: dark 1..10, the others 0 (off) .. 10. */
  private class Opts(val dark: Int, val width: Int, val height: Int, val sharp: Int, val clean: Int)

  private fun readInt(m: ReadableMap, key: String, fallback: Int): Int =
      if (m.hasKey(key) && !m.isNull(key)) m.getInt(key) else fallback

  private fun readDouble(m: ReadableMap, key: String, fallback: Double): Double =
      if (m.hasKey(key) && !m.isNull(key)) m.getDouble(key) else fallback

  private fun readString(m: ReadableMap, key: String, fallback: String): String =
      if (m.hasKey(key) && !m.isNull(key)) (m.getString(key) ?: fallback) else fallback

  private fun readOpts(m: ReadableMap): Opts =
      Opts(
          readInt(m, "dark", 3).coerceIn(0, 10),
          readInt(m, "width", 0).coerceIn(0, 10),
          readInt(m, "height", 0).coerceIn(0, 10),
          readInt(m, "sharp", 0).coerceIn(0, 10),
          readInt(m, "clean", 0).coerceIn(0, 10))

  // ---------------------------------------------------------------- preview

  @ReactMethod
  fun processImage(inPath: String, outPath: String, options: ReadableMap, promise: Promise) {
    val o = readOpts(options)
    val rotate = readDouble(options, "rotate", 0.0)
    Thread {
      try {
        val bmpOptions = BitmapFactory.Options()
        bmpOptions.inMutable = true
        val decoded = BitmapFactory.decodeFile(inPath, bmpOptions) ?: throw IllegalStateException("Cannot read $inPath")
        val result = enhance(decoded, rotate, o)
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

  /**
   * options: dark, width, height, sharp, clean (levels), threads (1..7), mode ("off", "auto" = each page
   * is straightened by its own measured tilt, "manual" = `manual` degrees for every page).
   */
  @ReactMethod
  fun applyToPdf(path: String, backupPath: String, options: ReadableMap, promise: Promise) {
    val o = readOpts(options)
    val threads = readInt(options, "threads", 5).coerceIn(1, MAX_THREADS)
    val mode = readString(options, "mode", "off")
    val manual = readDouble(options, "manual", 0.0)
    Thread {
      try {
        promise.resolve(runApply(path, backupPath, o, mode, manual, threads))
      } catch (e: CancelledException) {
        promise.reject("E_CANCELLED", "Cancelled")
      } catch (e: Throwable) {
        promise.reject("E_APPLY", e.message ?: e.toString())
      }
    }.start()
  }

  /** Stops applyToPdf at the next page. The original file is not changed. */
  @ReactMethod
  fun cancelApply(promise: Promise) {
    cancelRequested = true
    promise.resolve(true)
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

  private class Packed(val w: Int, val h: Int, val z: ByteArray)

  private class Job(val pw: Int, val ph: Int, val future: Future<Packed>)

  private fun isOutOfMemory(error: Throwable?): Boolean {
    var current = error
    while (current != null) {
      if (current is OutOfMemoryError) return true
      current = current.cause
    }
    return false
  }

  /** Runs the job; if memory runs out, starts again with half the threads. */
  private fun runApply(path: String, backupPath: String, o: Opts, mode: String, manualDeg: Double, requested: Int): WritableMap {
    cancelRequested = false
    var threads = requested
    var failure: Throwable? = null
    for (attempt in 0 until 4) {
      try {
        return runApplyOnce(path, backupPath, o, mode, manualDeg, threads)
      } catch (e: Throwable) {
        failure = e
        if (isOutOfMemory(e) && threads > 1) {
          threads = max(1, threads / 2)
          System.gc()
        } else {
          throw e
        }
      }
    }
    throw failure ?: IllegalStateException("Out of memory")
  }

  /**
   * Stages run side by side: this thread draws page n+1 (PdfRenderer allows only one page at a
   * time), the worker threads process and compress the pages already drawn, and the finished pages
   * are written in order. The number of workers is limited by the memory that is free.
   */
  private fun runApplyOnce(path: String, backupPath: String, o: Opts, mode: String, manualDeg: Double, requested: Int): WritableMap {
    val started = System.currentTimeMillis()
    var renderNs = 0L
    var waitNs = 0L
    var writeNs = 0L
    val workNs = AtomicLong()
    val packNs = AtomicLong()
    val src = File(path)
    if (!src.isFile) throw IllegalStateException("File not found: $path")
    val backup = File(backupPath)
    backup.parentFile?.mkdirs()
    val work = File(backup.parentFile, "work_output.pdf")

    var total = 0
    var used = 1
    val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
      val renderer = PdfRenderer(pfd)
      try {
        total = renderer.pageCount
        if (total <= 0) throw IllegalStateException("The PDF has no pages")

        val first = renderer.openPage(0)
        val firstW = min(MAX_WIDTH, max(1, (TARGET_HEIGHT.toDouble() * first.width / first.height).roundToInt()))
        first.close()
        val pixels = firstW.toLong() * TARGET_HEIGHT
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val byMemory = ((free * 0.75 - 4.0 * pixels) / (14.0 * pixels)).toInt()
        val workers = max(1, min(requested, byMemory))
        used = workers

        val writer = MiniPdfWriter(work)
        val pool = Executors.newFixedThreadPool(workers)
        val pending = ArrayDeque<Job>()
        var written = 0
        val pages = total

        fun flushOne() {
          if (cancelRequested) throw CancelledException()
          val job = pending.removeFirst()
          val tWait = System.nanoTime()
          val packed = job.future.get()
          waitNs += System.nanoTime() - tWait
          val tWrite = System.nanoTime()
          writer.addPage(job.pw, job.ph, packed.w, packed.h, packed.z)
          writeNs += System.nanoTime() - tWrite
          written++
          emit(written, pages)
        }

        try {
          for (i in 0 until total) {
            if (cancelRequested) throw CancelledException()
            val tRender = System.nanoTime()
            val page = renderer.openPage(i)
            val pw = page.width
            val ph = page.height
            val wPx = min(MAX_WIDTH, max(1, (TARGET_HEIGHT.toDouble() * pw / ph).roundToInt()))
            val bmp = Bitmap.createBitmap(wPx, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderNs += System.nanoTime() - tRender

            val future = pool.submit(
                Callable<Packed> {
                  val t0 = System.nanoTime()
                  val deg = when (mode) {
                    "auto" -> autoDegrees(bmp)
                    "manual" -> manualDeg
                    else -> 0.0
                  }
                  val g = enhance(bmp, deg, o)
                  val t1 = System.nanoTime()
                  val z = deflateBytes(g.bytes)
                  val t2 = System.nanoTime()
                  workNs.addAndGet(t1 - t0)
                  packNs.addAndGet(t2 - t1)
                  Packed(g.w, g.h, z)
                })
            pending.addLast(Job(pw, ph, future))
            while (pending.size >= workers + 1) flushOne()
          }
          while (pending.isNotEmpty()) flushOne()
          if (cancelRequested) throw CancelledException()
          writer.finish()
        } catch (e: Throwable) {
          pool.shutdownNow()
          writer.abort()
          throw e
        } finally {
          pool.shutdown()
        }
      } finally {
        renderer.close()
      }
    } finally {
      pfd.close()
    }

    // Last chance to stop: from here the file is being replaced, which is quick and is not interrupted.
    if (cancelRequested) {
      work.delete()
      throw CancelledException()
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

    val perPage = max(1, total).toDouble() * 1e6
    val result = Arguments.createMap()
    result.putInt("pages", total)
    result.putInt("threads", used)
    result.putDouble("bytes", src.length().toDouble())
    result.putDouble("ms", (System.currentTimeMillis() - started).toDouble())
    result.putDouble("renderMs", renderNs / perPage)
    result.putDouble("workMs", workNs.get() / perPage)
    result.putDouble("packMs", packNs.get() / perPage)
    result.putDouble("waitMs", waitNs / perPage)
    result.putDouble("writeMs", writeNs / perPage)
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

  private class Box(val left: Double, val right: Double, val top: Double, val bottom: Double)

  companion object {
    /** Pixels of a page's height in the new PDF: the Manta screen's own height, so nothing is resampled on screen. */
    private const val TARGET_HEIGHT = 2560
    private const val MAX_WIDTH = 4096
    private const val MAX_THREADS = 7

    /** Levels of each stretch: 0 = off, 10 = to the edge margin. */
    private const val STRETCH_STEPS = 10

    /** At the last level the text ends this close to the page edge (share of the page). */
    private const val EDGE = 0.02

    /** Deflate is lossless at every level; level 1 is much faster than 6 and the pixels are identical. */
    private const val DEFLATE_LEVEL = 1

    /** One reusable buffer per thread for the temporary pass of the 3 x 3 filters (always fully overwritten before it is read). */
    private val scratchHolder = ThreadLocal<ByteArray>()

    private fun scratch(size: Int): ByteArray {
      val current = scratchHolder.get()
      if (current != null && current.size >= size) return current
      val fresh = ByteArray(size)
      scratchHolder.set(fresh)
      return fresh
    }

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

    private fun deflateBytes(data: ByteArray): ByteArray {
      val deflater = Deflater(DEFLATE_LEVEL)
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

    /** Straighten, clean, stretch, sharpen, then darken. The input bitmap is recycled. */
    private fun enhance(input: Bitmap, rotateDeg: Double, o: Opts): Gray {
      var bmp = input
      if (abs(rotateDeg) >= 0.05) {
        val rotated = rotate(bmp, rotateDeg)
        bmp.recycle()
        bmp = rotated
      }
      val w = bmp.width
      val h = bmp.height
      var g = grayBytes(bmp)
      bmp.recycle()
      if (o.clean > 0) cleanBackground(g, w, h, o.clean)
      if (o.width > 0 || o.height > 0) g = stretchGray(g, w, h, o.width, o.height)
      if (o.sharp > 0) g = sharpenSmart(g, w, h, o.sharp)
      g = darken(g, w, h, o.dark)
      return Gray(w, h, g)
    }

    // ---------------------------------------------------------------- gray

    /** 8-bit gray of a bitmap (transparent parts count as white), read a band of rows at a time to save memory. */
    private fun grayBytes(bmp: Bitmap): ByteArray {
      val w = bmp.width
      val h = bmp.height
      val out = ByteArray(w * h)
      val band = 64
      val px = IntArray(w * band)
      var y = 0
      while (y < h) {
        val rows = min(band, h - y)
        bmp.getPixels(px, 0, w, 0, y, w, rows)
        val base = y * w
        for (i in 0 until w * rows) {
          val p = px[i]
          val a = (p ushr 24) and 0xFF
          val g0 = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
          out[base + i] = (255 - ((255 - g0) * a) / 255).toByte()
        }
        y += rows
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

    // ------------------------------------------------------- background cleanup

    /**
     * For poor scans and photocopies. In place.
     *  1. The paper's brightness is estimated for every 16 x 16 block (its 90th percentile, so the dark
     *     letters do not drag it down), spread to the neighbours, floored, smoothed and interpolated.
     *  2. Every pixel is divided by the paper under it: the uneven gray background becomes white.
     *  3. A black point and a white point (higher with the level) turn the light grays of shadows,
     *     stains and faint stamps into white while the letters stay black.
     *  4. Small isolated dark specks (up to 2 + level pixels) are erased.
     */
    private fun cleanBackground(g: ByteArray, w: Int, h: Int, level: Int) {
      val bs = 16
      val gw = (w + bs - 1) / bs
      val gh = (h + bs - 1) / bs
      val grid = FloatArray(gw * gh)
      val hist = IntArray(256)
      for (by in 0 until gh) {
        for (bx in 0 until gw) {
          java.util.Arrays.fill(hist, 0)
          val x0 = bx * bs
          val x1 = min(w, x0 + bs)
          val y0 = by * bs
          val y1 = min(h, y0 + bs)
          for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) hist[g[row + x].toInt() and 0xFF]++
          }
          val target = ((x1 - x0) * (y1 - y0) * 0.9).toInt()
          var acc = 0
          var v = 255
          for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) {
              v = i
              break
            }
          }
          grid[by * gw + bx] = v.toFloat()
        }
      }

      // A typical paper brightness for the whole page, and a floor under it so a large black area is not lightened.
      val sorted = grid.copyOf()
      java.util.Arrays.sort(sorted)
      val typical = sorted[(sorted.size * 0.7).toInt().coerceIn(0, sorted.size - 1)]
      val floor = max(60f, typical * 0.7f)

      // Borrow from the brightest neighbours (5 x 5), then smooth (3 x 3).
      val spread = FloatArray(grid.size)
      for (by in 0 until gh) {
        for (bx in 0 until gw) {
          var best = 0f
          for (dy in -2..2) {
            val yy = by + dy
            if (yy < 0 || yy >= gh) continue
            for (dx in -2..2) {
              val xx = bx + dx
              if (xx < 0 || xx >= gw) continue
              val v = grid[yy * gw + xx]
              if (v > best) best = v
            }
          }
          spread[by * gw + bx] = max(best, floor)
        }
      }
      val paper = FloatArray(grid.size)
      for (by in 0 until gh) {
        for (bx in 0 until gw) {
          var sum = 0f
          var n = 0
          for (dy in -1..1) {
            val yy = by + dy
            if (yy < 0 || yy >= gh) continue
            for (dx in -1..1) {
              val xx = bx + dx
              if (xx < 0 || xx >= gw) continue
              sum += spread[yy * gw + xx]
              n++
            }
          }
          paper[by * gw + bx] = sum / n
        }
      }

      val black = 40 + 4 * level
      val white = max(black + 30, 250 - 11 * level)
      val cut = IntArray(256) { n -> (((n - black).toDouble() / (white - black)).coerceIn(0.0, 1.0) * 255.0).roundToInt() }

      val xa = IntArray(w)
      val xb = IntArray(w)
      val xw = FloatArray(w)
      for (x in 0 until w) {
        val gx = (x + 0.5f) / bs - 0.5f
        val i0 = floor(gx).toInt().coerceIn(0, gw - 1)
        xa[x] = i0
        xb[x] = min(i0 + 1, gw - 1)
        xw[x] = (gx - i0).coerceIn(0f, 1f)
      }
      for (y in 0 until h) {
        val gy = (y + 0.5f) / bs - 0.5f
        val j0 = floor(gy).toInt().coerceIn(0, gh - 1)
        val j1 = min(j0 + 1, gh - 1)
        val fy = (gy - j0).coerceIn(0f, 1f)
        val rowA = j0 * gw
        val rowB = j1 * gw
        val row = y * w
        for (x in 0 until w) {
          val top = paper[rowA + xa[x]] + (paper[rowA + xb[x]] - paper[rowA + xa[x]]) * xw[x]
          val bottom = paper[rowB + xa[x]] + (paper[rowB + xb[x]] - paper[rowB + xa[x]]) * xw[x]
          val bg = top + (bottom - top) * fy
          val v = g[row + x].toInt() and 0xFF
          val flat = min(255, (v * 255f / bg).toInt())
          g[row + x] = cut[flat].toByte()
        }
      }

      removeSpecks(g, w, h, 2 + level)
    }

    /** Erases connected dark areas (8-neighbourhood) of at most maxArea pixels. */
    private fun removeSpecks(g: ByteArray, w: Int, h: Int, maxArea: Int) {
      // 0 = not seen, 1 = part of a larger shape, 2 = erased, 3 = in the area being measured
      val state = ByteArray(w * h)
      val queue = IntArray(maxArea + 2)
      for (start in 0 until w * h) {
        if (state[start].toInt() != 0) continue
        if ((g[start].toInt() and 0xFF) >= 128) continue
        var head = 0
        var tail = 0
        var big = false
        queue[tail++] = start
        state[start] = 3
        while (head < tail && !big) {
          val p = queue[head++]
          val px = p % w
          val py = p / w
          for (dy in -1..1) {
            val ny = py + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
              if (dx == 0 && dy == 0) continue
              val nx = px + dx
              if (nx < 0 || nx >= w) continue
              val np = ny * w + nx
              val st = state[np].toInt()
              if (st == 1) {
                big = true
              } else if (st == 0 && (g[np].toInt() and 0xFF) < 128) {
                if (tail > maxArea) {
                  big = true
                } else {
                  state[np] = 3
                  queue[tail++] = np
                }
              }
            }
          }
        }
        if (big) {
          for (i in 0 until tail) state[queue[i]] = 1
        } else {
          for (i in 0 until tail) {
            state[queue[i]] = 2
            g[queue[i]] = 255.toByte()
          }
        }
      }
    }

    // ------------------------------------------------------------- sharpening

    /**
     * Unsharp mask with a threshold: the difference from a 3 x 3 average is added back, but only the part
     * above a small threshold, so edges of letters get crisper and faint noise is not amplified.
     */
    private fun sharpenSmart(g: ByteArray, w: Int, h: Int, level: Int): ByteArray {
      val across = scratch(w * h)
      for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
          val a = g[row + max(0, x - 1)].toInt() and 0xFF
          val b = g[row + x].toInt() and 0xFF
          val c = g[row + min(w - 1, x + 1)].toInt() and 0xFF
          across[row + x] = ((a + b + c) / 3).toByte()
        }
      }
      val threshold = 10
      val out = ByteArray(w * h)
      for (y in 0 until h) {
        val up = max(0, y - 1) * w
        val mid = y * w
        val down = min(h - 1, y + 1) * w
        for (x in 0 until w) {
          val blur = (((across[up + x].toInt() and 0xFF) + (across[mid + x].toInt() and 0xFF) + (across[down + x].toInt() and 0xFF)) / 3)
          val v = g[mid + x].toInt() and 0xFF
          val d = v - blur
          val e = if (d > threshold) d - threshold else if (d < -threshold) d + threshold else 0
          out[mid + x] = (v + (e * level) / 8).coerceIn(0, 255).toByte()
        }
      }
      return out
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
      val w2 = ((level - 6) / 4.0).coerceIn(0.0, 1.0)
      if (w1 > 0.0) {
        val grown1 = minFilter(curved, w, h)
        out = blend(out, grown1, w1)
        if (w2 > 0.0) {
          // Growing by one pixel twice is growing by two: the second step starts from the first.
          out = blend(out, minFilter(grown1, w, h), w2)
        }
      }
      return out
    }

    /**
     * Same result as computing (x + (y - x) * weightOfB) rounded for every pixel, but the 65536 possible
     * (x, y) pairs are computed once with that very expression and then looked up.
     */
    private fun blend(a: ByteArray, b: ByteArray, weightOfB: Double): ByteArray {
      val table = ByteArray(256 * 256)
      for (x in 0..255) {
        for (y in 0..255) {
          table[(x shl 8) or y] = (x + (y - x) * weightOfB).roundToInt().toByte()
        }
      }
      return ByteArray(a.size) { i -> table[((a[i].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)] }
    }

    /** Each pixel takes the darkest value of its 3 x 3 neighbourhood: dark strokes grow by one pixel on every side. */
    private fun minFilter(src: ByteArray, w: Int, h: Int): ByteArray {
      val across = scratch(w * h)
      for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
          val a = src[row + max(0, x - 1)].toInt() and 0xFF
          val b = src[row + x].toInt() and 0xFF
          val c = src[row + min(w - 1, x + 1)].toInt() and 0xFF
          across[row + x] = min(a, min(b, c)).toByte()
        }
      }
      val out = ByteArray(w * h)
      for (y in 0 until h) {
        val up = max(0, y - 1) * w
        val mid = y * w
        val down = min(h - 1, y + 1) * w
        for (x in 0 until w) {
          val a = across[up + x].toInt() and 0xFF
          val b = across[mid + x].toInt() and 0xFF
          val c = across[down + x].toInt() and 0xFF
          out[mid + x] = min(a, min(b, c)).toByte()
        }
      }
      return out
    }

    // ---------------------------------------------------------------- stretching

    /**
     * Moves the printed area towards the page edges, each direction on its own.
     *
     * Sideways (widthLevel above 0): the text is also CENTRED. Its two side margins are replaced by one
     * equal margin, which starts from the average of the old left and right margins and shrinks
     * (widthLevel / 10) of the way to the edge margin. A page whose text sits too far right, and the
     * next page whose text sits too far left, both end up with the same margins.
     *
     * Up and down: the part between the first and the last row that holds ink moves
     * (heightLevel / 10) of the way to the top and bottom margins.
     *
     * The blank strips outside shrink; the page keeps its size. Done on the gray picture, in nine
     * blocks, with bilinear sampling.
     */
    private fun stretchGray(g: ByteArray, w: Int, h: Int, widthLevel: Int, heightLevel: Int): ByteArray {
      val box = contentBox(g, w, h) ?: return g
      val tw = min(widthLevel, STRETCH_STEPS) / STRETCH_STEPS.toDouble()
      val th = min(heightLevel, STRETCH_STEPS) / STRETCH_STEPS.toDouble()

      var nl = box.left
      var nr = box.right
      if (widthLevel > 0) {
        val average = (box.left + (1.0 - box.right)) / 2.0
        val margin = average + (min(average, EDGE) - average) * tw
        nl = margin
        nr = 1.0 - margin
      }
      val nt = box.top + (min(box.top, EDGE) - box.top) * th
      val nb = box.bottom + (max(box.bottom, 1.0 - EDGE) - box.bottom) * th
      if (nl == box.left && nr == box.right && nt == box.top && nb == box.bottom) return g

      val out = ByteArray(w * h)
      java.util.Arrays.fill(out, 255.toByte())
      fun at(f: Double, size: Int): Int = (f * size).roundToInt().coerceIn(0, size)
      val fromX = intArrayOf(0, at(box.left, w), at(box.right, w), w)
      val toX = intArrayOf(0, at(nl, w), at(nr, w), w)
      val fromY = intArrayOf(0, at(box.top, h), at(box.bottom, h), h)
      val toY = intArrayOf(0, at(nt, h), at(nb, h), h)
      for (j in 0..2) {
        for (i in 0..2) {
          resampleBlock(
              g, w, out,
              fromX[i], fromX[i + 1], fromY[j], fromY[j + 1],
              toX[i], toX[i + 1], toY[j], toY[j + 1])
        }
      }
      return out
    }

    /** Draws the source block [fx0, fx1) x [fy0, fy1) into the destination block, scaled, with bilinear sampling. */
    private fun resampleBlock(
        src: ByteArray, w: Int, out: ByteArray,
        fx0: Int, fx1: Int, fy0: Int, fy1: Int,
        tx0: Int, tx1: Int, ty0: Int, ty1: Int
    ) {
      val sw = fx1 - fx0
      val sh = fy1 - fy0
      val dw = tx1 - tx0
      val dh = ty1 - ty0
      if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
      if (sw == dw && sh == dh) {
        for (y in 0 until dh) System.arraycopy(src, (fy0 + y) * w + fx0, out, (ty0 + y) * w + tx0, dw)
        return
      }
      val xr = sw.toDouble() / dw
      val yr = sh.toDouble() / dh
      val xa = IntArray(dw)
      val xb = IntArray(dw)
      val xw = FloatArray(dw)
      for (dx in 0 until dw) {
        val sx = (dx + 0.5) * xr - 0.5
        val i0 = floor(sx).toInt()
        xa[dx] = (fx0 + i0).coerceIn(fx0, fx1 - 1)
        xb[dx] = (fx0 + i0 + 1).coerceIn(fx0, fx1 - 1)
        xw[dx] = (sx - i0).toFloat().coerceIn(0f, 1f)
      }
      for (dy in 0 until dh) {
        val sy = (dy + 0.5) * yr - 0.5
        val j0 = floor(sy).toInt()
        val rowA = (fy0 + j0).coerceIn(fy0, fy1 - 1) * w
        val rowB = (fy0 + j0 + 1).coerceIn(fy0, fy1 - 1) * w
        val wy = (sy - j0).toFloat().coerceIn(0f, 1f)
        val outRow = (ty0 + dy) * w + tx0
        for (dx in 0 until dw) {
          val a = src[rowA + xa[dx]].toInt() and 0xFF
          val b = src[rowA + xb[dx]].toInt() and 0xFF
          val c = src[rowB + xa[dx]].toInt() and 0xFF
          val d = src[rowB + xb[dx]].toInt() and 0xFF
          val top = a + (b - a) * xw[dx]
          val bottom = c + (d - c) * xw[dx]
          out[outRow + dx] = (top + (bottom - top) * wy + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
      }
    }

    /** The columns and rows that hold ink, as shares of the page; null for a page with almost no ink. */
    private fun contentBox(g: ByteArray, w: Int, h: Int): Box? {
      val cols = IntArray(w)
      val rows = IntArray(h)
      for (y in 0 until h) {
        val row = y * w
        var count = 0
        for (x in 0 until w) {
          if ((g[row + x].toInt() and 0xFF) < 170) {
            cols[x]++
            count++
          }
        }
        rows[y] = count
      }
      val needCol = max(4, (h * 0.006).roundToInt())
      val needRow = max(4, (w * 0.006).roundToInt())
      var left = 0
      while (left < w && cols[left] < needCol) left++
      var right = w - 1
      while (right >= 0 && cols[right] < needCol) right--
      var top = 0
      while (top < h && rows[top] < needRow) top++
      var bottom = h - 1
      while (bottom >= 0 && rows[bottom] < needRow) bottom--
      if (left >= right || top >= bottom) return null
      val box = Box(left.toDouble() / w, (right + 1).toDouble() / w, top.toDouble() / h, (bottom + 1).toDouble() / h)
      if (box.right - box.left < 0.3 || box.bottom - box.top < 0.3) return null
      return box
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
 * Writes a PDF one page at a time (a gray image per page, already Flate-compressed), so the memory
 * used does not grow with the number of pages the way android.graphics.pdf.PdfDocument does.
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

  /** [z] is the gray image, 8 bits per pixel, compressed with zlib. */
  fun addPage(widthPt: Int, heightPt: Int, imgW: Int, imgH: Int, z: ByteArray) {
    val image = nextObject++
    val content = nextObject++
    val page = nextObject++
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
}
