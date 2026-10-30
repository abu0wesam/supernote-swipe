/**
 * Page tool: shows the page open in the reader and lets the user choose how
 * much to widen it (5 levels) and how much to darken it (10 levels).
 *
 * @format
 */

import React, {useEffect, useState} from 'react';
import {Dimensions, Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {FileUtils, NativeUIUtils, PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {DARK_LEVELS, MANTA_ASPECT, WIDTH_LEVELS, fillScale, widthScale} from './src/convert';
import {PageInfo, pageInfo} from './src/pageflip';

type Preview = {uri: string; aspect: number; detail: string};

let lastPreviewPath: string | null = null;

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

async function renderPreview(path: string, page: number): Promise<Preview> {
  const dir = await PluginManager.getPluginDirPath();
  if (!dir) {
    throw new Error('The plugin folder is not available.');
  }
  if (lastPreviewPath) {
    try {
      await FileUtils.deleteFile(lastPreviewPath);
    } catch {
      // already gone
    }
  }
  const real = await readPageSize(path, page);
  // Ask for an image with the page's own proportions, so the picture is not distorted.
  const width = 960;
  const height = real ? Math.min(5120, Math.max(1, Math.round((width * real.h) / real.w))) : 1280;
  const out = `${dir}/preview_${Date.now()}.png`;
  const res: any = await (PluginDocAPI as any).generateCurrentDocImage(page, out, {width, height}, 0);
  if (!res?.success) {
    throw new Error(res?.error?.message ?? 'Could not draw the page.');
  }
  lastPreviewPath = out;
  const uri = `file://${out}`;
  const size = await new Promise<{width: number; height: number}>((resolve, reject) =>
    Image.getSize(uri, (w, h) => resolve({width: w, height: h}), reject),
  );
  const aspect = real ? real.w / real.h : size.width / size.height;
  const detail = real
    ? `Page ${Math.round(real.w)}\u00d7${Math.round(real.h)} (${real.source}) \u00b7 proportion ${aspect.toFixed(3)}`
    : `Page size unknown \u00b7 image ${size.width}\u00d7${size.height}`;
  return {uri, aspect, detail};
}

function LevelRow({count, first, value, onPick}: {count: number; first: number; value: number; onPick: (n: number) => void}) {
  return (
    <View style={styles.levels}>
      {Array.from({length: count}, (_, i) => first + i).map(n => (
        <Pressable key={n} style={[styles.level, n === value && styles.levelOn]} onPress={() => onPick(n)}>
          <Text style={[styles.levelText, n === value && styles.levelOnText]}>{n}</Text>
        </Pressable>
      ))}
    </View>
  );
}

export default function ConvertScreen(): React.JSX.Element {
  const [info, setInfo] = useState<PageInfo | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [widthLevel, setWidthLevel] = useState(2); // 0..4
  const [darkLevel, setDarkLevel] = useState(3); // 1..10

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
        const p = await renderPreview(i.path, i.current);
        if (alive) {
          setPreview(p);
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

  const win = Dimensions.get('window');
  const boxH = Math.min(win.height * 0.5, (win.width - 64) / MANTA_ASPECT);
  const boxW = boxH * MANTA_ASPECT;
  const pageAspect = preview ? preview.aspect : MANTA_ASPECT;
  const fill = fillScale(pageAspect, MANTA_ASPECT);
  const scale = widthScale(widthLevel, fill);
  const fitsHeight = pageAspect <= MANTA_ASPECT;
  const baseW = fitsHeight ? boxH * pageAspect : boxW;
  const baseH = fitsHeight ? boxH : boxW / pageAspect;
  const alreadyWide = !!preview && fill <= 1.001;

  const generate = () => {
    NativeUIUtils.showRattaDialog(
      `Width level ${widthLevel + 1}/${WIDTH_LEVELS}, darkness ${darkLevel}/${DARK_LEVELS}.\nCreating the new PDF comes in the next build.`,
      '',
      'OK',
      false,
    ).catch(() => undefined);
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

      <View style={[styles.screen, {width: boxW, height: boxH}]}>
        {preview ? (
          <Image source={{uri: preview.uri}} resizeMode="stretch" style={{width: baseW * scale, height: baseH}} />
        ) : (
          <Text style={styles.hint}>{error ? '' : 'Drawing the page\u2026'}</Text>
        )}
      </View>
      {preview ? (
        <Text style={styles.diag}>
          {preview.detail} \u00b7 screen {MANTA_ASPECT.toFixed(3)} \u00b7 full width \u00d7{fill.toFixed(3)}
        </Text>
      ) : null}
      {alreadyWide ? (
        <Text style={styles.hint}>This page is already as wide as the screen, so the width levels change nothing.</Text>
      ) : null}

      <Text style={styles.label}>Width: level {widthLevel + 1} of {WIDTH_LEVELS} (+{Math.round((scale - 1) * 100)}%)</Text>
      <LevelRow count={WIDTH_LEVELS} first={0} value={widthLevel} onPick={setWidthLevel} />

      <Text style={styles.label}>Darkness: level {darkLevel} of {DARK_LEVELS}</Text>
      <LevelRow count={DARK_LEVELS} first={1} value={darkLevel} onPick={setDarkLevel} />
      <Text style={styles.hint}>The darkness is applied when the new PDF is created; the preview shows the width only.</Text>

      <Pressable style={styles.generate} onPress={generate}>
        <Text style={styles.generateText}>Create the new PDF</Text>
      </Pressable>
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
  levels: {flexDirection: 'row', flexWrap: 'wrap', alignSelf: 'stretch', marginTop: 8},
  level: {borderWidth: 2, borderColor: '#000000', borderRadius: 8, minWidth: 56, height: 52, marginRight: 8, marginBottom: 8, alignItems: 'center', justifyContent: 'center'},
  levelOn: {backgroundColor: '#000000'},
  levelText: {fontSize: 21, color: '#000000'},
  levelOnText: {color: '#ffffff'},
  generate: {marginTop: 24, borderWidth: 2, borderColor: '#000000', borderRadius: 10, paddingVertical: 14, paddingHorizontal: 28},
  generateText: {fontSize: 22, fontWeight: '700', color: '#000000'},
});
