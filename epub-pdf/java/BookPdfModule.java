package com.swipeswitch;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.os.Environment;
import android.text.Html;
import android.text.Layout;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AlignmentSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Xml;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * One layout engine for both the on-screen preview and the exported PDF.
 * A page is laid out once with Android's StaticLayout (Arabic shaping, bidi, justification) on a
 * Supernote Manta sized page (462 x 616 pt, 3:4). The same drawPage() paints it onto a Bitmap for the
 * preview and onto a PdfDocument page for the export, so the two cannot differ.
 */
public class BookPdfModule extends ReactContextBaseJavaModule {
  static final float PW = 462f;
  static final float PH = 616f;

  private final ExecutorService ex = Executors.newSingleThreadExecutor();
  private volatile boolean cancelled = false;

  // ---- book (parsed once per file) ----
  private String bookPath;
  private String bookTitle = "";
  private List<Block> blocks = new ArrayList<>();
  private long totalChars = 0;
  private long imageBytes = 0;

  // ---- layout (rebuilt when settings change) ----
  private List<Pg> pages = new ArrayList<>();
  private float marginL, marginT, contentW, contentH;
  private final Map<String, Typeface> typefaces = new HashMap<>();

  private static class Stop extends RuntimeException {
    Stop() { super("Cancelled"); }
  }

  private static class Block {
    Spanned text;
    boolean heading;
    boolean chapterStart;
    long offset;
    StaticLayout lay;
  }

  private static class Seg {
    int block, l0, l1;
    float y;
  }

  private static class Pg {
    List<Seg> segs = new ArrayList<>();
    long startOffset = 0;
  }

  private static class Settings {
    float size = 12f;
    float spacing = 1.4f;
    float sidePct = 7f;
    float vertPct = 6f;
    int weight = 1;
    String font = "default";
    String align = "justify";
    String dir = "auto";
    boolean chapterBreak = true;
  }

  public BookPdfModule(ReactApplicationContext context) {
    super(context);
  }

  @Override
  public String getName() {
    return "BookPdf";
  }

  private void check() {
    if (cancelled) throw new Stop();
  }

  private void emit(String stage, int done, int total) {
    try {
      WritableMap m = Arguments.createMap();
      m.putString("stage", stage);
      m.putInt("done", done);
      m.putInt("total", total);
      getReactApplicationContext()
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("BookPdfProgress", m);
    } catch (Throwable ignored) {
    }
  }

  private void fail(Promise p, Throwable t) {
    if (t instanceof Stop) {
      p.reject("E_CANCELLED", "Cancelled");
    } else if (t instanceof OutOfMemoryError) {
      p.reject("E_MEMORY", "Not enough memory for this book. Close other apps and try again.");
    } else {
      String msg = t.getMessage();
      p.reject("E_BOOK", msg == null ? t.getClass().getSimpleName() : msg, t);
    }
  }

  // =====================================================================
  // Small file helpers
  // =====================================================================

  @ReactMethod
  public void readText(final String path, final Promise promise) {
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          File f = new File(path);
          if (!f.isFile()) { promise.resolve(null); return; }
          FileInputStream in = new FileInputStream(f);
          try {
            promise.resolve(new String(readAll(in, 1 << 20), "UTF-8"));
          } finally { in.close(); }
        } catch (Throwable t) { promise.resolve(null); }
      }
    });
  }

  @ReactMethod
  public void writeText(final String path, final String text, final Promise promise) {
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          File f = new File(path);
          File dir = f.getParentFile();
          if (dir != null) dir.mkdirs();
          FileOutputStream out = new FileOutputStream(f);
          try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
          promise.resolve(true);
        } catch (Throwable t) { promise.resolve(false); }
      }
    });
  }

  @ReactMethod
  public void cancel(Promise promise) {
    cancelled = true;
    promise.resolve(true);
  }

  private static byte[] readAll(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) >= 0) {
      if (out.size() + n > limit) throw new IOException("A file inside the book is too large");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  // =====================================================================
  // Fonts
  // =====================================================================

  @ReactMethod
  public void listFonts(final Promise promise) {
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          List<File> found = new ArrayList<>();
          File root = Environment.getExternalStorageDirectory();
          String[] subs = {"Document/fonts", "Document/Fonts", "Fonts", "fonts", "Document"};
          List<String> seen = new ArrayList<>();
          for (String s : subs) {
            File d = new File(root, s);
            if (d.isDirectory()) scanFonts(d, 0, found, seen);
          }
          Collections.sort(found, new Comparator<File>() {
            @Override public int compare(File a, File b) {
              return a.getName().compareToIgnoreCase(b.getName());
            }
          });
          WritableArray arr = Arguments.createArray();
          for (File f : found) {
            WritableMap m = Arguments.createMap();
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            m.putString("name", dot > 0 ? n.substring(0, dot) : n);
            m.putString("path", f.getAbsolutePath());
            arr.pushMap(m);
          }
          promise.resolve(arr);
        } catch (Throwable t) { fail(promise, t); }
      }
    });
  }

  private void scanFonts(File dir, int depth, List<File> out, List<String> seen) {
    File[] list = dir.listFiles();
    if (list == null) return;
    for (File f : list) {
      if (f.isDirectory()) {
        if (depth < 3) scanFonts(f, depth + 1, out, seen);
      } else {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if ((n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))
            && !seen.contains(f.getAbsolutePath())) {
          seen.add(f.getAbsolutePath());
          out.add(f);
        }
      }
    }
  }

  private Typeface typeface(String font) {
    if (font == null || font.equals("default")) return Typeface.DEFAULT;
    if (font.equals("serif")) return Typeface.SERIF;
    if (font.equals("sans")) return Typeface.SANS_SERIF;
    if (font.equals("mono")) return Typeface.MONOSPACE;
    Typeface t = typefaces.get(font);
    if (t != null) return t;
    try {
      t = Typeface.createFromFile(font);
    } catch (Throwable e) {
      t = null;
    }
    if (t == null) t = Typeface.DEFAULT;
    typefaces.put(font, t);
    return t;
  }

  // =====================================================================
  // EPUB reading: spine -> formatted paragraphs (bold, italic, headings, images kept)
  // =====================================================================

  @ReactMethod
  public void loadBook(final String path, final Promise promise) {
    cancelled = false;
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          doLoad(path);
          WritableMap m = Arguments.createMap();
          m.putString("title", bookTitle);
          m.putInt("paragraphs", blocks.size());
          m.putDouble("chars", (double) totalChars);
          promise.resolve(m);
        } catch (Throwable t) { fail(promise, t); }
      }
    });
  }

  private static class Opf {
    String title = "";
    List<String> spine = new ArrayList<>();
  }

  private static String attr(XmlPullParser p, String name) {
    for (int i = 0; i < p.getAttributeCount(); i++) {
      String n = p.getAttributeName(i);
      if (n.equals(name) || n.endsWith(":" + name)) return p.getAttributeValue(i);
    }
    return null;
  }

  private static XmlPullParser newParser(byte[] bytes) throws Exception {
    XmlPullParser p = Xml.newPullParser();
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    p.setInput(new StringReader(decode(bytes)));
    return p;
  }

  private static String decode(byte[] b) {
    int off = 0;
    if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) off = 3;
    return new String(b, off, b.length - off, Charset.forName("UTF-8"));
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    } catch (Throwable t) { return s; }
  }

  private static String resolve(String baseEntry, String rel) {
    int cut = rel.indexOf('#');
    if (cut >= 0) rel = rel.substring(0, cut);
    cut = rel.indexOf('?');
    if (cut >= 0) rel = rel.substring(0, cut);
    String dir = "";
    int s = baseEntry.lastIndexOf('/');
    if (s >= 0) dir = baseEntry.substring(0, s + 1);
    String full = rel.startsWith("/") ? rel.substring(1) : dir + rel;
    List<String> parts = new ArrayList<>();
    for (String part : full.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
      else parts.add(part);
    }
    return TextUtils.join("/", parts);
  }

  private static ZipEntry entry(ZipFile zip, Map<String, String> lower, String name) {
    ZipEntry e = zip.getEntry(name);
    if (e != null) return e;
    String dec = urlDecode(name);
    e = zip.getEntry(dec);
    if (e != null) return e;
    String real = lower.get(dec.toLowerCase(Locale.ROOT));
    return real == null ? null : zip.getEntry(real);
  }

  private void doLoad(String path) throws Exception {
    File file = new File(path);
    if (!file.isFile()) throw new IOException("The book file was not found");
    List<Block> out = new ArrayList<>();
    long off = 0;
    imageBytes = 0;
    String title = "";
    ZipFile zip = new ZipFile(file);
    try {
      Map<String, String> lower = new HashMap<>();
      java.util.Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        String n = en.nextElement().getName();
        lower.put(n.toLowerCase(Locale.ROOT), n);
      }
      ZipEntry ce = zip.getEntry("META-INF/container.xml");
      if (ce == null) throw new IOException("This is not a valid EPUB (no container.xml)");
      String opfPath = null;
      XmlPullParser cp = newParser(readAll(zip.getInputStream(ce), 1 << 20));
      while (cp.next() != XmlPullParser.END_DOCUMENT) {
        if (cp.getEventType() == XmlPullParser.START_TAG && cp.getName().endsWith("rootfile")) {
          opfPath = attr(cp, "full-path");
          break;
        }
      }
      if (opfPath == null) throw new IOException("The EPUB package file was not found");
      ZipEntry oe = entry(zip, lower, opfPath);
      if (oe == null) throw new IOException("The EPUB package file is missing");
      Opf opf = parseOpf(readAll(zip.getInputStream(oe), 4 << 20), opfPath, zip, lower);
      title = opf.title;
      Resources res = getReactApplicationContext().getResources();
      int total = opf.spine.size();
      int idx = 0;
      for (String href : opf.spine) {
        check();
        emit("read", ++idx, total);
        ZipEntry he = entry(zip, lower, href);
        if (he == null) continue;
        String html;
        try {
          html = decode(readAll(zip.getInputStream(he), 16 << 20));
        } catch (IOException ioe) {
          continue;
        }
        Spanned sp = toSpanned(zip, lower, href, html, res);
        int n = sp.length();
        int s = 0;
        boolean first = true;
        while (s <= n) {
          int e = TextUtils.indexOf(sp, '\n', s);
          if (e < 0) e = n;
          int a = s, b = e;
          while (a < b && isWs(sp.charAt(a))) a++;
          while (b > a && isWs(sp.charAt(b - 1))) b--;
          if (b > a) {
            Block bl = new Block();
            SpannableStringBuilder para = new SpannableStringBuilder(sp, a, b);
            bl.text = para;
            bl.heading = isHeading(para);
            bl.chapterStart = first;
            bl.offset = off;
            first = false;
            off += (b - a) + 1;
            out.add(bl);
          }
          if (e >= n) break;
          s = e + 1;
        }
      }
    } finally {
      try { zip.close(); } catch (Throwable ignored) { }
    }
    if (out.isEmpty()) throw new IOException("No readable text was found in this book");
    blocks = out;
    totalChars = off;
    bookPath = path;
    bookTitle = title;
    pages = new ArrayList<>();
  }

  private static boolean isWs(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\u00A0' || c == '\u200B' || c == '\u2028' || c == '\u2029'
        || c == '\u3000' || c == '\f';
  }

  private static boolean isHeading(Spanned t) {
    int len = t.length();
    RelativeSizeSpan[] rs = t.getSpans(0, len, RelativeSizeSpan.class);
    for (RelativeSizeSpan r : rs) {
      if (r.getSizeChange() > 1.05f) return true;
    }
    AlignmentSpan[] as = t.getSpans(0, len, AlignmentSpan.class);
    for (AlignmentSpan a : as) {
      if (a.getAlignment() == Layout.Alignment.ALIGN_CENTER && len < 120) return true;
    }
    return false;
  }

  private Opf parseOpf(byte[] bytes, String opfPath, ZipFile zip, Map<String, String> lower) throws Exception {
    Opf o = new Opf();
    Map<String, String> manifest = new HashMap<>();
    List<String> idrefs = new ArrayList<>();
    XmlPullParser p = newParser(bytes);
    boolean inTitle = false;
    boolean titleDone = false;
    int ev;
    while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
      if (ev == XmlPullParser.START_TAG) {
        String n = p.getName();
        if (n.equals("item")) {
          String id = attr(p, "id");
          String href = attr(p, "href");
          if (id != null && href != null) manifest.put(id, resolve(opfPath, href));
        } else if (n.equals("itemref")) {
          String ref = attr(p, "idref");
          String linear = attr(p, "linear");
          if (ref != null && !"no".equalsIgnoreCase(linear)) idrefs.add(ref);
        } else if (n.endsWith("title") && !titleDone) {
          inTitle = true;
        }
      } else if (ev == XmlPullParser.TEXT && inTitle) {
        o.title = p.getText().trim();
        titleDone = true;
        inTitle = false;
      } else if (ev == XmlPullParser.END_TAG && inTitle) {
        inTitle = false;
      }
    }
    for (String ref : idrefs) {
      String href = manifest.get(ref);
      if (href != null) o.spine.add(href);
    }
    if (o.spine.isEmpty()) throw new IOException("The book has no readable chapters");
    return o;
  }

  private static final Pattern HEAD = Pattern.compile("(?is)<head\\b.*?</head>");
  private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
  private static final Pattern SVG_IMG = Pattern.compile("(?is)<image\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
  private static final Pattern SVG_TAG = Pattern.compile("(?is)</?svg\\b[^>]*>");
  private static final Pattern PROLOG = Pattern.compile("(?is)^\\s*(<\\?xml.*?\\?>)?\\s*(<!DOCTYPE[^>]*>)?");

  private Spanned toSpanned(final ZipFile zip, final Map<String, String> lower, final String chapter,
      String html, final Resources res) {
    String h = PROLOG.matcher(html).replaceFirst("");
    h = HEAD.matcher(h).replaceAll("");
    h = SCRIPT.matcher(h).replaceAll("");
    h = SVG_IMG.matcher(h).replaceAll("<img src=\"$1\">");
    h = SVG_TAG.matcher(h).replaceAll("");
    Html.ImageGetter ig = new Html.ImageGetter() {
      @Override public Drawable getDrawable(String src) {
        return loadImage(zip, lower, chapter, src, res);
      }
    };
    return Html.fromHtml(h, Html.FROM_HTML_MODE_COMPACT, ig, null);
  }

  private Drawable loadImage(ZipFile zip, Map<String, String> lower, String chapter, String src, Resources res) {
    Drawable blank = new ColorDrawable(0);
    blank.setBounds(0, 0, 1, 1);
    try {
      if (src == null || src.startsWith("data:") || src.startsWith("http")) return blank;
      ZipEntry e = entry(zip, lower, resolve(chapter, src));
      if (e == null) return blank;
      byte[] data = readAll(zip.getInputStream(e), 24 << 20);
      BitmapFactory.Options bo = new BitmapFactory.Options();
      bo.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, bo);
      if (bo.outWidth <= 0 || bo.outHeight <= 0) return blank;
      int sample = 1;
      while (bo.outWidth / sample > 1400 || bo.outHeight / sample > 1800) sample *= 2;
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      o.inPreferredConfig = Bitmap.Config.RGB_565;
      long cost = (long) (bo.outWidth / sample) * (bo.outHeight / sample) * 2L;
      if (imageBytes + cost > 160L * 1024 * 1024) return blank;
      Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
      if (bm == null) return blank;
      imageBytes += cost;
      BitmapDrawable d = new BitmapDrawable(res, bm);
      d.setBounds(0, 0, bo.outWidth, bo.outHeight); // real size; scaled to the page when laying out
      return d;
    } catch (Throwable t) {
      return blank;
    }
  }

  // =====================================================================
  // Layout (StaticLayout) and pagination
  // =====================================================================

  private Settings parseSettings(String json) throws Exception {
    Settings s = new Settings();
    JSONObject o = new JSONObject(json);
    s.size = (float) Math.max(6, Math.min(40, o.optDouble("size", s.size)));
    s.spacing = (float) Math.max(0.8, Math.min(3, o.optDouble("spacing", s.spacing)));
    s.sidePct = (float) Math.max(0, Math.min(25, o.optDouble("side", s.sidePct)));
    s.vertPct = (float) Math.max(0, Math.min(25, o.optDouble("vert", s.vertPct)));
    s.weight = Math.max(1, Math.min(10, o.optInt("weight", 1)));
    s.font = o.optString("font", "default");
    s.align = o.optString("align", "justify");
    s.dir = o.optString("dir", "auto");
    s.chapterBreak = o.optBoolean("chapterBreak", true);
    return s;
  }

  @ReactMethod
  public void layout(final String settingsJson, final double anchorOffset, final double anchorFraction,
      final Promise promise) {
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          cancelled = false;
          if (blocks.isEmpty()) throw new IOException("No book is loaded");
          Settings s = parseSettings(settingsJson);
          doLayout(s);
          long target;
          if (anchorOffset >= 0) target = (long) anchorOffset;
          else target = (long) (Math.max(0, Math.min(1, anchorFraction)) * totalChars);
          WritableMap m = Arguments.createMap();
          m.putInt("pages", pages.size());
          m.putInt("page", pageFor(target));
          promise.resolve(m);
        } catch (Throwable t) { fail(promise, t); }
      }
    });
  }

  private int pageFor(long target) {
    int best = 0;
    for (int i = 0; i < pages.size(); i++) {
      if (pages.get(i).startOffset <= target) best = i; else break;
    }
    return best;
  }

  private void doLayout(Settings s) {
    marginL = PW * s.sidePct / 100f;
    marginT = PH * s.vertPct / 100f;
    contentW = Math.max(120f, PW - 2 * marginL);
    contentH = Math.max(160f, PH - 2 * marginT);
    int w = Math.round(contentW);

    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.BLACK);
    paint.setTextSize(s.size);
    paint.setTypeface(typeface(s.font));
    paint.setLinearText(true);
    paint.setSubpixelText(true);
    if (s.weight > 1) {
      paint.setStyle(Paint.Style.FILL_AND_STROKE);
      paint.setStrokeWidth(s.size * 0.006f * (s.weight - 1));
    }

    TextDirectionHeuristic heur = TextDirectionHeuristics.FIRSTSTRONG_RTL;
    if (s.dir.equals("rtl")) heur = TextDirectionHeuristics.RTL;
    else if (s.dir.equals("ltr")) heur = TextDirectionHeuristics.LTR;

    int count = blocks.size();
    for (int i = 0; i < count; i++) {
      check();
      if ((i & 255) == 0) emit("layout", i, count);
      Block b = blocks.get(i);
      int len = b.text.length();
      fitImages(b.text, w);
      boolean rtl = heur.isRtl(b.text, 0, len);
      Layout.Alignment al = Layout.Alignment.ALIGN_NORMAL;
      boolean just = false;
      boolean centered = false;
      AlignmentSpan[] as = b.text.getSpans(0, len, AlignmentSpan.class);
      for (AlignmentSpan a : as) {
        if (a.getAlignment() == Layout.Alignment.ALIGN_CENTER) centered = true;
      }
      if (len == 1 && b.text.charAt(0) == '\uFFFC') centered = true; // a lone picture
      if (s.align.equals("center") || centered) al = Layout.Alignment.ALIGN_CENTER;
      else if (s.align.equals("right")) al = rtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
      else if (s.align.equals("left")) al = rtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
      else if (s.align.equals("justify") && !b.heading) just = true;
      StaticLayout.Builder bld = StaticLayout.Builder.obtain(b.text, 0, len, paint, w)
          .setAlignment(al)
          .setLineSpacing(0f, s.spacing)
          .setIncludePad(false)
          .setTextDirection(heur)
          .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
          .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
      if (Build.VERSION.SDK_INT >= 26) {
        bld.setJustificationMode(just ? Layout.JUSTIFICATION_MODE_INTER_WORD : Layout.JUSTIFICATION_MODE_NONE);
      }
      b.lay = bld.build();
    }
    paginate(s);
  }

  private void fitImages(Spanned t, int w) {
    ImageSpan[] is = t.getSpans(0, t.length(), ImageSpan.class);
    for (ImageSpan sp : is) {
      Drawable d = sp.getDrawable();
      if (d == null) continue;
      int iw = d.getIntrinsicWidth();
      int ih = d.getIntrinsicHeight();
      if (d instanceof BitmapDrawable) {
        Bitmap bm = ((BitmapDrawable) d).getBitmap();
        if (bm != null) { iw = bm.getWidth(); ih = bm.getHeight(); }
      }
      if (iw <= 1 || ih <= 1) continue;
      float sc = Math.min(0.5f, Math.min(w / (float) iw, contentH * 0.85f / ih));
      d.setBounds(0, 0, Math.max(1, Math.round(iw * sc)), Math.max(1, Math.round(ih * sc)));
    }
  }

  private void paginate(Settings s) {
    List<Pg> out = new ArrayList<>();
    Pg cur = new Pg();
    float y = 0f;
    boolean prevHeading = false;
    int count = blocks.size();
    for (int bi = 0; bi < count; bi++) {
      check();
      Block b = blocks.get(bi);
      StaticLayout L = b.lay;
      int nl = L.getLineCount();
      if (nl == 0) continue;
      if (b.chapterStart && s.chapterBreak && !cur.segs.isEmpty()) {
        out.add(cur);
        cur = new Pg();
        y = 0f;
      } else if (!cur.segs.isEmpty()) {
        y += b.heading ? s.size * 0.9f : (prevHeading ? s.size * 0.5f : s.size * 0.45f);
      } else {
        y = 0f;
      }
      int l0 = 0;
      while (l0 < nl) {
        float avail = contentH - y;
        float top0 = L.getLineTop(l0);
        int l1 = l0;
        while (l1 < nl && L.getLineBottom(l1) - top0 <= avail + 0.01f) l1++;
        int take = l1 - l0;
        boolean whole = l1 >= nl;
        boolean empty = cur.segs.isEmpty();
        if (!whole) {
          int rest = nl - l1;
          if (take >= 4 && rest == 1) take--;                 // avoid a single orphan line on the next page
          if (take < 2 && nl - l0 >= 3 && !empty) take = 0;   // avoid a single widow line at the bottom
          if (b.heading && !empty) take = 0;                  // headings are never split
        } else if (b.heading && !empty && bi + 1 < count) {
          Block nx = blocks.get(bi + 1);
          float need = L.getLineBottom(nl - 1) - top0 + s.size * 0.5f;
          if (nx.lay != null && nx.lay.getLineCount() > 0) {
            int k = Math.min(2, nx.lay.getLineCount());
            need += nx.lay.getLineBottom(k - 1);
          }
          if (need > avail) take = 0;                         // keep a heading with the text after it
        }
        if (take <= 0) {
          if (empty) {
            take = 1;
          } else {
            out.add(cur);
            cur = new Pg();
            y = 0f;
            continue;
          }
        }
        Seg sg = new Seg();
        sg.block = bi;
        sg.l0 = l0;
        sg.l1 = l0 + take;
        sg.y = y;
        if (cur.segs.isEmpty()) cur.startOffset = b.offset + L.getLineStart(l0);
        cur.segs.add(sg);
        y += L.getLineBottom(l0 + take - 1) - top0;
        l0 += take;
        if (l0 < nl) {
          out.add(cur);
          cur = new Pg();
          y = 0f;
        }
      }
      prevHeading = b.heading;
    }
    if (!cur.segs.isEmpty() || out.isEmpty()) out.add(cur);
    pages = out;
  }

  // =====================================================================
  // Drawing: the single code path used by the preview and by the PDF
  // =====================================================================

  private void drawPage(Canvas c, Pg pg) {
    for (Seg sg : pg.segs) {
      StaticLayout L = blocks.get(sg.block).lay;
      int nl = L.getLineCount();
      float top = L.getLineTop(sg.l0);
      float bot = L.getLineBottom(sg.l1 - 1);
      c.save();
      c.translate(marginL, marginT + sg.y - top);
      float ct = sg.l0 == 0 ? -2000f : top;
      float cb = sg.l1 >= nl ? 20000f : bot;
      c.clipRect(-8f, ct, contentW + 8f, cb);
      L.draw(c);
      c.restore();
    }
  }

  @ReactMethod
  public void renderPage(final int index, final String outPath, final double scale, final Promise promise) {
    ex.execute(new Runnable() {
      @Override public void run() {
        try {
          if (pages.isEmpty()) throw new IOException("Nothing is laid out yet");
          int i = Math.max(0, Math.min(pages.size() - 1, index));
          Pg pg = pages.get(i);
          float sc = (float) Math.max(0.5, Math.min(3, scale));
          int w = Math.round(PW * sc);
          int h = Math.round(PH * sc);
          Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
          try {
            Canvas c = new Canvas(bm);
            c.drawColor(Color.WHITE);
            c.scale(w / PW, h / PH);
            drawPage(c, pg);
            File f = new File(outPath);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            try { bm.compress(Bitmap.CompressFormat.PNG, 100, fo); } finally { fo.close(); }
          } finally { bm.recycle(); }
          WritableMap m = Arguments.createMap();
          m.putInt("page", i);
          m.putInt("pages", pages.size());
          m.putDouble("offset", (double) pg.startOffset);
          m.putDouble("fraction", totalChars > 0 ? pg.startOffset / (double) totalChars : 0);
          m.putInt("width", w);
          m.putInt("height", h);
          promise.resolve(m);
        } catch (Throwable t) { fail(promise, t); }
      }
    });
  }

  @ReactMethod
  public void exportPdf(final String outPath, final Promise promise) {
    cancelled = false;
    ex.execute(new Runnable() {
      @Override public void run() {
        long t0 = System.currentTimeMillis();
        File part = null;
        PdfDocument doc = null;
        try {
          if (pages.isEmpty()) throw new IOException("Nothing is laid out yet");
          File target = uniqueTarget(new File(outPath));
          File dir = target.getParentFile();
          if (dir != null) dir.mkdirs();
          part = new File(target.getPath() + ".part");
          doc = new PdfDocument();
          int total = pages.size();
          for (int i = 0; i < total; i++) {
            check();
            PdfDocument.PageInfo info =
                new PdfDocument.PageInfo.Builder(Math.round(PW), Math.round(PH), i + 1).create();
            PdfDocument.Page page = doc.startPage(info);
            drawPage(page.getCanvas(), pages.get(i));
            doc.finishPage(page);
            if ((i & 7) == 0 || i == total - 1) emit("export", i + 1, total);
          }
          FileOutputStream fo = new FileOutputStream(part);
          try { doc.writeTo(fo); } finally { fo.close(); }
          doc.close();
          doc = null;
          if (!part.renameTo(target)) throw new IOException("Could not save the PDF");
          part = null;
          WritableMap m = Arguments.createMap();
          m.putString("path", target.getAbsolutePath());
          m.putInt("pages", total);
          m.putDouble("bytes", (double) target.length());
          m.putDouble("ms", (double) (System.currentTimeMillis() - t0));
          promise.resolve(m);
        } catch (Throwable t) {
          fail(promise, t);
        } finally {
          if (doc != null) { try { doc.close(); } catch (Throwable ignored) { } }
          if (part != null) { try { part.delete(); } catch (Throwable ignored) { } }
        }
      }
    });
  }

  /** Never overwrites an existing file: adds " (2)", " (3)" ... */
  private static File uniqueTarget(File f) {
    if (!f.exists()) return f;
    String n = f.getName();
    int dot = n.lastIndexOf('.');
    String stem = dot > 0 ? n.substring(0, dot) : n;
    String ext = dot > 0 ? n.substring(dot) : "";
    for (int k = 2; k < 1000; k++) {
      File c = new File(f.getParentFile(), stem + " (" + k + ")" + ext);
      if (!c.exists()) return c;
    }
    return f;
  }
}
