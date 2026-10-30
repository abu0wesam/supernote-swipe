package com.swipeswitch

import android.util.AtomicFile
import android.util.Xml
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** EPUB formatting for the Text tool: writes a separately named copy; the original is never changed. */
class EpubToolModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  override fun getName(): String = "EpubTool"

  private val active = AtomicBoolean(false)
  @Volatile private var cancelled = false
  private val profileLock = Any()

  private class Stopped : RuntimeException("Cancelled")
  private class Item(val href: String, val type: String)
  private class Plan(val chapters: Set<String>)

  private fun checkStop() {
    if (cancelled) throw Stopped()
  }

  private fun bounded(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(65536)
    while (true) {
      if (active.get()) checkStop()
      val n = input.read(buf)
      if (n < 0) break
      require(out.size().toLong() + n <= limit) { "A text chapter exceeds the safe size limit" }
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun read(zip: ZipFile, name: String, limit: Int = 2 * 1024 * 1024): ByteArray {
    val e = zip.getEntry(name) ?: error("Missing EPUB resource: $name")
    return zip.getInputStream(e).use { bounded(it, limit) }
  }

  private fun parse(bytes: ByteArray, visit: (XmlPullParser) -> Unit) {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    p.setInput(ByteArrayInputStream(bytes), null)
    while (p.eventType != XmlPullParser.END_DOCUMENT) {
      if (p.eventType == XmlPullParser.START_TAG) visit(p)
      p.next()
    }
  }

  private fun attr(p: XmlPullParser, name: String): String {
    for (i in 0 until p.attributeCount) {
      if (p.getAttributeName(i) == name) return p.getAttributeValue(i)
    }
    return ""
  }

  private fun resolve(base: String, href: String): String {
    val value = java.net.URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
    require(!value.startsWith("/") && !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) { "External chapter URI is unsupported" }
    val parts = ArrayList<String>()
    val prefix = base.substringBeforeLast('/', "")
    for (part in ((if (prefix.isEmpty()) "" else "$prefix/") + value).split('/')) {
      if (part.isEmpty() || part == ".") continue
      if (part == "..") {
        require(parts.isNotEmpty()) { "Invalid EPUB path" }
        parts.removeAt(parts.lastIndex)
      } else {
        parts.add(part)
      }
    }
    return parts.joinToString("/")
  }

  private fun plan(zip: ZipFile): Plan {
    val entries = Collections.list(zip.entries())
    require(entries.size <= 20000) { "Too many EPUB resources" }
    require(entries.map { it.name }.toSet().size == entries.size) { "Duplicate ZIP entries are unsupported" }
    require(String(read(zip, "mimetype", 64), Charsets.US_ASCII).trim() == "application/epub+zip") { "Not an EPUB container" }
    require(zip.getEntry("META-INF/rights.xml") == null && zip.getEntry("META-INF/signatures.xml") == null) { "Protected or signed EPUB is not supported" }
    if (zip.getEntry("META-INF/encryption.xml") != null) {
      parse(read(zip, "META-INF/encryption.xml")) { p ->
        if (p.name == "EncryptionMethod") {
          require(attr(p, "Algorithm") in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) { "Encrypted EPUB content is not supported" }
        }
      }
    }
    val roots = ArrayList<String>()
    parse(read(zip, "META-INF/container.xml")) { p ->
      if (p.name == "rootfile") roots.add(resolve("", attr(p, "full-path")))
    }
    require(roots.size == 1) { "Only single-rendition EPUB is supported in this version" }
    val opf = roots[0]
    val manifest = HashMap<String, Item>()
    val spine = ArrayList<String>()
    parse(read(zip, opf)) { p ->
      val tag = p.name
      if (tag == "item") {
        manifest[attr(p, "id")] = Item(attr(p, "href"), attr(p, "media-type"))
      } else if (tag == "itemref") {
        require(!attr(p, "properties").split(Regex("\\s+")).contains("rendition:layout-pre-paginated")) { "Fixed-layout EPUB is not supported" }
        spine.add(attr(p, "idref"))
      } else if (tag == "meta") {
        if (attr(p, "property") == "rendition:layout") {
          require(p.nextText().trim() == "reflowable") { "Fixed-layout EPUB is not supported" }
        }
        if (attr(p, "name") == "fixed-layout") {
          require(attr(p, "content").lowercase(Locale.ROOT) != "true") { "Fixed-layout EPUB is not supported" }
        }
      }
    }
    require(spine.isNotEmpty()) { "No reading-order chapters" }
    val chapters = HashSet<String>()
    for (id in spine) {
      val item = manifest[id] ?: error("Missing spine resource")
      require(item.type == "application/xhtml+xml") { "Only reflowable XHTML chapters are supported" }
      val path = resolve(opf, item.href)
      require(zip.getEntry(path) != null) { "Missing chapter: $path" }
      chapters.add(path)
    }
    return Plan(chapters)
  }

  private fun patch(bytes: ByteArray, css: String): ByteArray {
    val charset: Charset = when {
      bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
      bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
      else -> Charsets.UTF_8
    }
    var text = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    val encoding = Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(text.take(256))?.groupValues?.get(1)
    require(encoding == null || encoding.uppercase(Locale.ROOT) in setOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE")) { "Unsupported XHTML encoding" }
    text = Regex("(?s)<!--PDFTOOL_TEXT_START-->.*?<!--PDFTOOL_TEXT_END-->\\s*").replace(text, "")
    val tokens = Regex("(?is)<!--.*?-->|<!\\[CDATA\\[.*?\\]\\]>|</(?:[A-Za-z_][\\w.-]*:)?head\\s*>")
    val head = tokens.findAll(text).firstOrNull { it.value.startsWith("</") } ?: error("Chapter has no closing head element")
    val style = "<!--PDFTOOL_TEXT_START--><style xmlns=\"http://www.w3.org/1999/xhtml\" type=\"text/css\" id=\"pdftool-text-v1\">$css</style><!--PDFTOOL_TEXT_END-->\n"
    return (text.substring(0, head.range.first) + style + text.substring(head.range.first)).toByteArray(charset)
  }

  @ReactMethod
  fun inspect(path: String, promise: Promise) {
    if (active.get()) {
      promise.reject("E_BUSY", "An EPUB job is running")
      return
    }
    Thread {
      try {
        ZipFile(File(path)).use { zip ->
          val p = plan(zip)
          val result = Arguments.createMap()
          result.putInt("chapters", p.chapters.size)
          promise.resolve(result)
        }
      } catch (e: Throwable) {
        promise.reject("E_EPUB_INSPECT", e.message ?: e.toString())
      }
    }.start()
  }

  @ReactMethod
  fun exportCopy(path: String, outputPath: String, css: String, promise: Promise) {
    if (!active.compareAndSet(false, true)) {
      promise.reject("E_BUSY", "An EPUB job is already running")
      return
    }
    cancelled = false
    Thread {
      var temp: File? = null
      try {
        require(css.isNotBlank() && css.length <= 8192 &&
            !Regex("[<>&]|@import|url\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(css)) { "Invalid formatting rules" }
        val src = File(path).canonicalFile
        val out = File(outputPath).canonicalFile
        require(src != out && src.parentFile == out.parentFile && !out.exists()) { "Output must be a new file beside the original" }
        val sourceSize = src.length()
        val sourceTime = src.lastModified()
        val work = File.createTempFile("text_tool_", ".epub", out.parentFile)
        temp = work
        var count = 0
        ZipFile(src).use { zip ->
          val p = plan(zip)
          ZipOutputStream(BufferedOutputStream(FileOutputStream(work))).use { output ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val first = ZipEntry("mimetype")
            first.method = ZipEntry.STORED
            first.size = mime.size.toLong()
            first.compressedSize = first.size
            first.crc = CRC32().apply { update(mime) }.value
            output.putNextEntry(first)
            output.write(mime)
            output.closeEntry()
            for (entry in Collections.list(zip.entries())) {
              checkStop()
              if (entry.name == "mimetype") continue
              val next = ZipEntry(entry.name)
              if (entry.time >= 0) next.time = entry.time
              output.putNextEntry(next)
              if (!entry.isDirectory) {
                if (entry.name in p.chapters) {
                  val data = zip.getInputStream(entry).use { bounded(it, 8 * 1024 * 1024) }
                  output.write(patch(data, css))
                  count++
                } else {
                  zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                      checkStop()
                      val n = input.read(buffer)
                      if (n < 0) break
                      output.write(buffer, 0, n)
                    }
                  }
                }
              }
              output.closeEntry()
            }
            check(count == p.chapters.size) { "Chapter count mismatch" }
          }
        }
        checkStop()
        check(src.length() == sourceSize && src.lastModified() == sourceTime) { "Source changed while formatting" }
        ZipFile(work).use { check(plan(it).chapters.size == count) { "Output validation failed" } }
        checkStop()
        require(android.os.Build.VERSION.SDK_INT >= 26) { "EPUB export requires Android 8 or later" }
        java.nio.file.Files.move(work.toPath(), out.toPath())
        temp = null
        val result = Arguments.createMap()
        result.putString("path", out.path)
        result.putInt("chapters", count)
        result.putDouble("bytes", out.length().toDouble())
        promise.resolve(result)
      } catch (e: Stopped) {
        promise.reject("E_CANCELLED", "Cancelled; original unchanged")
      } catch (e: Throwable) {
        promise.reject("E_EPUB_EXPORT", e.message ?: e.toString())
      } finally {
        temp?.delete()
        active.set(false)
      }
    }.start()
  }

  @ReactMethod
  fun cancel(promise: Promise) {
    cancelled = true
    promise.resolve(true)
  }

  private fun profile(dir: String, path: String): File {
    val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 255) }
    return File(File(dir, "settings/text"), "$hash.json")
  }

  @ReactMethod
  fun loadProfile(dir: String, path: String, promise: Promise) {
    try {
      synchronized(profileLock) {
        val file = profile(dir, path)
        require(!file.exists() || file.length() <= 4096) { "Profile is too large" }
        promise.resolve(if (file.exists()) AtomicFile(file).openRead().use { String(it.readBytes(), Charsets.UTF_8) } else null)
      }
    } catch (e: Throwable) {
      promise.reject("E_TEXT_PROFILE", e.message ?: e.toString())
    }
  }

  @ReactMethod
  fun saveProfile(dir: String, path: String, json: String, promise: Promise) {
    try {
      synchronized(profileLock) {
        require(json.length <= 4096) { "Profile is too large" }
        JSONObject(json)
        val file = profile(dir, path)
        val parent = file.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create the profile folder" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
          stream.write(json.toByteArray(Charsets.UTF_8))
          atomic.finishWrite(stream)
        } catch (e: Throwable) {
          atomic.failWrite(stream)
          throw e
        }
        promise.resolve(true)
      }
    } catch (e: Throwable) {
      promise.reject("E_TEXT_PROFILE", e.message ?: e.toString())
    }
  }
}
