# PDF Tool 1.5.0: Text (EPUB) tool

A second toolbar button, Text, formats a reflowable EPUB into a NEW, separately named copy beside the book. The original is never changed or overwritten. The PDF button and all gestures are unchanged.

Settings: font family (generic serif or sans), size, weight, line spacing, side and top/bottom margins, alignment and text direction. Settings are saved per book in the plugin folder.

How it works: a style block is added to each reading-order XHTML chapter. Other resources (images, fonts, CSS, metadata) are copied unchanged. Only single-rendition reflowable books are accepted; fixed-layout, DRM-protected, signed or encrypted books, non-XHTML spine items and TXT files are rejected. Direction changes text direction only; page-turn metadata is kept. The copy needs Android 8 or later.

Limits: no exact reader preview is claimed, reader settings or publisher CSS can override the result, and fonts are generic families rather than embedded Arabic fonts.

Run order for local builds: prepare_pdf_tool.py, prepare_exact_preview.py, prepare_text_assets.py. The manual Release workflow does all three.

Status: CSS rules passed Node assertions and the icon generator was checked. Kotlin, TypeScript and Android builds and a real EPUB export on the device have NOT been run. Test on a copy of a book: open the new copy, check text, links and images, and cancel in the middle of an export.
