#!/usr/bin/env python3
"""Run AFTER prepare_exact_preview.py and BEFORE bundling.
Page frames, borders and rules no longer decide where the text block is, so a frame around the
text does not stop the width/height stretch from moving the text itself towards the page edges."""
from pathlib import Path
from prepare_pdf_tool import replace_once

ROOT = Path(__file__).resolve().parents[1]
SIGNATURE = "    private fun contentBox(g: ByteArray, w: Int, h: Int): Box? {\n"
HELPERS = r"""    /**
     * Frames, borders and rules are long thin lines, not text. In a copy, dark runs longer than 5% of the
     * page height (vertical) or 25% of its width (horizontal) are cleared before the text block is measured.
     */
    private fun withoutLongLines(g: ByteArray, w: Int, h: Int): ByteArray {
      val out = g.copyOf()
      val minV = max(40, h / 20)
      val minH = max(40, w / 4)
      for (x in 0 until w) {
        var y = 0
        while (y < h) {
          if ((g[y * w + x].toInt() and 0xFF) >= 170) {
            y++
            continue
          }
          var end = y
          while (end < h && (g[end * w + x].toInt() and 0xFF) < 170) end++
          if (end - y >= minV) {
            for (k in y until end) out[k * w + x] = 255.toByte()
          }
          y = end
        }
      }
      for (y in 0 until h) {
        val row = y * w
        var x = 0
        while (x < w) {
          if ((g[row + x].toInt() and 0xFF) >= 170) {
            x++
            continue
          }
          var end = x
          while (end < w && (g[row + end].toInt() and 0xFF) < 170) end++
          if (end - x >= minH) {
            for (k in x until end) out[row + k] = 255.toByte()
          }
          x = end
        }
      }
      return out
    }

    private fun contentBox(g: ByteArray, w: Int, h: Int): Box? =
        contentBoxRaw(withoutLongLines(g, w, h), w, h) ?: contentBoxRaw(g, w, h)

    private fun contentBoxRaw(g: ByteArray, w: Int, h: Int): Box? {
"""

def main():
    native = ROOT / "android/app/src/main/java/com/swipeswitch/PdfToolModule.kt"
    text = native.read_text(encoding="utf-8")
    if "contentBoxRaw" in text:
        raise RuntimeError("The frame fix is already applied")
    patched = replace_once(text, SIGNATURE, HELPERS)
    if patched.replace(HELPERS, SIGNATURE, 1) != text:
        raise RuntimeError("Unexpected change outside the inserted helpers")
    native.write_text(patched, encoding="utf-8")
    print("Frame fix prepared: long lines are ignored when the text block is measured.")

if __name__ == "__main__":
    main()
