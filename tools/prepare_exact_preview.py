#!/usr/bin/env python3
"""Run AFTER prepare_pdf_tool.py and BEFORE npm ci, tsc and bundling.
Shared PdfRenderer preview + SHA-256 pixel proof + bold icon 6."""
import hashlib
import struct
import zlib
from pathlib import Path
from prepare_pdf_tool import replace_once

ROOT = Path(__file__).resolve().parents[1]
BRIDGE_BLOB = "e68911a5dabe8ca0641d777f4f57285bf4959cd9"
START_MARKER = "/** The real size of a page: the file API first, then the size the reader shows. */"

def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

SIZE = 256
SEGMENTS = [  # x1, y1, x2, y2, stroke width: document outline, folded corner, wrench handle
    (48, 224, 48, 28, 12), (48, 28, 151, 28, 12), (151, 28, 206, 83, 12),
    (206, 83, 206, 224, 12), (206, 224, 48, 224, 12),
    (151, 28, 151, 83, 10), (151, 83, 206, 83, 10),
    (91, 187, 152, 126, 24),
]
DISCS = [(91, 187, 17), (155, 125, 25)]  # handle end and wrench head
HOLES = [(91, 187, 5)]
NOTCH = [(147, 98), (169, 98), (155, 117), (165, 127), (183, 112), (184, 137), (161, 138), (143, 120)]

def near(px, py, seg):
    x1, y1, x2, y2, w = seg
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
    return (px - x1 - t * dx) ** 2 + (py - y1 - t * dy) ** 2 <= (w / 2) ** 2

def disc(px, py, c):
    return (px - c[0]) ** 2 + (py - c[1]) ** 2 <= c[2] ** 2

def inside(px, py, poly):
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit

def make_icon_png():
    """Bold black-and-white 1-bit PNG, drawn with the standard library only."""
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)
        byte = 0
        for x in range(SIZE):
            px, py = x + 0.5, y + 0.5
            black = any(near(px, py, s) for s in SEGMENTS) or any(disc(px, py, c) for c in DISCS)
            if black and (any(disc(px, py, c) for c in HOLES) or inside(px, py, NOTCH)):
                black = False
            byte = (byte << 1) | (0 if black else 1)
            if x % 8 == 7:
                rows.append(byte)
                byte = 0

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 1, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))

NATIVE_EDITS = [
    (r'''  private class Opts(val dark: Int, val width: Int, val height: Int, val sharp: Int, val clean: Int)''',
     r'''  private class Opts(val dark: Int, val width: Int, val height: Int, val sharp: Int, val clean: Int,
      val expectedBytes: Long, val expectedModified: Long, val previewPage: Int, val previewHash: String)'''),
    (r'''          readInt(m, "clean", 0).coerceIn(0, 10))''',
     r'''          readInt(m, "clean", 0).coerceIn(0, 10),
          readDouble(m, "expectedBytes", -1.0).toLong(),
          readDouble(m, "expectedModified", -1.0).toLong(),
          readInt(m, "previewPage", -1),
          readString(m, "previewHash", ""))'''),
    (r'''  @ReactMethod
  fun processImage(inPath: String, outPath: String, options: ReadableMap, promise: Promise) {''',
     r'''  private fun grayHash(bytes: ByteArray): String =
      java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        String.format(Locale.US, "%02x", it.toInt() and 0xFF)
      }

  private fun verifyPreviewSource(src: File, o: Opts) {
    if (o.previewHash.length != 64 || o.previewPage < 0 ||
        src.length() != o.expectedBytes || src.lastModified() != o.expectedModified) {
      throw IllegalStateException("The preview is missing or the PDF changed. Reopen the tool to refresh it.")
    }
  }

  private fun renderExactPage(page: PdfRenderer.Page): Bitmap {
    val w = min(MAX_WIDTH, max(1, (TARGET_HEIGHT.toDouble() * page.width / page.height).roundToInt()))
    val bmp = Bitmap.createBitmap(w, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
      bmp.eraseColor(Color.WHITE)
      page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      return bmp
    } catch (e: Throwable) {
      bmp.recycle()
      throw e
    }
  }

  @ReactMethod
  fun renderPdfPage(path: String, pageIndex: Int, outPath: String, promise: Promise) {
    Thread {
      try {
        val src = File(path)
        val bytes = src.length()
        val modified = src.lastModified()
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
          val renderer = PdfRenderer(pfd)
          try {
            val page = renderer.openPage(pageIndex)
            val pageWidth = page.width
            val pageHeight = page.height
            val bmp = try { renderExactPage(page) } finally { page.close() }
            try {
              FileOutputStream(outPath).use { stream ->
                check(bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Cannot write preview" }
              }
              check(src.length() == bytes && src.lastModified() == modified) { "PDF changed while drawing preview" }
              val result = Arguments.createMap()
              result.putInt("width", bmp.width)
              result.putInt("height", bmp.height)
              result.putInt("pageWidth", pageWidth)
              result.putInt("pageHeight", pageHeight)
              result.putDouble("sourceBytes", bytes.toDouble())
              result.putDouble("sourceModified", modified.toDouble())
              promise.resolve(result)
            } finally {
              bmp.recycle()
            }
          } finally {
            renderer.close()
          }
        } finally {
          pfd.close()
        }
      } catch (e: Throwable) {
        promise.reject("E_PREVIEW_RENDER", e.message ?: e.toString())
      }
    }.start()
  }

  @ReactMethod
  fun processImageExact(inPath: String, outPath: String, options: ReadableMap, promise: Promise) {
    val o = readOpts(options)
    val rotate = readDouble(options, "rotate", 0.0)
    Thread {
      try {
        val decoded = BitmapFactory.decodeFile(inPath) ?: throw IllegalStateException("Cannot read $inPath")
        val g = enhance(decoded, rotate, o)
        val out = grayToBitmap(g.bytes, g.w, g.h)
        try {
          FileOutputStream(outPath).use { stream ->
            check(out.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Cannot write preview" }
          }
        } finally {
          out.recycle()
        }
        val result = Arguments.createMap()
        result.putString("hash", grayHash(g.bytes))
        promise.resolve(result)
      } catch (e: Throwable) {
        promise.reject("E_PREVIEW_PROCESS", e.message ?: e.toString())
      }
    }.start()
  }

  @ReactMethod
  fun processImage(inPath: String, outPath: String, options: ReadableMap, promise: Promise) {'''),
    (r'''    if (!src.isFile) throw IllegalStateException("File not found: $path")''',
     r'''    if (!src.isFile) throw IllegalStateException("File not found: $path")
    verifyPreviewSource(src, o)'''),
    (r'''        if (total <= 0) throw IllegalStateException("The PDF has no pages")''',
     r'''        if (total <= 0) throw IllegalStateException("The PDF has no pages")
        check(o.previewPage < total) { "The preview page is outside the PDF" }'''),
    (r'''            val wPx = min(MAX_WIDTH, max(1, (TARGET_HEIGHT.toDouble() * pw / ph).roundToInt()))
            val bmp = Bitmap.createBitmap(wPx, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()''',
     r'''            val bmp = try { renderExactPage(page) } finally { page.close() }'''),
    (r'''                  val g = enhance(bmp, deg, o)
''',
     r'''                  val g = enhance(bmp, deg, o)
                  if (i == o.previewPage && grayHash(g.bytes) != o.previewHash) {
                    throw IllegalStateException("Output does not match the preview. The PDF was not replaced.")
                  }
'''),
    (r'''    // Keep the very first original: a second run must not replace it with an already adjusted file.''',
     r'''    verifyPreviewSource(src, o)

    // Keep the very first original: a second run must not replace it with an already adjusted file.'''),
]

BRIDGE_EDITS = [
    (r'''export type ApplyOptions = Levels & {threads: number; mode: SkewMode; manual: number};''',
     r'''export type ApplyOptions = Levels & {threads: number; mode: SkewMode; manual: number; expectedBytes: number; expectedModified: number; previewPage: number; previewHash: string};'''),
    (r'''type Bridge = {''',
     r'''type RenderedPage = {width: number; height: number; pageWidth: number; pageHeight: number; sourceBytes: number; sourceModified: number};

type Bridge = {
  renderPdfPage(path: string, page: number, outPath: string): Promise<RenderedPage>;
  processImageExact(inPath: string, outPath: string, options: Levels & {rotate: number}): Promise<{hash: string}>;'''),
    (r'''  bridge().processImage(inPath, outPath, {...levels, rotate});''',
     r'''  bridge().processImageExact(inPath, outPath, {...levels, rotate});

export const renderPdfPage = (path: string, page: number, outPath: string) =>
  bridge().renderPdfPage(path, page, outPath);'''),
]

SCREEN_EDITS = [
    (r'''import {FileUtils, NativeUIUtils, PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';''',
     r'''import {FileUtils, NativeUIUtils, PluginFileAPI, PluginManager} from 'sn-plugin-lib';'''),
    (r'''  processImage,
''',
     r'''  processImage,
  renderPdfPage,
'''),
    (r'''type Raw = {path: string; dir: string; aspect: number};''',
     r'''type Raw = {path: string; dir: string; aspect: number; sourceBytes: number; sourceModified: number};'''),
    (r'''/** Height, in pixels, of the page picture: the same as the pages of the new PDF (the Manta screen's height). */
const PAGE_PIXELS = 2560;

''',
     r''''''),
    (r'''  const real = await readPageSize(path, page);
  // The picture has the page's own proportions and the height of the pages of the new PDF.
  const height = PAGE_PIXELS;
  const width = real ? Math.min(5120, Math.max(1, Math.round((height * real.w) / real.h))) : 1920;
  const out = `${dir}/preview_${Date.now()}.png`;
  const res: any = await (PluginDocAPI as any).generateCurrentDocImage(page, out, {width, height}, 0);
  if (!res?.success) {
    throw new Error(res?.error?.message ?? 'Could not draw the page.');
  }
  lastRawPath = out;
  return {path: out, dir, aspect: width / height};''',
     r'''  const out = `${dir}/preview_${Date.now()}.png`;
  const res = await renderPdfPage(path, page, out);
  lastRawPath = out;
  return {path: out, dir, aspect: res.pageWidth / res.pageHeight, sourceBytes: res.sourceBytes, sourceModified: res.sourceModified};'''),
    (r'''  const [updating, setUpdating] = useState(false);''',
     r'''  const [updating, setUpdating] = useState(false);
  const [proof, setProof] = useState<{key: string; hash: string} | null>(null);
  const [applied, setApplied] = useState(false);'''),
    (r'''  const degrees = skew === 'off' ? 0 : skew === 'auto' ? detected ?? 0 : manualDeg;''',
     r'''  const degrees = skew === 'off' ? 0 : skew === 'auto' ? detected ?? 0 : manualDeg;
  const previewKey = JSON.stringify([raw?.path, detected, widthLevel, heightLevel, darkLevel, sharpLevel, cleanLevel, skew, manualDeg, degrees]);'''),
    (r'''    if (!raw || !native || !loaded || busy) {''',
     r'''    if (!raw || !native || !loaded || busy || applied || (skew === 'auto' && detected === null)) {'''),
    (r'''    setUpdating(true);''',
     r'''    setProof(null);
    setUpdating(true);'''),
    (r'''        await processImage(''',
     r'''        const processed = await processImage('''),
    (r'''        setProcPath(out);''',
     r'''        setProcPath(out);
        setProof({key: previewKey, hash: processed.hash});'''),
    (r'''[raw, native, loaded, darkLevel, degrees, widthLevel, heightLevel, sharpLevel, cleanLevel, busy]);''',
     r'''[raw, native, loaded, darkLevel, degrees, widthLevel, heightLevel, sharpLevel, cleanLevel, busy, applied, detected, skew, previewKey]);'''),
    (r'''  const apply = async () => {
    if (!info || busy) {''',
     r'''  const apply = async () => {
    if (!info || busy || applied || updating || !raw || !proof || proof.key !== previewKey) {'''),
    (r'''        threads: THREADS,
        mode: skew,''',
     r'''        threads: THREADS,
        expectedBytes: raw.sourceBytes,
        expectedModified: raw.sourceModified,
        previewPage: info.current,
        previewHash: proof.hash,
        mode: skew,'''),
    (r'''      setHasBackup(true);''',
     r'''      setHasBackup(true);
      setApplied(true);
      setProof(null);'''),
    (r'''      setHasBackup(false);''',
     r'''      setHasBackup(false);
      setApplied(true);
      setProof(null);'''),
    (r'''      <Row label="Width">''',
     r'''      <View pointerEvents={busy || applied ? 'none' : 'auto'} style={{alignSelf: 'stretch'}}>
      <Row label="Width">'''),
    (r'''      <View style={styles.buttons}>''',
     r'''      </View>
      <View style={styles.buttons}>'''),
    (r'''(!native || busy) && styles.disabled''',
     r'''(!native || busy || applied || updating || !proof || proof.key !== previewKey) && styles.disabled'''),
    (r'''disabled={!native || busy}''',
     r'''disabled={(!native || busy || applied || updating || !proof || proof.key !== previewKey)}'''),
    (r'''{busy ? `Working ${progress}` : 'Apply to this file'}''',
     r'''{busy ? `Working ${progress}` : applied ? 'Reopen tool for a new preview' : 'Apply to this file'}'''),
]

def main():
    native_file = ROOT / "android/app/src/main/java/com/swipeswitch/PdfToolModule.kt"
    screen_file = ROOT / "ConvertScreen.tsx"
    bridge_file = ROOT / "src/pdftool.ts"
    n = native_file.read_text(encoding="utf-8")
    s = screen_file.read_text(encoding="utf-8")
    bridge_bytes = bridge_file.read_bytes()
    if "optimistic: Boolean" not in n or "|| busy) {" not in s:
        raise RuntimeError("Run tools/prepare_pdf_tool.py first")
    if blob(bridge_bytes) != BRIDGE_BLOB:
        raise RuntimeError("Bridge changed; review the exact-preview patch first")
    b = bridge_bytes.decode("utf-8")
    marker = "    private fun enhance("
    protected = n[n.index(marker):]
    for old, new in NATIVE_EDITS:
        n = replace_once(n, old, new)
    if n[n.index(marker):] != protected:
        raise RuntimeError("Image routines or PDF writer changed unexpectedly")
    for old, new in BRIDGE_EDITS:
        b = replace_once(b, old, new)
    start = s.index(START_MARKER)
    end = s.index("async function removeFile", start)
    s = s[:start] + s[end:]
    for old, new in SCREEN_EDITS:
        s = replace_once(s, old, new)
    icon = make_icon_png()
    native_file.write_bytes(n.encode("utf-8"))
    screen_file.write_bytes(s.encode("utf-8"))
    bridge_file.write_bytes(b.encode("utf-8"))
    (ROOT / "assets/icon_pdftool.png").write_bytes(icon)
    print("1.4.7 prepared: shared renderer, preview pixel proof, fresh-settings guard, bold icon 6.")

if __name__ == "__main__":
    main()
