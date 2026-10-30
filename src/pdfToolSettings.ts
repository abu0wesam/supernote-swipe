import {FileUtils, PluginManager} from 'sn-plugin-lib';
import {entryName} from './settings';
import type {SkewMode} from './pdftool';

/**
 * The levels chosen in the page tool, kept between openings. Like the other settings of the plugin,
 * the value is stored as the NAME of an empty folder in the plugin's private directory
 * (the SDK has no call to write a text file): <pluginDir>/settings/pdftool/<encoded JSON>/
 */

export type PdfToolSettings = {
  width: number;
  height: number;
  dark: number;
  sharp: number;
  clean: number;
  skew: SkewMode;
  manual: number;
};

function clamp(value: unknown, low: number, high: number, fallback: number): number {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(high, Math.max(low, Math.round(n))) : fallback;
}

let baseDir: string | null = null;

async function dir(): Promise<string | null> {
  if (!baseDir) {
    const d = await PluginManager.getPluginDirPath();
    baseDir = d ? `${d}/settings/pdftool` : null;
  }
  return baseDir;
}

/** The saved levels, or null when nothing was saved (or it cannot be read): the defaults then stay. */
export async function loadPdfToolSettings(): Promise<PdfToolSettings | null> {
  try {
    const d = await dir();
    if (!d || !(await FileUtils.exists(d))) {
      return null;
    }
    const entries: unknown[] = (await FileUtils.listFiles(d)) ?? [];
    const name = entries.map(entryName).find((n): n is string => !!n);
    if (!name) {
      return null;
    }
    const v = JSON.parse(decodeURIComponent(name));
    if (!Array.isArray(v)) {
      return null;
    }
    const skew: SkewMode = v[5] === 'auto' || v[5] === 'manual' ? v[5] : 'off';
    const manual = Number(v[6]);
    return {
      width: clamp(v[0], 0, 10, 4),
      height: clamp(v[1], 0, 10, 2),
      dark: clamp(v[2], 1, 10, 3),
      sharp: clamp(v[3], 0, 10, 3),
      clean: clamp(v[4], 0, 10, 0),
      skew,
      manual: Number.isFinite(manual) ? Math.min(5, Math.max(-5, manual)) : 0,
    };
  } catch (e) {
    console.warn('[PDF Tool] loadPdfToolSettings', e);
    return null;
  }
}

let writes: Promise<void> = Promise.resolve();
let latest: PdfToolSettings | null = null;

/** Saves the levels; writes are done one after the other and only the newest value is kept. */
export function savePdfToolSettings(settings: PdfToolSettings) {
  latest = settings;
  const snapshot = settings;
  writes = writes
    .then(async () => {
      const d = await dir();
      if (!d || snapshot !== latest) {
        return;
      }
      const packed = [snapshot.width, snapshot.height, snapshot.dark, snapshot.sharp, snapshot.clean, snapshot.skew, snapshot.manual];
      await FileUtils.deleteDir(d);
      await FileUtils.makeDir(d);
      await FileUtils.makeDir(`${d}/${encodeURIComponent(JSON.stringify(packed))}`);
    })
    .catch(e => console.warn('[PDF Tool] savePdfToolSettings', e));
}
