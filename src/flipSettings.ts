import {FileUtils, PluginManager} from 'sn-plugin-lib';
import {entryName} from './settings';

/**
 * Settings of the one-finger page flip, persisted like the gesture settings:
 * the JSON is the NAME of an empty folder in the plugin's private directory.
 */

export type FlipDistance = 'short' | 'normal' | 'long';

export type FlipSettings = {
  enabled: boolean;
  /** false: left-to-right = next page (Arabic books). true: the opposite. */
  reversed: boolean;
  distance: FlipDistance;
  /** Flipping forward on the last page of a note adds a new page. */
  newPage: boolean;
};

export const FLIP_DEFAULTS: FlipSettings = {enabled: true, reversed: false, distance: 'normal', newPage: true};

/** Horizontal travel needed, as a share of the short side of the screen. */
export const DISTANCE_RATIO: Record<FlipDistance, number> = {short: 0.08, normal: 0.12, long: 0.18};

let current: FlipSettings = {...FLIP_DEFAULTS};
const listeners = new Set<() => void>();

export const getFlipSettings = () => current;

export function subscribeFlip(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

let baseDir: string | null = null;

async function dir(): Promise<string | null> {
  if (!baseDir) {
    const d = await PluginManager.getPluginDirPath();
    baseDir = d ? `${d}/settings/flip` : null;
  }
  return baseDir;
}

export async function loadFlipSettings() {
  try {
    const d = await dir();
    if (!d || !(await FileUtils.exists(d))) {
      return;
    }
    const entries: unknown[] = (await FileUtils.listFiles(d)) ?? [];
    const name = entries.map(entryName).find((n): n is string => !!n);
    if (!name) {
      return;
    }
    current = {...FLIP_DEFAULTS, ...JSON.parse(decodeURIComponent(name))};
    listeners.forEach(fn => fn());
  } catch (e) {
    console.warn('[PDF Tool] loadFlipSettings', e);
  }
}

let writes: Promise<void> = Promise.resolve();

export function updateFlip(patch: Partial<FlipSettings>) {
  current = {...current, ...patch};
  listeners.forEach(fn => fn());
  const snapshot = current;
  writes = writes
    .then(async () => {
      const d = await dir();
      if (!d || snapshot !== current) {
        return;
      }
      await FileUtils.deleteDir(d);
      await FileUtils.makeDir(d);
      await FileUtils.makeDir(`${d}/${encodeURIComponent(JSON.stringify(snapshot))}`);
    })
    .catch(e => console.warn('[PDF Tool] saveFlipSettings', e));
}
