/**
 * Page tool: shows the page open in the reader and lets the user choose how
 * far to move the text towards the page edges, sideways and up-and-down on
 * their own (10 levels each), how much to darken it (10 levels) and whether to
 * straighten tilted scans. The result can be applied to the same file; the
 * original is kept and can be restored.
 *
 * @format
 */

import React, {useEffect, useRef, useState} from 'react';
import {Dimensions, Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {FileUtils, NativeUIUtils, PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {DARK_LEVELS, MANTA_ASPECT} from './src/convert';
import {PageInfo, pageInfo} from './src/pageflip';
import {
  SkewMode,
  applyToPdf,
  backupPathFor,
  detectSkew,
  nativeAvailable,
  onProgress,
  processImage,
  restoreBackup,
} from './src/pdftool';

type Raw = {path: string; dir: string; aspect: number; detail: string};

let lastRawPath: string | null = null;

/** Height, in pixels, of the page picture: the same as the pages of the new PDF (the Manta screen's height). */
const PAGE_PIXELS = 2560;

/** Levels of each stretch: 0 = off, 10 = to the edge margin. */
const STRETCH_LEVELS = 10;

/** The real size of a page: the file API first, then the size the reader shows. */
async function readPageSize(path: string, page: number): Promise<{w: number; h: number; source: string} | null> {
  const tries: [string, () => Promise<any>][] = [
    ['getPageSize', () => (PluginFileAPI as any).getPageSize(path, page)],
    ['getPageDisplaySize', () => (PluginCommAPI as any).getPageDisplaySize()],
  ];
  for (const [source, call] of tries) {
    try {
      const res = await call();
      const w = Number(res?.result?.width);
      const h = Number(res?.result?.height);
      if (res?.success && w > 0 && h > 0) {
        return {w, h, source};
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
  const aspect = width / height;
  const detail = real
    ? `Page ${Math.round(real.w)}\u00d7${Math.round(real.h)} (${real.source}) \u00b7 proportion ${aspect.toFixed(3)}`
    : `Page size unknown \u00b7 picture ${width}\u00d7${height}`;
  return {path: out, dir, aspect, detail};
}

function LevelRow({
  count,
  first,
  value,
  onPick,
  zeroLabel,
}: {
  count: number;
  first: number;
  value: number;
  onPick: (n: number) => void;
  zeroLabel?: string;
}) {
  return (
    <View style={styles.levels}>
      {Array.from({length: count}, (_, i) => first + i).map(n => (
        <Pressable key={n} style={[styles.level, n === value && styles.levelOn]} onPress={() => onPick(n)}>
          <Text style={[styles.levelText, n === value && styles.levelOnText]}>{n === 0 && zeroLabel ? zeroLabel : n}</Text>
        </Pressable>
      ))}
    </View>
  );
}

function Chip({label, on, onPress}: {label: string; on: boolean; onPress: () => void}) {
  return (
    <Pressable style={[styles.chip, on && styles.levelOn]} onPress={onPress}>
      <Text style={[styles.levelText, on && styles.levelOnText]}>{label}</Text>
    </Pressable>
  );
}

export default function ConvertScreen(): React.JSX.Element {
  const [info, setInfo] = useState<PageInfo | null>(null);
  const [raw, setRaw] = useState<Raw | null>(null);
  const [procPath, setProcPath] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [widthLevel, setWidthLevel] = useState(4); // 0..10
  const [heightLevel, setHeightLevel] = useState(2); // 0..10
  const [darkLevel, setDarkLevel] = useState(3); // 1..10
  const [skew, setSkew] = useState<SkewMode>('off');
  const [manualDeg, setManualDeg] = useState(0);
  const [detected, setDetected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const [hasBackup, setHasBackup] = useState(false);
  const procRef = useRef<string | null>(null);

  const native = nativeAvailable();
  const isPdf = !!info && info.path.toLowerCase().endsWith('.pdf');

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

  // The preview with the real stretching, darkening and straightening, a moment after a level changes.
  useEffect(() => {
    if (!raw || !native) {
      return;
    }
    let alive = true;
    const timer = setTimeout(async () => {
      try {
        const out = `${raw.dir}/proc_${Date.now()}.png`;
        await processImage(raw.path, out, darkLevel, degrees, widthLevel, heightLevel);
        if (!alive) {
          await removeFile(out);
          return;
        }
        const previous = procRef.current;
        procRef.current = out;
        setProcPath(out);
        await removeFile(previous);
      } catch (e: any) {
        if (alive) {
          setMessage(`Preview failed: ${e?.message ?? e}`);
        }
      }
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [raw, native, darkLevel, degrees, widthLevel, heightLevel]);

  const win = Dimensions.get('window');
  const boxH = Math.min(win.height * 0.46, (win.width - 64) / MANTA_ASPECT);
  const boxW = boxH * MANTA_ASPECT;
  const pageAspect = raw ? raw.aspect : MANTA_ASPECT;
  const fitsHeight = pageAspect <= MANTA_ASPECT;
  const baseW = fitsHeight ? boxH * pageAspect : boxW;
  const baseH = fitsHeight ? boxH : boxW / pageAspect;
  const shownUri = procPath ? `file://${procPath}` : raw ? `file://${raw.path}` : null;

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
      'Replace this file with the adjusted version?\nA copy of the original is kept, and "Restore original" brings it back.\nPages become images and a long book takes several minutes.',
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
      const res = await applyToPdf(info.path, backup, widthLevel, heightLevel, darkLevel, skew, manualDeg);
      setHasBackup(true);
      await (PluginFileAPI as any).openFile(info.path, info.current);
      const seconds = res.ms / 1000;
      setMessage(
        `Done: ${res.pages} pages in ${seconds.toFixed(0)} s (${(seconds / Math.max(1, res.pages)).toFixed(2)} s per page), ` +
          `${(res.bytes / (1024 * 1024)).toFixed(1)} MB. The file is reloaded in the reader.`,
      );
    } catch (e: any) {
      setMessage(`Failed: ${e?.message ?? e}`);
    } finally {
      stop();
      setBusy(false);
      setProgress('');
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

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text style={styles.title}>PDF Tool</Text>
        <Pressable onPress={() => PluginManager.closePluginView()} style={styles.close}>
          <Text style={styles.title}>{'\u2715'}</Text>
        </Pressable>
      </View>
      {info ? (
        <Text style={styles.hint}>
          Page {info.current + 1}
          {info.total ? ` of ${info.total}` : ''}
        </Text>
      ) : null}
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {!native ? <Text style={styles.error}>This build has no native PDF module: the preview and applying are unavailable.</Text> : null}

      <View style={[styles.screen, {width: boxW, height: boxH}]}>
        {shownUri ? (
          <Image source={{uri: shownUri}} resizeMode="stretch" style={{width: baseW, height: baseH}} />
        ) : (
          <Text style={styles.hint}>{error ? '' : 'Drawing the page\u2026'}</Text>
        )}
      </View>
      {raw ? <Text style={styles.diag}>{raw.detail}</Text> : null}

      <Text style={styles.label}>Stretch sideways: {widthLevel === 0 ? 'off' : `${widthLevel} of ${STRETCH_LEVELS}`}</Text>
      <LevelRow count={STRETCH_LEVELS + 1} first={0} value={widthLevel} onPick={setWidthLevel} zeroLabel="Off" />

      <Text style={styles.label}>Stretch up and down: {heightLevel === 0 ? 'off' : `${heightLevel} of ${STRETCH_LEVELS}`}</Text>
      <LevelRow count={STRETCH_LEVELS + 1} first={0} value={heightLevel} onPick={setHeightLevel} zeroLabel="Off" />
      <Text style={styles.hint}>
        Each one moves the text that far towards the page edge (10 = to the margin). The page keeps its size. Set them
        separately: books differ in proportions.
      </Text>

      <Text style={styles.label}>Darkness: level {darkLevel} of {DARK_LEVELS}</Text>
      <LevelRow count={DARK_LEVELS} first={1} value={darkLevel} onPick={setDarkLevel} />

      <Text style={styles.label}>Straighten tilted pages</Text>
      <View style={styles.levels}>
        <Chip label="Off" on={skew === 'off'} onPress={() => setSkew('off')} />
        <Chip label="Auto" on={skew === 'auto'} onPress={() => setSkew('auto')} />
        <Chip label="Manual" on={skew === 'manual'} onPress={() => setSkew('manual')} />
      </View>
      {skew === 'auto' ? (
        <Text style={styles.hint}>
          {detected === null ? 'Measuring this page\u2026' : `This page: ${detected.toFixed(1)}\u00b0. Every page is measured on its own.`}
        </Text>
      ) : null}
      {skew === 'manual' ? (
        <View style={styles.levels}>
          <Chip label="\u22121\u00b0" on={false} onPress={() => setManualDeg(d => Math.max(-5, +(d - 1).toFixed(2)))} />
          <Chip label="\u22120.25\u00b0" on={false} onPress={() => setManualDeg(d => Math.max(-5, +(d - 0.25).toFixed(2)))} />
          <Text style={styles.big}>{manualDeg.toFixed(2)}\u00b0</Text>
          <Chip label="+0.25\u00b0" on={false} onPress={() => setManualDeg(d => Math.min(5, +(d + 0.25).toFixed(2)))} />
          <Chip label="+1\u00b0" on={false} onPress={() => setManualDeg(d => Math.min(5, +(d + 1).toFixed(2)))} />
        </View>
      ) : null}
      <Text style={styles.hint}>Positive = turn clockwise. The same angle is used for every page in manual mode.</Text>

      {busy ? <Text style={styles.label}>Working: {progress}</Text> : null}
      {message ? <Text style={styles.error}>{message}</Text> : null}

      <Pressable style={[styles.generate, (!native || busy) && styles.disabled]} onPress={apply} disabled={!native || busy}>
        <Text style={styles.generateText}>Apply to this file</Text>
      </Pressable>
      {hasBackup ? (
        <Pressable style={[styles.generate, busy && styles.disabled]} onPress={restore} disabled={busy}>
          <Text style={styles.generateText}>Restore original</Text>
        </Pressable>
      ) : null}
      <Text style={styles.hint}>Handwriting you already wrote on this PDF may move when the page is changed.</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#ffffff'},
  content: {padding: 32, alignItems: 'center'},
  header: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', alignSelf: 'stretch'},
  title: {fontSize: 30, fontWeight: '700', color: '#000000'},
  close: {padding: 8},
  hint: {fontSize: 17, color: '#444444', marginVertical: 8, textAlign: 'center'},
  diag: {fontSize: 15, color: '#444444', marginBottom: 4, fontFamily: 'monospace', textAlign: 'center'},
  error: {fontSize: 19, color: '#000000', marginVertical: 12, textAlign: 'center'},
  screen: {borderWidth: 2, borderColor: '#000000', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', marginVertical: 12},
  label: {fontSize: 21, color: '#000000', marginTop: 16, alignSelf: 'flex-start'},
  big: {fontSize: 24, fontWeight: '700', color: '#000000', marginHorizontal: 12},
  levels: {flexDirection: 'row', flexWrap: 'wrap', alignSelf: 'stretch', marginTop: 8, alignItems: 'center'},
  level: {borderWidth: 2, borderColor: '#000000', borderRadius: 8, minWidth: 56, height: 52, marginRight: 8, marginBottom: 8, alignItems: 'center', justifyContent: 'center'},
  chip: {borderWidth: 2, borderColor: '#000000', borderRadius: 8, paddingHorizontal: 16, height: 52, marginRight: 8, marginBottom: 8, alignItems: 'center', justifyContent: 'center'},
  levelOn: {backgroundColor: '#000000'},
  levelText: {fontSize: 21, color: '#000000'},
  levelOnText: {color: '#ffffff'},
  generate: {marginTop: 20, borderWidth: 2, borderColor: '#000000', borderRadius: 10, paddingVertical: 14, paddingHorizontal: 28},
  disabled: {opacity: 0.4},
  generateText: {fontSize: 22, fontWeight: '700', color: '#000000'},
});
