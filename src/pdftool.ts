import {DeviceEventEmitter, NativeModules} from 'react-native';

/** Bridge to PdfToolModule (android/app/src/main/java/com/swipeswitch). */

export type SkewMode = 'off' | 'auto' | 'manual';

/** Levels chosen in the tool: dark 1..10, the others 0 (off) to 10. */
export type Levels = {dark: number; width: number; height: number; sharp: number; clean: number};

export type ApplyOptions = Levels & {threads: number; mode: SkewMode; manual: number};

export type ApplyResult = {pages: number; bytes: number; ms: number; threads: number};

type Bridge = {
  processImage(inPath: string, outPath: string, options: Levels & {rotate: number}): Promise<boolean>;
  detectSkew(inPath: string): Promise<number>;
  applyToPdf(path: string, backupPath: string, options: ApplyOptions): Promise<ApplyResult>;
  cancelApply(): Promise<boolean>;
  restore(path: string, backupPath: string): Promise<boolean>;
};

const native = NativeModules.PdfTool as Bridge | undefined;

/** False when this build of the plugin carries no native package. */
export const nativeAvailable = () => !!native;

function bridge(): Bridge {
  if (!native) {
    throw new Error('The native PDF module is missing from this build.');
  }
  return native;
}

/** Cleans, stretches, sharpens, darkens and straightens a picture of a page (the preview). */
export const processImage = (inPath: string, outPath: string, levels: Levels, rotate: number) =>
  bridge().processImage(inPath, outPath, {...levels, rotate});

export const detectSkew = (inPath: string) => bridge().detectSkew(inPath);

export const applyToPdf = (path: string, backupPath: string, options: ApplyOptions) =>
  bridge().applyToPdf(path, backupPath, options);

/** Stops applyToPdf at the next page; the original file stays as it was. applyToPdf then fails with code E_CANCELLED. */
export const cancelApply = () => bridge().cancelApply();

export const restoreBackup = (path: string, backupPath: string) => bridge().restore(path, backupPath);

/** Progress of applyToPdf, page by page. Returns the function that stops listening. */
export function onProgress(fn: (done: number, total: number) => void): () => void {
  const subscription = DeviceEventEmitter.addListener('PdfToolProgress', (e: {done: number; total: number}) =>
    fn(e.done, e.total),
  );
  return () => subscription.remove();
}

/** Where the original of a PDF is kept: one file per PDF path, in the plugin's own folder. */
export function backupPathFor(pluginDir: string, pdfPath: string): string {
  let hash = 5381;
  for (let i = 0; i < pdfPath.length; i++) {
    hash = ((hash * 33) ^ pdfPath.charCodeAt(i)) >>> 0;
  }
  return `${pluginDir}/backups/${hash.toString(16)}.pdf`;
}
