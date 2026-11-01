# EPUB layout preview + PDF export (separate branch, nothing in main is changed)

- `java/BookPdfModule.java`, `java/BookPdfPackage.java`: one layout engine (Android StaticLayout) on a Manta page (462 x 616 pt).
  The preview bitmap and the PDF pages are painted by the same `drawPage()`.
- `js/module600.js`: the EPUB screen plus a router. Module 497 sends `.epub` files to it and everything else to the original PDF screen.
- Registered through a second entry in `reactPackages` (`com.swipeswitch.BookPdfPackage`), so `PdfToolPackage` is untouched.
