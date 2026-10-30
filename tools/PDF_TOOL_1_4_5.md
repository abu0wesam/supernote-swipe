# PDF Tool 1.4.5 preparation

This release uses a checked build-time patch, rather than replacing the large
PdfToolModule.kt through a connector that truncates reads. The tracked native
file and ConvertScreen.tsx remain the 1.4.4 sources; the release workspace is
patched before bundling and compilation. This is intentional, not a full
source-file migration.

Run `python3 tools/prepare_pdf_tool.py` once on a clean checkout before any local
build. The Release workflow does this automatically, only when manually started.
The script checks exact Git blob hashes for the native file and screen, and
refuses unexpected sources or a second application. It also creates the PNG
icon from a checksummed text representation before Metro reads index.js.

## Scheduling experiment

- Ask for garbage collection before measuring Java heap headroom.
- Use an experimental 9-byte/pixel scheduling budget and try at least two
  workers only on the first attempt (unless only one was requested).
- Retry with fewer workers on OutOfMemoryError, based on the failed worker count.
- Wait for old workers to terminate before a memory retry; fail instead of
  retrying concurrently if they do not stop within 60 seconds.
- Pause new preview requests while conversion is busy.
- Keep every byte from `private fun enhance` through the end of the native file
  unchanged, including all image routines and the PDF writer. Rendering height
  remains 2560 and Deflate level remains 1.

This is not a guarantee of peak memory usage or of a speed-up. A system-level
low-memory process kill cannot be recovered by catching OutOfMemoryError.

## Sleep handling

The SDK's setSystemDormancyState(true) requests prevention of automatic sleep;
false allows sleep again. The shared conversion guard releases this state in
finally after success, cancellation, or failure, and rejects duplicate jobs.
If the API is missing or explicitly reports failure, conversion is not started.
Release failures are logged without replacing the original conversion result.

This is NOT a standalone Android foreground service and does not guarantee
survival after PluginHost termination, manual sleep, forced app closure, or a
restart. No additional Android permission is declared. Test on the actual Manta.

## Validation

`node tools/test_pdf_apply_guard.mjs` tests the actual guard without dependencies.
The workflow also runs the existing TypeScript, Jest, and Android build steps.
The Android build, pixel comparison, speed, and device sleep behavior have NOT
been tested by the assistant. Accept the release only after those device tests.
