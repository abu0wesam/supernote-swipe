#!/usr/bin/env python3
"""Prepare PDF Tool 1.4.5 in the build workspace; never commit generated changes."""
import base64
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = "android/app/src/main/java/com/swipeswitch/PdfToolModule.kt"
EXPECTED_NATIVE = "e60a2860695b8193b50044a60db7f3d25848598e"
EXPECTED_SCREEN = "10298ac9ceb677729ed5ba14e3b3eb6f38853479"

def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("Patch anchor missing or ambiguous: " + old[:100])
    return text.replace(old, new, 1)

NATIVE_EDITS = [
    ("import java.util.concurrent.Future\n",
     "import java.util.concurrent.Future\nimport java.util.concurrent.TimeUnit\n"),
    ('  private class CancelledException : RuntimeException("Cancelled")',
     '  private class CancelledException : RuntimeException("Cancelled")\n\n'
     '  private class WorkerMemoryException(val workers: Int, cause: Throwable) : RuntimeException(cause)'),
    ("return runApplyOnce(path, backupPath, o, mode, manualDeg, threads)",
     "return runApplyOnce(path, backupPath, o, mode, manualDeg, threads, attempt == 0)"),
    ("          threads = max(1, threads / 2)\n          System.gc()",
     "          val failedWorkers = (e as? WorkerMemoryException)?.workers ?: threads\n"
     "          threads = max(1, min(threads, failedWorkers) / 2)\n          System.gc()"),
    ("private fun runApplyOnce(path: String, backupPath: String, o: Opts, mode: String, manualDeg: Double, requested: Int): WritableMap {",
     "private fun runApplyOnce(path: String, backupPath: String, o: Opts, mode: String, manualDeg: Double, requested: Int, optimistic: Boolean): WritableMap {"),
    ("        val runtime = Runtime.getRuntime()\n        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())\n"
     "        val byMemory = ((free * 0.75 - 4.0 * pixels) / (14.0 * pixels)).toInt()\n"
     "        val workers = max(1, min(requested, byMemory))",
     "        System.gc()\n        val runtime = Runtime.getRuntime()\n"
     "        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())\n"
     "        // Experimental scheduling budget, not a guarantee of peak memory usage.\n"
     "        // Only the first attempt may try two workers despite this estimate.\n"
     "        val byMemory = ((free * 0.75) / (9.0 * pixels)).toInt()\n"
     "        val floorWorkers = if (optimistic) min(requested, 2) else 1\n"
     "        val workers = max(floorWorkers, min(requested, byMemory))"),
    ("          pool.shutdownNow()\n          writer.abort()\n          throw e",
     "          pool.shutdownNow()\n          writer.abort()\n"
     "          // Never start a memory retry while an old worker is still processing.\n"
     "          if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {\n"
     '            throw IllegalStateException("PDF workers did not stop; retry was not started.")\n'
     "          }\n"
     "          if (isOutOfMemory(e)) throw WorkerMemoryException(workers, e)\n"
     "          throw e"),
]

SCREEN_EDITS = [
    ("if (!raw || !native || !loaded) {", "if (!raw || !native || !loaded || busy) {"),
    ("[raw, native, loaded, darkLevel, degrees, widthLevel, heightLevel, sharpLevel, cleanLevel]);",
     "[raw, native, loaded, darkLevel, degrees, widthLevel, heightLevel, sharpLevel, cleanLevel, busy]);"),
]

def transform_native(text):
    marker = "    private fun enhance("
    protected = text[text.index(marker):]
    for old, new in NATIVE_EDITS:
        text = replace_once(text, old, new)
    if text[text.index(marker):] != protected:
        raise RuntimeError("Image processing or PDF writer changed unexpectedly")
    return text

ICON_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAQAAAAEAAQAAAAB0CZXLAAACqElEQVR42u2ZsY7UMBBAx1bEheryAYj1J1DSXUr+gx+AL1ivxAfwRSgdfASF6SgogkSRlXIZil2SsT3jEYLdu5PsMvfOdp5n7HHWIJSbhQpUoAICcG9Icwzwiz4btSEmBoj+adZ6WDQA1df0GjAwwA7XNkEo99BE78QOMWnArAGLBqACNFSEZUNgKAOGiuBVjwrQTwrgZgXoFgVoUQGoCAuKCBagIvjMIiJ4gIjgASKCB4gIHiAieICIsKCI4AEiQthhNhECsIkQgE2EAGwiBGATIQCbCAuKCAHYREg77SpCAlYRErCKkIBVhASsIuTjwP8xkrejIZul3EN4yEPNaoBRh0BExAAP+RYVuA5wPNU5/iJDfDPGmEu8xZabO/zXHk7tBms8/F/gy1OY5BDveRhI0YuIOII7P/nriDq2AADwTpvD7efhEWWWJZmFuCXF65pZ1wF+asDoAe59AQhK6Q5DEBfrXAHdAvwo3rMmgOlj4ab2TCnd5+dLeQ5T9x7gxVv5zOpcr5TuvROz+1RnYrgLWXaTzDIQemU1B69cSA/KmbW3ymJ9akTgBhHDq69tsYfw5ntXBIYP4IrAwUJfWiy0xu9LPSwN0cABc0u3V8tFw9IUgdHNbREI/dQVgcGPrggcSDRwAFoSDRwQa2CAWAMDxBoYINbAALEGLi8wAOwxiKde9AWEGyL6hsKdWdFXGK6HFpV9sluUatDNSg/9xNx4yOnvMdzRJ7YcDfkQSTTkQBINOZBEQw4k0ZADSTTkQBoNGRAnBQOkGlIg05ACmYYUyDSkQJNqyJc70ZANMSYasiGmREMK2DnRkAJmSTRkk9wflPKgtwrgGgXoWgVoOwVonALYXgGMr8XeowWadadO/7K79iRfXmIIpwHn6DX1J9QKVOBpAr8B1m8xBbO5rAsAAAAASUVORK5CYII='
ICON_SHA256 = '7e74823a6162340fe9d4b0bf841d4219f595eddef635e0d884069f257c632c5f'

def main():
    native_file = ROOT / NATIVE
    screen_file = ROOT / "ConvertScreen.tsx"
    native_bytes = native_file.read_bytes()
    screen_bytes = screen_file.read_bytes()
    if git_blob(native_bytes) != EXPECTED_NATIVE:
        raise RuntimeError("Native source changed: review the patch against the new source first")
    if git_blob(screen_bytes) != EXPECTED_SCREEN:
        raise RuntimeError("Screen source changed: review the patch against the new source first")
    native_new = transform_native(native_bytes.decode("utf-8"))
    screen_new = screen_bytes.decode("utf-8")
    for old, new in SCREEN_EDITS:
        screen_new = replace_once(screen_new, old, new)
    icon = base64.b64decode(ICON_PNG, validate=True)
    if hashlib.sha256(icon).hexdigest() != ICON_SHA256:
        raise RuntimeError("Icon checksum mismatch")
    native_file.write_bytes(native_new.encode("utf-8"))
    screen_file.write_bytes(screen_new.encode("utf-8"))
    icon_file = ROOT / "assets/icon_pdftool.png"
    icon_file.parent.mkdir(parents=True, exist_ok=True)
    icon_file.write_bytes(icon)
    print("PDF Tool 1.4.5 prepared: scheduling patch, preview guard, icon #5. Image routines unchanged.")

if __name__ == "__main__":
    main()
