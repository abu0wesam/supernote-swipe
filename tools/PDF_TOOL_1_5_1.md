# PDF Tool 1.5.1

PDF stretch: the page frame (border lines) around the text was being treated as the text block, so the Width/Height stretch moved the frame towards the page edge while the text inside it barely moved. tools/prepare_frame_fix.py (run after prepare_exact_preview.py) clears long thin lines (vertical runs over 5% of the page height, horizontal runs over 25% of its width) in a copy before the text block is measured, and falls back to the old measurement if nothing is left. The preview and the converted file share this code, so they stay identical. Frames and rules are squeezed into the outer strips, not removed. Pages without frames measure the same as before.

Text tool: an approximate live preview of the current page text now answers every option. The reader lays text out differently, so the created copy must still be checked there. With "User-defined" display settings the reader ignores most book CSS, so the screen reminds you to choose "Document default setting". Margin options also add @page{margin:0}.

Status: the new measurement was tested in a Python port on a synthetic page with a frame; TypeScript, Kotlin and Android builds and a real-device test have NOT been run.
