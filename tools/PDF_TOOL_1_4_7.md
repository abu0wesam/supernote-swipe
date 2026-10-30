# PDF Tool 1.4.7

Run order: tools/prepare_pdf_tool.py, then tools/prepare_exact_preview.py. The manual Release workflow does both before TypeScript checking and bundling.

Preview and conversion now use the same native PdfRenderer drawing code (height 2560, same width limit, physical page aspect ratio) and the same enhance() image routine. The preview returns a SHA-256 hash of the processed grayscale page. Conversion recomputes the hash for the previewed page and stops before replacing the PDF if it differs. Source size and modification time are checked before the work and before replacement.

Apply stays disabled until the preview matches the current settings (including measured Auto tilt). Controls lock while converting. After Apply or Restore, reopen the tool for another operation.

Icon 6 (document and wrench, bold black strokes) is drawn by the script with the Python standard library. Image filters, lossless compression and the scheduling patch are unchanged. This does not solve background sleep.

Status: the script anchors were checked against the files read from main, and edit-order interactions were simulated, but no Android/TypeScript build and no device test has been run. If the app reports "Output does not match the preview", the pixel proof failed safe and the PDF was not replaced.
