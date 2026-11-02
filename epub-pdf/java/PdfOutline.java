package com.swipeswitch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a bookmark tree (the "Contents" list of a PDF reader) to a finished PDF as an incremental update,
 * so the original bytes are untouched. Works with classic xref tables (what Android's PdfDocument writes);
 * returns false and leaves the file alone for anything else.
 */
public final class PdfOutline {
  public static final class Item {
    final String title;
    final int level;
    final int page;

    public Item(String title, int level, int page) {
      this.title = title;
      this.level = level;
      this.page = page;
    }
  }

  private PdfOutline() {}

  private static final Charset L1 = Charset.forName("ISO-8859-1");

  public static boolean add(File pdf, List<Item> items) throws IOException {
    if (items.isEmpty()) return false;
    byte[] data = new byte[(int) pdf.length()];
    FileInputStream in = new FileInputStream(pdf);
    try {
      int off = 0;
      while (off < data.length) {
        int n = in.read(data, off, data.length - off);
        if (n < 0) break;
        off += n;
      }
    } finally { in.close(); }
    String s = new String(data, L1);

    int tr = s.lastIndexOf("trailer");
    if (tr < 0) throw new IOException("no classic trailer");
    String trailer = s.substring(tr);
    // object positions are found by scanning, so the old cross-reference table is not trusted
    Map<Integer, Long> off = new HashMap<>();
    Matcher om = Pattern.compile("(?:^|[\\r\\n])(\\d{1,8}) 0 obj").matcher(s);
    while (om.find()) off.put(Integer.parseInt(om.group(1)), (long) om.start(1));
    Matcher rm = Pattern.compile("/Root\\s+(\\d+)\\s+\\d+\\s+R").matcher(trailer);
    Matcher zm = Pattern.compile("/Size\\s+(\\d+)").matcher(trailer);
    if (!rm.find() || !zm.find()) throw new IOException("trailer without /Root");
    int root = Integer.parseInt(rm.group(1));
    int size = Math.max(Integer.parseInt(zm.group(1)), maxKey(off) + 1);
    Matcher im = Pattern.compile("/Info\\s+\\d+\\s+\\d+\\s+R").matcher(trailer);
    String info = im.find() ? im.group() : "";
    Matcher idm = Pattern.compile("/ID\\s*\\[[^\\]]*\\]").matcher(trailer);
    String id = idm.find() ? idm.group() : "";

    String cat = objText(s, off, root).trim();
    if (cat.contains("/Outlines")) throw new IOException("already has outlines");
    if (!cat.endsWith(">>")) throw new IOException("unexpected catalog");

    List<Integer> pageObjs = new ArrayList<>();
    Matcher pm = Pattern.compile("/Pages\\s+(\\d+)\\s+\\d+\\s+R").matcher(cat);
    if (!pm.find()) throw new IOException("catalog without /Pages");
    collectPages(s, off, Integer.parseInt(pm.group(1)), pageObjs, 0);
    if (pageObjs.isEmpty()) throw new IOException("no pages found");

    int n = items.size();
    int outlinesNo = size;
    int firstItemNo = size + 1;
    int[] parent = new int[n], prevI = new int[n], nextI = new int[n], first = new int[n], last = new int[n], desc = new int[n];
    java.util.Arrays.fill(parent, -1);
    java.util.Arrays.fill(prevI, -1);
    java.util.Arrays.fill(nextI, -1);
    java.util.Arrays.fill(first, -1);
    java.util.Arrays.fill(last, -1);
    int[] lvl = new int[n];
    int[] stack = new int[n + 2];
    int sp = 0;
    int topFirst = -1, topLast = -1;
    int prevLvl = 0;
    for (int i = 0; i < n; i++) {
      int l = Math.max(1, Math.min(items.get(i).level, prevLvl + 1));
      lvl[i] = l;
      prevLvl = l;
      while (sp > 0 && lvl[stack[sp - 1]] >= l) sp--;
      int par = sp > 0 ? stack[sp - 1] : -1;
      parent[i] = par;
      int sibling = par < 0 ? topLast : last[par];
      if (sibling >= 0) { prevI[i] = sibling; nextI[sibling] = i; }
      if (par < 0) { if (topFirst < 0) topFirst = i; topLast = i; }
      else { if (first[par] < 0) first[par] = i; last[par] = i; }
      for (int a = sp - 1; a >= 0; a--) desc[stack[a]]++;
      stack[sp++] = i;
    }

    StringBuilder body = new StringBuilder();
    Map<Integer, Long> newOff = new HashMap<>();
    long base = data.length;
    String sep = "\n";
    body.append(sep);

    // outline root
    newOff.put(outlinesNo, base + body.length());
    body.append(outlinesNo).append(" 0 obj\n<< /Type /Outlines /First ").append(firstItemNo + topFirst)
        .append(" 0 R /Last ").append(firstItemNo + topLast).append(" 0 R /Count ").append(n).append(" >>\nendobj\n");
    for (int i = 0; i < n; i++) {
      Item it = items.get(i);
      int pg = Math.max(0, Math.min(pageObjs.size() - 1, it.page));
      StringBuilder d = new StringBuilder();
      d.append("<< /Title ").append(pdfString(it.title)).append(" /Parent ")
          .append(parent[i] < 0 ? outlinesNo : firstItemNo + parent[i]).append(" 0 R");
      if (prevI[i] >= 0) d.append(" /Prev ").append(firstItemNo + prevI[i]).append(" 0 R");
      if (nextI[i] >= 0) d.append(" /Next ").append(firstItemNo + nextI[i]).append(" 0 R");
      if (first[i] >= 0) d.append(" /First ").append(firstItemNo + first[i]).append(" 0 R /Last ")
          .append(firstItemNo + last[i]).append(" 0 R /Count ").append(desc[i]);
      d.append(" /Dest [").append(pageObjs.get(pg)).append(" 0 R /Fit] >>");
      newOff.put(firstItemNo + i, base + body.length());
      body.append(firstItemNo + i).append(" 0 obj\n").append(d).append("\nendobj\n");
    }
    // new catalog (same object number, so readers pick the updated one)
    String newCat = cat.substring(0, cat.length() - 2).replaceAll("/PageMode\\s*/\\w+", "").trim() + " /Outlines " + outlinesNo + " 0 R /PageMode /UseOutlines >>";
    newOff.put(root, base + body.length());
    body.append(root).append(" 0 obj\n").append(newCat).append("\nendobj\n");

    long xrefPos = base + body.length();
    int newSize = firstItemNo + n;
    body.append("xref\n0 ").append(newSize).append("\n");
    for (int k = 0; k < newSize; k++) {
      Long o = newOff.get(k);
      if (o == null) o = off.get(k);
      if (o == null) body.append(k == 0 ? "0000000000 65535 f \n" : "0000000000 00000 f \n");
      else body.append(entryLine(o));
    }
    body.append("trailer\n<< /Size ").append(newSize).append(" /Root ").append(root).append(" 0 R").append("");
    if (!info.isEmpty()) body.append(" ").append(info);
    if (!id.isEmpty()) body.append(" ").append(id);
    body.append(" >>\nstartxref\n").append(xrefPos).append("\n%%EOF\n");

    File tmp = new File(pdf.getPath() + ".ol");
    FileOutputStream out = new FileOutputStream(tmp);
    try {
      out.write(data);
      out.write(body.toString().getBytes(L1));
    } finally { out.close(); }
    if (!pdf.delete() || !tmp.renameTo(pdf)) {
      tmp.delete();
      return false;
    }
    return true;
  }

  private static int maxKey(Map<Integer, Long> m) {
    int mx = 0;
    for (int k : m.keySet()) if (k > mx) mx = k;
    return mx;
  }

  private static String entryLine(long offset) {
    String o = Long.toString(offset);
    StringBuilder b = new StringBuilder();
    for (int i = o.length(); i < 10; i++) b.append('0');
    return b.append(o).append(" 00000 n \n").toString();
  }

  private static int skipWs(String s, int p) {
    while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    return p;
  }

  private static String objText(String s, Map<Integer, Long> off, int n) throws IOException {
    Long o = off.get(n);
    if (o == null) throw new IOException("Missing PDF object " + n);
    int i = s.indexOf("obj", (int) (long) o);
    int e = s.indexOf("endobj", i);
    if (i < 0 || e < 0) throw new IOException("Broken PDF object " + n);
    return s.substring(i + 3, e);
  }

  private static void collectPages(String s, Map<Integer, Long> off, int node, List<Integer> out, int depth)
      throws IOException {
    if (depth > 12) return;
    String t = objText(s, off, node);
    if (Pattern.compile("/Type\\s*/Pages\\b").matcher(t).find()) {
      Matcher km = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(t);
      if (!km.find()) return;
      Matcher rm = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(km.group(1));
      while (rm.find()) collectPages(s, off, Integer.parseInt(rm.group(1)), out, depth + 1);
    } else {
      out.add(node);
    }
  }

  private static String pdfString(String t) {
    StringBuilder b = new StringBuilder("<FEFF");
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      b.append(String.format("%04X", (int) c));
    }
    return b.append(">").toString();
  }
}
