/**
 * Page tool, all on one screen: the page open in the reader (large; tap it to see it full screen),
 * and under it one compact row for each setting: stretch sideways (which also centres the text),
 * stretch up and down, darkness, sharpness, background cleanup, tilt. The result can be applied to
 * the same file (and cancelled while it works); the original is kept and can be restored.
 * The levels chosen are remembered for the next time the tool is opened.
 *
 * @format
 */

import React, {useEffect, useRef, useState} from 'react';
import {Dimensions, Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {FileUtils, NativeUIUtils, PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {MANTA_ASPECT} from './src/convert';
import {PageInfo, pageInfo} from './src/pageflip';
import {loadPdfToolSettings, savePdfToolSettings} from './src/pdfToolSettings';
import {
  SkewMode,
  applyToPdf,
  backupPathFor,
  cancelApply,
  detectSkew,
  nativeAvailable,
  onProgress,
  processImage,
  restoreBackup,
} from './src/pdftool';

type Raw = {path: string; dir: string; aspect: number};

let lastRawPath: string | null = null;

/** Height, in pixels, of the page picture: the same as the pages of the new PDF (the Manta screen's height). */
const PAGE_PIXELS = 2560;

/** Height kept for everything under the preview: header, rows, buttons, messages. */
const RESERVED_HEIGHT = 610;

/** Worker threads used when the file is written: four, the number that ran fastest on the Manta (the module lowers it if the memory is short). */
const THREADS = 4;

/** The real size of a page: the file API first, then the size the reader shows. */
async function readPageSize(path: string, page: number): Promise<{w: number; h: number} | null> {
  const tries: (() => Promise<any>)[] = [
    () => (PluginFileAPI as any).getPageSize(path, page),
    () => (PluginCommAPI as any).getPageDisplaySize(),
  ];
  for (const call of tries) {
    try {
      const res = await call();
      const w = Number(res?.result?.width);
      const h = Number(res?.result?.height);
      if (res?.success && w > 0 && h > 0) {
        return {w, h};
      }
    } catch {
      // try the next one
    }
  }
  return null;
}

async function removeFile(path: string | null) {
  if (!path) {
    return;
  }
  try {
    await FileUtils.deleteFile(path);
  } catch {
    // already gone
  }
}

async function renderRaw(path: string, page: number): Promise<Raw> {
  const dir = await PluginManager.getPluginDirPath();
  if (!dir) {
    throw new Error('The plugin folder is not available.');
  }
  await removeFile(lastRawPath);
  const real = await readPageSize(path, page);
  // The picture has the page's own proportions and the height of the pages of the new PDF.
  const height = PAGE_PIXELS;
  const width = real ? Math.min(5120, Math.max(1, Math.round((height * real.w) / real.h))) : 1920;
  const out = `${dir}/preview_${Date.now()}.png`;
  const res: any = await (PluginDocAPI as any).generateCurrentDocImage(page, out, {width, height}, 0);
  if (!res?.success) {
    throw new Error(res?.error?.message ?? 'Could not draw the page.');
  }
  lastRawPath = out;
  return {path: out, dir, aspect: width / height};
}

function Chip({label, on, onPress}: {label: string; on: boolean; onPress: () => void}) {
  return (
    <Pressable style={[styles.chip, on && styles.chipOn]} onPress={onPress}>
      <Text style={[styles.chipText, on && styles.chipOnText]} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

/** One compact row: the name on the side, the choices in a line. */
function Row({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel} numberOfLines={1}>
        {label}
      </Text>
      <View style={styles.rowChips}>{children}</View>
    </View>
  );
}

function Levels({from, to, value, onPick}: {from: number; to: number; value: number; onPick: (n: number) => void}) {
  return (
    <>
      {Array.from({length: to - from + 1}, (_, i) => from + i).map(n => (
        <Chip key={n} label={String(n)} on={n === value} onPress={() => onPick(n)} />
      ))}
    </>
  );
}

export default function ConvertScreen(): React.JSX.Element {
  const [info, setInfo] = useState<PageInfo | null>(null);
  const [raw, setRaw] = useState<Raw | null>(null);
  const [procPath, setProcPath] = useState<string | null>(null);
  const [showOriginal, setShowOriginal] = useState(false);
  const [full, setFull] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [widthLevel, setWidthLevel] = useState(4); // 0..10
  const [heightLevel, setHeightLevel] = useState(2); // 0..10
  const [darkLevel, setDarkLevel] = useState(3); // 1..10
  const [sharpLevel, setSharpLevel] = useState(3); // 0..10
  const [cleanLevel, setCleanLevel] = useState(0); // 0..10
  const [skew, setSkew] = useState<SkewMode>('off');
  const [manualDeg, setManualDeg] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [detected, setDetected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const [hasBackup, setHasBackup] = useState(false);
  const procRef = useRef<string | null>(null);

  const native = nativeAvailable();
  const isPdf = !!info && info.path.toLowerCase().endsWith('.pdf');

  // The levels saved the last time, before the first preview is made.
  useEffect(() => {
    let alive = true;
    loadPdfToolSettings()
      .then(saved => {
        if (!alive) {
          return;
        }
        if (saved) {
          setWidthLevel(saved.width);
          setHeightLevel(saved.height);
          setDarkLevel(saved.dark);
          setSharpLevel(saved.sharp);
          setCleanLevel(saved.clean);
          setSkew(saved.skew);
          setManualDeg(saved.manual);
        }
        setLoaded(true);
      })
      .catch(() => alive && setLoaded(true));
    return () => {
      alive = false;
    };
  }, []);

  // Every change of a level is saved.
  useEffect(() => {
    if (!loaded) {
      return;
    }
    savePdfToolSettings({
      width: widthLevel,
      height: heightLevel,
      dark: darkLevel,
      sharp: sharpLevel,
      clean: cleanLevel,
      skew,
      manual: manualDeg,
    });
  }, [loaded, widthLevel, heightLevel, darkLevel, sharpLevel, cleanLevel, skew, manualDeg]);

  // The page open in the reader.
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const i = await pageInfo();
        if (!alive) {
          return;
        }
        if (!i) {
          setError('No open document was detected.');
          return;
        }
        setInfo(i);
        const r = await renderRaw(i.path, i.current);
        if (!alive) {
          return;
        }
        setRaw(r);
        if (nativeAvailable()) {
          setHasBackup(await FileUtils.exists(backupPathFor(r.dir, i.path)));
          detectSkew(r.path)
            .then(d => alive && setDetected(d))
            .catch(() => undefined);
        }
      } catch (e: any) {
        if (alive) {
          setError(String(e?.message ?? e));
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const degrees = skew === 'off' ? 0 : skew === 'auto' ? detected ?? 0 : manualDeg;

  // The preview with the real processing, a moment after a level changes.
  useEffect(() => {
    if (!raw || !native || !loaded) {
      return;
    }
    let alive = true;
    setUpdating(true);
    const timer = setTimeout(async () => {
      try {
        const out = `${raw.dir}/proc_${Date.now()}.png`;
        await processImage(
          raw.path,
          out,
          {dark: darkLevel, width: widthLevel, height: heightLevel, sharp: sharpLevel, clean: cleanLevel},
          degrees,
        );
        if (!alive) {
          await removeFile(out);
          return;
        }
        const previous = procRef.current;
        procRef.current = out;
        setProcPath(out);
        setUpdating(false);
        await removeFile(previous);
      } catch (e: any) {
        if (alive) {
          setUpdating(false);
          setMessage(`Preview failed: ${e?.message ?? e}`);
        }
      }
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [raw, native, loaded, darkLevel, degrees, widthLevel, heightLevel, sharpLevel, cleanLevel]);

  const win = Dimensions.get('window');
  const boxH = Math.max(260, Math.min(win.height - RESERVED_HEIGHT, (win.width - 24) / MANTA_ASPECT));
  const boxW = boxH * MANTA_ASPECT;
  const pageAspect = raw ? raw.aspect : MANTA_ASPECT;
  const fitsHeight = pageAspect <= MANTA_ASPECT;
  const baseW = fitsHeight ? boxH * pageAspect : boxW;
  const baseH = fitsHeight ? boxH : boxW / pageAspect;
  const shownPath = showOriginal ? raw?.path : procPath ?? raw?.path;
  const shownUri = shownPath ? `file://${shownPath}` : null;

  const backupFor = async (path: string) => {
    const dir = await PluginManager.getPluginDirPath();
    if (!dir) {
      throw new Error('The plugin folder is not available.');
    }
    return backupPathFor(dir, path);
  };

  const apply = async () => {
    if (!info || busy) {
      return;
    }
    if (!isPdf) {
      setMessage('Only PDF files can be changed.');
      return;
    }
    const ok = await NativeUIUtils.showRattaDialog(
      'Replace this file with the adjusted version?\nA copy of the original is kept, and "Restore original" brings it back.\nPages become images and a long book takes several minutes. You can cancel while it works.',
      'Cancel',
      'Apply',
      false,
    );
    if (!ok) {
      return;
    }
    setBusy(true);
    setMessage(null);
    setProgress('starting\u2026');
    const stop = onProgress((done, total) => setProgress(`${done} / ${total}`));
    try {
      const backup = await backupFor(info.path);
      const res = await applyToPdf(info.path, backup, {
        dark: darkLevel,
        width: widthLevel,
        height: heightLevel,
        sharp: sharpLevel,
        clean: cleanLevel,
        threads: THREADS,
        mode: skew,
        manual: manualDeg,
      });
      setHasBackup(true);
      await (PluginFileAPI as any).openFile(info.path, info.current);
      const seconds = res.ms / 1000;
      const timing = res as any;
      const rounded = (v: unknown) => (typeof v === 'number' ? v.toFixed(0) : '?');
      setMessage(
        `Done: ${res.pages} pages in ${seconds.toFixed(0)} s (${(seconds / Math.max(1, res.pages)).toFixed(2)} s per page), ` +
          `${res.threads} thread(s), ${(res.bytes / (1024 * 1024)).toFixed(1)} MB.` +
          `\nPer page, ms: draw ${rounded(timing.renderMs)}, process ${rounded(timing.workMs)}, compress ${rounded(timing.packMs)}, wait ${rounded(timing.waitMs)}, write ${rounded(timing.writeMs)}.`,
      );
    } catch (e: any) {
      if (e?.code === 'E_CANCELLED') {
        setMessage('Cancelled. The file was not changed.');
      } else {
        setMessage(`Failed: ${e?.message ?? e}`);
      }
    } finally {
      stop();
      setBusy(false);
      setProgress('');
    }
  };

  const cancel = async () => {
    setMessage('Cancelling\u2026 it stops after the page in hand.');
    try {
      await cancelApply();
    } catch {
      // the work ends by itself
    }
  };

  const restore = async () => {
    if (!info || busy) {
      return;
    }
    const ok = await NativeUIUtils.showRattaDialog('Put the original file back?', 'Cancel', 'Restore', false);
    if (!ok) {
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await restoreBackup(info.path, await backupFor(info.path));
      setHasBackup(false);
      await (PluginFileAPI as any).openFile(info.path, info.current);
      setMessage('The original file is back.');
    } catch (e: any) {
      setMessage(`Failed: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  // The page with the proposed changes, over the whole window; a tap goes back.
  if (full && shownUri) {
    const winAspect = win.width / win.height;
    const fullW = pageAspect > winAspect ? win.width : win.height * pageAspect;
    const fullH = pageAspect > winAspect ? win.width / pageAspect : win.height;
    return (
      <Pressable style={styles.full} onPress={() => setFull(false)}>
        <Image source={{uri: shownUri}} resizeMode="stretch" style={{width: fullW, height: fullH}} />
      </Pressable>
    );
  }

  const tiltLabel = skew === 'auto' && detected !== null ? `Tilt ${detected.toFixed(1)}\u00b0` : 'Tilt';

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text style={styles.title}>PDF Tool</Text>
        <Text style={styles.page}>
          {info ? `Page ${info.current + 1}${info.total ? ` / ${info.total}` : ''}` : ''}
          {updating ? '  \u00b7 updating\u2026' : ''}
        </Text>
        <Pressable onPress={() => PluginManager.closePluginView()} style={styles.close}>
          <Text style={styles.title}>{'\u2715'}</Text>
        </Pressable>
      </View>
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {!native ? <Text style={styles.error}>This build has no native PDF module: the preview and applying are unavailable.</Text> : null}

      <Pressable
        style={[styles.screen, {width: boxW, height: boxH}]}
        onPress={() => {
          if (shownUri) {
            setFull(true);
          }
        }}>
        {shownUri ? (
          <Image source={{uri: shownUri}} resizeMode="stretch" style={{width: baseW, height: baseH}} />
        ) : (
          <Text style={styles.hint}>{error ? '' : 'Drawing the page\u2026'}</Text>
        )}
      </Pressable>

      <Row label="View">
        <Chip label="Original" on={showOriginal} onPress={() => setShowOriginal(true)} />
        <Chip label="Result" on={!showOriginal} onPress={() => setShowOriginal(false)} />
      </Row>
      <Row label="Width">
        <Levels from={0} to={10} value={widthLevel} onPick={setWidthLevel} />
      </Row>
      <Row label="Height">
        <Levels from={0} to={10} value={heightLevel} onPick={setHeightLevel} />
      </Row>
      <Row label="Dark">
        <Levels from={1} to={10} value={darkLevel} onPick={setDarkLevel} />
      </Row>
      <Row label="Sharp">
        <Levels from={0} to={10} value={sharpLevel} onPick={setSharpLevel} />
      </Row>
      <Row label="Clean">
        <Levels from={0} to={10} value={cleanLevel} onPick={setCleanLevel} />
      </Row>
      <Row label={tiltLabel}>
        <Chip label="Off" on={skew === 'off'} onPress={() => setSkew('off')} />
        <Chip label="Auto" on={skew === 'auto'} onPress={() => setSkew('auto')} />
        <Chip label="Manual" on={skew === 'manual'} onPress={() => setSkew('manual')} />
        {skew === 'manual' ? (
          <>
            <Chip label="-1" on={false} onPress={() => setManualDeg(d => Math.max(-5, +(d - 1).toFixed(2)))} />
            <Chip label="-.25" on={false} onPress={() => setManualDeg(d => Math.max(-5, +(d - 0.25).toFixed(2)))} />
            <Chip label={`${manualDeg.toFixed(2)}\u00b0`} on={true} onPress={() => setManualDeg(0)} />
            <Chip label="+.25" on={false} onPress={() => setManualDeg(d => Math.min(5, +(d + 0.25).toFixed(2)))} />
            <Chip label="+1" on={false} onPress={() => setManualDeg(d => Math.min(5, +(d + 1).toFixed(2)))} />
          </>
        ) : null}
      </Row>

      <View style={styles.buttons}>
        <Pressable style={[styles.button, styles.buttonMain, (!native || busy) && styles.disabled]} onPress={apply} disabled={!native || busy}>
          <Text style={styles.buttonText}>{busy ? `Working ${progress}` : 'Apply to this file'}</Text>
        </Pressable>
        {busy ? (
          <Pressable style={styles.button} onPress={cancel}>
            <Text style={styles.buttonText}>Cancel</Text>
          </Pressable>
        ) : hasBackup ? (
          <Pressable style={styles.button} onPress={restore}>
            <Text style={styles.buttonText}>Restore original</Text>
          </Pressable>
        ) : null}
      </View>
      {message ? <Text style={styles.message}>{message}</Text> : null}
      <Text style={styles.hint}>Tap the page to see it full screen. 0 = off, 10 = strongest. Width also centres the text.</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#ffffff'},
  content: {paddingHorizontal: 12, paddingVertical: 8, alignItems: 'center'},
  full: {flex: 1, backgroundColor: '#ffffff', alignItems: 'center', justifyContent: 'center'},
  header: {flexDirection: 'row', alignItems: 'center', alignSelf: 'stretch', height: 44},
  title: {fontSize: 24, fontWeight: '700', color: '#000000'},
  page: {flex: 1, fontSize: 17, color: '#444444', textAlign: 'center'},
  close: {paddingHorizontal: 8},
  hint: {fontSize: 14, color: '#444444', marginVertical: 4, textAlign: 'center'},
  error: {fontSize: 17, color: '#000000', marginVertical: 6, textAlign: 'center'},
  message: {fontSize: 16, color: '#000000', marginVertical: 4, textAlign: 'center'},
  screen: {borderWidth: 2, borderColor: '#000000', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', marginVertical: 6},
  row: {flexDirection: 'row', alignItems: 'center', alignSelf: 'stretch', height: 50},
  rowLabel: {width: 92, fontSize: 16, fontWeight: '700', color: '#000000'},
  rowChips: {flex: 1, flexDirection: 'row', alignItems: 'center'},
  chip: {flex: 1, height: 40, marginHorizontal: 2, borderWidth: 2, borderColor: '#000000', borderRadius: 6, alignItems: 'center', justifyContent: 'center'},
  chipOn: {backgroundColor: '#000000'},
  chipText: {fontSize: 16, color: '#000000'},
  chipOnText: {color: '#ffffff'},
  buttons: {flexDirection: 'row', alignSelf: 'stretch', marginTop: 8},
  button: {flex: 1, marginHorizontal: 4, borderWidth: 2, borderColor: '#000000', borderRadius: 8, paddingVertical: 12, alignItems: 'center'},
  buttonMain: {flex: 2},
  buttonText: {fontSize: 19, fontWeight: '700', color: '#000000'},
  disabled: {opacity: 0.4},
});
