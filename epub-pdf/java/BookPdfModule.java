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
import android.text.style.URLSpan;
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
  private int[] blockPage = new int[0];
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
    List<String> anchors;
    boolean rtl;
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
    String nav = null;
    String ncx = null;
  }

  private static class TocItem {
    String title;
    int level;
    String file;
    String anchor;
    int block = -1;
  }

  private static class Css {
    final Map<String, Integer> m = new HashMap<>();
  }

  static final int F_B = 1, F_I = 2, F_U = 4, F_C = 8, F_BIG = 16, F_SMALL = 32;

  private final Map<String, Css> cssCache = new HashMap<>();
  private List<TocItem> toc = new ArrayList<>();

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

  private static String fragment(String href) {
    int c = href.indexOf('#');
    return c >= 0 ? urlDecode(href.substring(c + 1)) : "";
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

  private static class Chapter {
    List<Block> blocks = new ArrayList<>();
  }

  private void doLoad(String path) throws Exception {
    File file = new File(path);
    if (!file.isFile()) throw new IOException("The book file was not found");
    imageBytes = 0;
    cssCache.clear();
    String title = "";
    final List<Block> out = new ArrayList<>();
    final ZipFile zip = new ZipFile(file);
    ExecutorService pool = null;
    try {
      final Map<String, String> lower = new HashMap<>();
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
      Opf opf = parseOpf(readAll(zip.getInputStream(oe), 4 << 20), opfPath);
      title = opf.title;

      // Table of contents (EPUB 3 nav document, else EPUB 2 NCX)
      List<TocItem> items = new ArrayList<>();
      try {
        if (opf.nav != null) {
          ZipEntry ne = entry(zip, lower, opf.nav);
          if (ne != null) items = parseNav(readAll(zip.getInputStream(ne), 4 << 20), opf.nav);
        }
      } catch (Throwable ignored) { items = new ArrayList<>(); }
      if (items.isEmpty() && opf.ncx != null) {
        try {
          ZipEntry ne = entry(zip, lower, opf.ncx);
          if (ne != null) items = parseNcx(readAll(zip.getInputStream(ne), 4 << 20), opf.ncx);
        } catch (Throwable ignored) { items = new ArrayList<>(); }
      }
      final Map<String, List<String>> anchorsByFile = new HashMap<>();
      for (TocItem t : items) {
        if (t.anchor.isEmpty()) continue;
        List<String> l = anchorsByFile.get(t.file);
        if (l == null) { l = new ArrayList<>(); anchorsByFile.put(t.file, l); }
        if (!l.contains(t.anchor)) l.add(t.anchor);
      }

      final Resources res = getReactApplicationContext().getResources();
      final int total = opf.spine.size();
      pool = Executors.newFixedThreadPool(3);
      List<java.util.concurrent.Future<Chapter>> futures = new ArrayList<>();
      final int[] done = {0};
      for (final String href : opf.spine) {
        futures.add(pool.submit(new java.util.concurrent.Callable<Chapter>() {
          @Override public Chapter call() throws Exception {
            check();
            Chapter c = parseChapter(zip, lower, href, res, anchorsByFile.get(href));
            int d;
            synchronized (done) { d = ++done[0]; }
            emit("read", d, total);
            return c;
          }
        }));
      }
      long off = 0;
      Map<String, Integer> fileFirst = new HashMap<>();
      Map<String, Integer> anchorBlock = new HashMap<>();
      for (int i = 0; i < futures.size(); i++) {
        Chapter c;
        try {
          c = futures.get(i).get();
        } catch (java.util.concurrent.ExecutionException ee) {
          Throwable cause = ee.getCause();
          if (cause instanceof Stop) throw (Stop) cause;
          if (cause instanceof OutOfMemoryError) throw (OutOfMemoryError) cause;
          continue; // an unreadable chapter is skipped, the rest of the book still loads
        }
        String href = opf.spine.get(i);
        boolean first = true;
        for (Block b : c.blocks) {
          b.chapterStart = first;
          if (first && !fileFirst.containsKey(href)) fileFirst.put(href, out.size());
          first = false;
          b.offset = off;
          off += b.text.length() + 1;
          if (b.anchors != null) {
            for (String a : b.anchors) anchorBlock.put(href + "#" + a, out.size());
          }
          out.add(b);
        }
      }
      if (out.isEmpty()) throw new IOException("No readable text was found in this book");
      List<TocItem> resolved = new ArrayList<>();
      for (TocItem t : items) {
        Integer bi = t.anchor.isEmpty() ? null : anchorBlock.get(t.file + "#" + t.anchor);
        if (bi == null) bi = fileFirst.get(t.file);
        if (bi == null) continue;
        t.block = bi;
        resolved.add(t);
      }
      toc = resolved;
      blocks = out;
      totalChars = off;
      bookPath = path;
      bookTitle = title;
      pages = new ArrayList<>();
    } finally {
      if (pool != null) pool.shutdownNow();
      try { zip.close(); } catch (Throwable ignored) { }
    }
  }

  private Opf parseOpf(byte[] bytes, String opfPath) throws Exception {
    Opf o = new Opf();
    Map<String, String> manifest = new HashMap<>();
    Map<String, String> types = new HashMap<>();
    List<String> idrefs = new ArrayList<>();
    String tocId = null;
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
          if (id != null && href != null) {
            String full = resolve(opfPath, href);
            manifest.put(id, full);
            String mt = attr(p, "media-type");
            if (mt != null) types.put(id, mt);
            String props = attr(p, "properties");
            if (props != null && (" " + props + " ").contains(" nav ")) o.nav = full;
          }
        } else if (n.equals("itemref")) {
          String ref = attr(p, "idref");
          String linear = attr(p, "linear");
          if (ref != null && !"no".equalsIgnoreCase(linear)) idrefs.add(ref);
        } else if (n.equals("spine")) {
          tocId = attr(p, "toc");
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
    if (tocId != null && manifest.containsKey(tocId)) o.ncx = manifest.get(tocId);
    if (o.ncx == null) {
      for (Map.Entry<String, String> e : types.entrySet()) {
        if ("application/x-dtbncx+xml".equals(e.getValue())) { o.ncx = manifest.get(e.getKey()); break; }
      }
    }
    for (String ref : idrefs) {
      String href = manifest.get(ref);
      if (href != null) o.spine.add(href);
    }
    if (o.spine.isEmpty()) throw new IOException("The book has no readable chapters");
    return o;
  }

  private List<TocItem> parseNav(byte[] bytes, String navPath) throws Exception {
    List<TocItem> out = new ArrayList<>();
    XmlPullParser p = newParser(bytes);
    boolean inToc = false;
    int navDepth = 0;
    int olDepth = 0;
    TocItem cur = null;
    StringBuilder txt = null;
    int ev;
    while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
      if (ev == XmlPullParser.START_TAG) {
        String n = p.getName();
        if (n.equals("nav")) {
          String ty = attr(p, "type");
          if (!inToc && ty != null && ty.contains("toc")) { inToc = true; navDepth = 1; olDepth = 0; }
          else if (inToc) navDepth++;
        } else if (inToc && n.equals("ol")) {
          olDepth++;
        } else if (inToc && n.equals("a")) {
          String href = attr(p, "href");
          cur = new TocItem();
          cur.level = Math.max(1, olDepth);
          if (href != null && !href.startsWith("http")) {
            cur.file = resolve(navPath, href);
            cur.anchor = fragment(href);
          } else cur.file = null;
          txt = new StringBuilder();
        }
      } else if (ev == XmlPullParser.TEXT) {
        if (txt != null) txt.append(p.getText());
      } else if (ev == XmlPullParser.END_TAG) {
        String n = p.getName();
        if (inToc && n.equals("a") && cur != null) {
          String t = txt.toString().replaceAll("\\s+", " ").trim();
          if (cur.file != null && !t.isEmpty()) { cur.title = t; out.add(cur); }
          cur = null;
          txt = null;
        } else if (inToc && n.equals("ol")) {
          olDepth--;
        } else if (inToc && n.equals("nav")) {
          navDepth--;
          if (navDepth <= 0) inToc = false;
        }
      }
    }
    return out;
  }

  private List<TocItem> parseNcx(byte[] bytes, String ncxPath) throws Exception {
    List<TocItem> out = new ArrayList<>();
    XmlPullParser p = newParser(bytes);
    int depth = 0;
    List<TocItem> stack = new ArrayList<>();
    boolean inLabel = false;
    int ev;
    StringBuilder txt = null;
    while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
      if (ev == XmlPullParser.START_TAG) {
        String n = p.getName();
        if (n.equals("navPoint")) {
          depth++;
          TocItem t = new TocItem();
          t.level = depth;
          t.title = "";
          t.file = null;
          t.anchor = "";
          stack.add(t);
          out.add(t);
        } else if (n.equals("navLabel")) {
          inLabel = true;
        } else if (n.equals("text") && inLabel) {
          txt = new StringBuilder();
        } else if (n.equals("content") && !stack.isEmpty()) {
          String src = attr(p, "src");
          TocItem t = stack.get(stack.size() - 1);
          if (src != null) { t.file = resolve(ncxPath, src); t.anchor = fragment(src); }
        }
      } else if (ev == XmlPullParser.TEXT) {
        if (txt != null) txt.append(p.getText());
      } else if (ev == XmlPullParser.END_TAG) {
        String n = p.getName();
        if (n.equals("text") && txt != null && !stack.isEmpty()) {
          TocItem t = stack.get(stack.size() - 1);
          if (t.title.isEmpty()) t.title = txt.toString().replaceAll("\\s+", " ").trim();
          txt = null;
        } else if (n.equals("navLabel")) {
          inLabel = false;
        } else if (n.equals("navPoint")) {
          depth--;
          if (!stack.isEmpty()) stack.remove(stack.size() - 1);
        }
      }
    }
    List<TocItem> good = new ArrayList<>();
    for (TocItem t : out) if (t.file != null && t.title != null && !t.title.isEmpty()) good.add(t);
    return good;
  }

  // ---- chapter -> formatted paragraphs ----

  private static final Pattern HEAD = Pattern.compile("(?is)<head\\b.*?</head>");
  private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
  private static final Pattern SVG_IMG = Pattern.compile("(?is)<image\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
  private static final Pattern SVG_TAG = Pattern.compile("(?is)</?svg\\b[^>]*>");
  private static final Pattern PROLOG = Pattern.compile("(?is)^\\s*(<\\?xml.*?\\?>)?\\s*(<!DOCTYPE[^>]*>)?");
  private static final Pattern LINK_CSS = Pattern.compile("(?is)<link\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
  private static final Pattern STYLE_BLOCK = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
  private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9:_-]*)([^>]*?)(/?)>");
  private static final Pattern A_CLASS = Pattern.compile("(?is)(?<![\\w:-])class\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern A_STYLE = Pattern.compile("(?is)(?<![\\w:-])style\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern A_ID = Pattern.compile("(?is)(?<![\\w:-])(?:id|name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern MARK = Pattern.compile("\uE000(\\d+)\uE001");
  private static final Pattern CSS_RULE = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
  private static final Pattern CSS_SEL = Pattern.compile("^(\\*|[a-zA-Z][\\w-]*)?(\\.[\\w-]+)?$");
  private static final String VOID_TAGS = ",br,hr,img,meta,link,input,col,area,base,wbr,";

  private static int declFlags(String body) {
    int f = 0;
    for (String d : body.split(";")) {
      int c = d.indexOf(':');
      if (c < 0) continue;
      String k = d.substring(0, c).trim().toLowerCase(Locale.ROOT);
      String v = d.substring(c + 1).trim().toLowerCase(Locale.ROOT).replace("!important", "").trim();
      if (k.equals("font-weight")) {
        if (v.startsWith("bold")) f |= F_B;
        else if (v.matches("[6-9]00")) f |= F_B;
      } else if (k.equals("font-style")) {
        if (v.startsWith("italic") || v.startsWith("oblique")) f |= F_I;
      } else if (k.equals("text-decoration") || k.equals("text-decoration-line")) {
        if (v.contains("underline")) f |= F_U;
      } else if (k.equals("text-align")) {
        if (v.startsWith("center")) f |= F_C;
      } else if (k.equals("font-size")) {
        float r = sizeRatio(v);
        if (r >= 1.15f) f |= F_BIG;
        else if (r > 0 && r <= 0.87f) f |= F_SMALL;
      }
    }
    return f;
  }

  private static float sizeRatio(String v) {
    try {
      if (v.equals("larger") || v.equals("large")) return 1.2f;
      if (v.equals("x-large") || v.equals("xx-large")) return 1.5f;
      if (v.equals("smaller") || v.equals("small")) return 0.85f;
      if (v.equals("x-small") || v.equals("xx-small")) return 0.7f;
      Matcher m = Pattern.compile("^([0-9.]+)\\s*(em|rem|%|px|pt)?$").matcher(v);
      if (!m.matches()) return -1f;
      float n = Float.parseFloat(m.group(1));
      String u = m.group(2);
      if (u == null) return -1f;
      if (u.equals("em") || u.equals("rem")) return n;
      if (u.equals("%")) return n / 100f;
      if (u.equals("px")) return n / 16f;
      return n / 12f;
    } catch (Throwable t) { return -1f; }
  }

  private Css parseCss(String text, Css into) {
    String t = text.replaceAll("(?s)/\\*.*?\\*/", "");
    Matcher m = CSS_RULE.matcher(t);
    while (m.find()) {
      int f = declFlags(m.group(2));
      if (f == 0) continue;
      for (String sel : m.group(1).split(",")) {
        String s = sel.trim();
        if (s.isEmpty() || s.startsWith("@")) continue;
        Matcher sm = CSS_SEL.matcher(s);
        if (!sm.matches()) continue;
        String tag = sm.group(1) == null || sm.group(1).equals("*") ? "" : sm.group(1).toLowerCase(Locale.ROOT);
        String cls = sm.group(2) == null ? "" : sm.group(2);
        String key = tag + cls;
        if (key.isEmpty()) continue;
        Integer old = into.m.get(key);
        into.m.put(key, (old == null ? 0 : old) | f);
      }
    }
    return into;
  }

  private Css chapterCss(ZipFile zip, Map<String, String> lower, String chapter, String html) {
    Css css = new Css();
    Matcher lm = LINK_CSS.matcher(html);
    while (lm.find()) {
      String href = lm.group(1);
      if (!href.toLowerCase(Locale.ROOT).contains(".css")) continue;
      String path = resolve(chapter, href);
      Css one;
      synchronized (cssCache) { one = cssCache.get(path); }
      if (one == null) {
        one = new Css();
        try {
          ZipEntry e = entry(zip, lower, path);
          if (e != null) parseCss(decode(readAll(zip.getInputStream(e), 4 << 20)), one);
        } catch (Throwable ignored) { }
        synchronized (cssCache) { cssCache.put(path, one); }
      }
      for (Map.Entry<String, Integer> en : one.m.entrySet()) {
        Integer old = css.m.get(en.getKey());
        css.m.put(en.getKey(), (old == null ? 0 : old) | en.getValue());
      }
    }
    Matcher sm = STYLE_BLOCK.matcher(html);
    while (sm.find()) parseCss(sm.group(1), css);
    return css;
  }

  private static class Open {
    String name;
    String inner;
    String outer;
  }

  /** Re-writes the markup so classes and inline styles that mean bold, italic, underline, centre or size become tags Html understands. */
  private static String applyStyles(String html, Css css, List<String> anchorIds) {
    StringBuilder out = new StringBuilder(html.length() + html.length() / 8);
    List<Open> stack = new ArrayList<>();
    Matcher m = TAG.matcher(html);
    int last = 0;
    while (m.find()) {
      out.append(html, last, m.start());
      last = m.end();
      boolean closing = m.group(1).length() > 0;
      String name = m.group(2).toLowerCase(Locale.ROOT);
      String attrs = m.group(3);
      boolean selfClose = m.group(4).length() > 0;
      boolean isVoid = VOID_TAGS.contains("," + name + ",");
      if (closing) {
        int idx = -1;
        for (int i = stack.size() - 1; i >= 0; i--) {
          if (stack.get(i).name.equals(name)) { idx = i; break; }
        }
        if (idx < 0) { out.append(m.group()); continue; }
        while (stack.size() - 1 > idx) {
          Open o = stack.remove(stack.size() - 1);
          out.append(o.inner).append(o.outer);
        }
        Open o = stack.remove(stack.size() - 1);
        out.append(o.inner).append(m.group()).append(o.outer);
        continue;
      }
      int f = 0;
      if (css != null && !css.m.isEmpty()) {
        Integer tf = css.m.get(name);
        if (tf != null) f |= tf;
        Matcher cm = A_CLASS.matcher(attrs);
        if (cm.find()) {
          String cv = cm.group(1) != null ? cm.group(1) : cm.group(2);
          for (String c : cv.trim().split("\\s+")) {
            if (c.isEmpty()) continue;
            Integer a = css.m.get("." + c);
            if (a != null) f |= a;
            Integer b = css.m.get(name + "." + c);
            if (b != null) f |= b;
          }
        }
      }
      Matcher stm = A_STYLE.matcher(attrs);
      if (stm.find()) f |= declFlags(stm.group(1) != null ? stm.group(1) : stm.group(2));
      String marker = "";
      if (anchorIds != null && !anchorIds.isEmpty()) {
        Matcher im = A_ID.matcher(attrs);
        while (im.find()) {
          String id = im.group(1) != null ? im.group(1) : im.group(2);
          int k = anchorIds.indexOf(id);
          if (k >= 0) marker = "\uE000" + k + "\uE001";
        }
      }
      String innerOpen = "", innerClose = "", outerOpen = "", outerClose = "";
      if ((f & F_C) != 0) { outerOpen = "<div style=\"text-align:center\">"; outerClose = "</div>"; }
      if ((f & F_B) != 0 && !name.equals("b") && !name.equals("strong") && !name.matches("h[1-6]")) {
        innerOpen += "<b>"; innerClose = "</b>" + innerClose;
      }
      if ((f & F_I) != 0 && !name.equals("i") && !name.equals("em")) { innerOpen += "<i>"; innerClose = "</i>" + innerClose; }
      if ((f & F_U) != 0 && !name.equals("u") && !name.equals("a")) { innerOpen += "<u>"; innerClose = "</u>" + innerClose; }
      if ((f & F_BIG) != 0 && !name.matches("h[1-6]")) { innerOpen += "<big>"; innerClose = "</big>" + innerClose; }
      else if ((f & F_SMALL) != 0) { innerOpen += "<small>"; innerClose = "</small>" + innerClose; }
      if (isVoid || selfClose) {
        out.append(outerOpen).append(m.group()).append(marker).append(outerClose);
        continue;
      }
      out.append(outerOpen).append(m.group()).append(marker).append(innerOpen);
      Open o = new Open();
      o.name = name;
      o.inner = innerClose;
      o.outer = outerClose;
      stack.add(o);
    }
    out.append(html, last, html.length());
    while (!stack.isEmpty()) {
      Open o = stack.remove(stack.size() - 1);
      out.append(o.inner).append(o.outer);
    }
    return out.toString();
  }

  private Chapter parseChapter(final ZipFile zip, final Map<String, String> lower, final String chapter,
      Resources res, List<String> anchorIds) throws Exception {
    Chapter ch = new Chapter();
    ZipEntry he = entry(zip, lower, chapter);
    if (he == null) return ch;
    String html = decode(readAll(zip.getInputStream(he), 16 << 20));
    Css css = chapterCss(zip, lower, chapter, html);
    String h = PROLOG.matcher(html).replaceFirst("");
    h = HEAD.matcher(h).replaceAll("");
    h = SCRIPT.matcher(h).replaceAll("");
    h = SVG_IMG.matcher(h).replaceAll("<img src=\"$1\">");
    h = SVG_TAG.matcher(h).replaceAll("");
    h = applyStyles(h, css, anchorIds);
    final Resources r = res;
    Html.ImageGetter ig = new Html.ImageGetter() {
      @Override public Drawable getDrawable(String src) {
        return loadImage(zip, lower, chapter, src, r);
      }
    };
    Spanned sp = Html.fromHtml(h, Html.FROM_HTML_MODE_COMPACT, ig, null);
    if (sp instanceof android.text.Spannable) {
      android.text.Spannable w = (android.text.Spannable) sp;
      URLSpan[] links = w.getSpans(0, w.length(), URLSpan.class);
      for (URLSpan u : links) w.removeSpan(u);   // links would draw in the (transparent) link colour
    }
    int n = sp.length();
    int s = 0;
    List<String> pending = null;
    while (s <= n) {
      check();
      int e = TextUtils.indexOf(sp, '\n', s);
      if (e < 0) e = n;
      if (e > s) {
        SpannableStringBuilder para = new SpannableStringBuilder(sp, s, e);
        List<String> found = null;
        Matcher mm = MARK.matcher(para);
        List<int[]> cut = new ArrayList<>();
        while (mm.find()) {
          try {
            int k = Integer.parseInt(mm.group(1));
            if (anchorIds != null && k < anchorIds.size()) {
              if (found == null) found = new ArrayList<>();
              found.add(anchorIds.get(k));
            }
          } catch (NumberFormatException ignored) { }
          cut.add(new int[] {mm.start(), mm.end()});
        }
        for (int i = cut.size() - 1; i >= 0; i--) para.delete(cut.get(i)[0], cut.get(i)[1]);
        int a = 0, b = para.length();
        while (b > a && isWs(para.charAt(b - 1))) b--;
        if (b < para.length()) para.delete(b, para.length());
        while (a < para.length() && isWs(para.charAt(a))) a++;
        if (a > 0) para.delete(0, a);
        if (found != null) {
          if (pending == null) pending = found; else pending.addAll(found);
        }
        if (para.length() > 0) {
          Block bl = new Block();
          bl.text = para;
          bl.heading = isHeading(para);
          bl.anchors = pending;
          pending = null;
          ch.blocks.add(bl);
        }
      }
      if (e >= n) break;
      s = e + 1;
    }
    return ch;
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
      synchronized (this) {
        if (imageBytes + cost > 160L * 1024 * 1024) return blank;
        imageBytes += cost;
      }
      Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
      if (bm == null) return blank;
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

    final int count = blocks.size();
    // paragraph direction: by the script that dominates the paragraph, not by its first letter
    boolean prevRtl = true;
    for (int i = 0; i < count; i++) {
      Block b = blocks.get(i);
      if (s.dir.equals("rtl")) b.rtl = true;
      else if (s.dir.equals("ltr")) b.rtl = false;
      else {
        int ar = 0, la = 0;
        CharSequence t = b.text;
        for (int k = 0, n = t.length(); k < n; k++) {
          char ch = t.charAt(k);
          if ((ch >= 0x0600 && ch <= 0x06FF) || (ch >= 0x0750 && ch <= 0x077F) || (ch >= 0xFB50 && ch <= 0xFDFF)
              || (ch >= 0xFE70 && ch <= 0xFEFF)) ar++;
          else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) la++;
        }
        if (ar == 0 && la == 0) b.rtl = prevRtl; else b.rtl = ar >= la;
      }
      prevRtl = b.rtl;
    }
    final Settings st = s;
    final int wFinal = w;
    final TextPaint basePaint = paint;
    final int[] done = {0};
    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<java.util.concurrent.Future<Object>> fs = new ArrayList<>();
      int per = (count + threads - 1) / threads;
      for (int t = 0; t < threads; t++) {
        final int from = t * per;
        final int to = Math.min(count, from + per);
        if (from >= to) break;
        fs.add(pool.submit(new java.util.concurrent.Callable<Object>() {
          @Override public Object call() {
            TextPaint tp = new TextPaint(basePaint);
            for (int i = from; i < to; i++) {
              check();
              layoutBlock(blocks.get(i), tp, st, wFinal);
              int d;
              synchronized (done) { d = ++done[0]; }
              if ((d & 255) == 0) emit("layout", d, count);
            }
            return null;
          }
        }));
      }
      for (java.util.concurrent.Future<Object> f : fs) {
        try {
          f.get();
        } catch (java.util.concurrent.ExecutionException ee) {
          Throwable c = ee.getCause();
          if (c instanceof RuntimeException) throw (RuntimeException) c;
          if (c instanceof Error) throw (Error) c;
          throw new RuntimeException(c);
        } catch (InterruptedException ie) {
          throw new Stop();
        }
      }
    } finally {
      pool.shutdownNow();
    }
    paginate(s);
  }

  private void layoutBlock(Block b, TextPaint paint, Settings s, int w) {
    int len = b.text.length();
    fitImages(b.text, w);
    TextDirectionHeuristic heur = b.rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR;
    boolean rtl = b.rtl;
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
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    if (Build.VERSION.SDK_INT >= 26) {
      bld.setJustificationMode(just ? Layout.JUSTIFICATION_MODE_INTER_WORD : Layout.JUSTIFICATION_MODE_NONE);
    }
    b.lay = bld.build();
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
      boolean lone = t.length() == 1;
      float cap = (lone && iw >= 300) ? 100f : 0.5f;
      float sc = Math.min(cap, Math.min(w / (float) iw, contentH * 0.92f / ih));
      d.setBounds(0, 0, Math.max(1, Math.round(iw * sc)), Math.max(1, Math.round(ih * sc)));
    }
  }

  private void paginate(Settings s) {
    List<Pg> out = new ArrayList<>();
    Pg cur = new Pg();
    float y = 0f;
    boolean prevHeading = false;
    int count = blocks.size();
    int[] firstPage = new int[count];
    java.util.Arrays.fill(firstPage, -1);
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
        if (firstPage[bi] < 0) firstPage[bi] = out.size();
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
    int nextKnown = Math.max(0, out.size() - 1);
    for (int i = count - 1; i >= 0; i--) {
      if (firstPage[i] < 0) firstPage[i] = nextKnown; else nextKnown = firstPage[i];
    }
    blockPage = firstPage;
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
          int outlineCount = 0;
          try {
            List<PdfOutline.Item> its = new ArrayList<>();
            for (TocItem ti : toc) {
              if (ti.block >= 0 && ti.block < blockPage.length) {
                its.add(new PdfOutline.Item(ti.title, ti.level, blockPage[ti.block]));
              }
            }
            if (!its.isEmpty() && PdfOutline.add(target, its)) outlineCount = its.size();
          } catch (Throwable ignored) { }
          WritableMap m = Arguments.createMap();
          m.putString("path", target.getAbsolutePath());
          m.putInt("pages", total);
          m.putInt("outline", outlineCount);
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
