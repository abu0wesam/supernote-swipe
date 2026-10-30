import {DeviceEventEmitter, NativeModules} from 'react-native';

/** Bridge to PdfToolModule (android/app/src/main/java/com/swipeswitch). */

export type SkewMode = 'off' | 'auto' | 'manual';

export type ApplyResult = {pages: number; bytes: number; ms: number};

type Bridge = {
  processImage(
    inPath: string,
    outPath: string,
    darkLevel: number,
    rotateDeg: number,
    widthLevel: number,
    heightLevel: number,
  ): Promise<boolean>;
  detectSkew(inPath: string): Promise<number>;
  applyToPdf(
    path: string,
    backupPath: string,
    widthLevel: number,
    heightLevel: number,
    darkLevel: number,
    mode: SkewMode,
    manualDeg: number,
  ): Promise<ApplyResult>;
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

/** Straightens, stretches the text, sharpens and darkens a picture of a page (the preview). */
export const processImage = (
  inPath: string,
  outPath: string,
  darkLevel: number,
  rotateDeg: number,
  widthLevel: number,
  heightLevel: number,
) => bridge().processImage(inPath, outPath, darkLevel, rotateDeg, widthLevel, heightLevel);

export const detectSkew = (inPath: string) => bridge().detectSkew(inPath);

export const applyToPdf = (
  path: string,
  backupPath: string,
  widthLevel: number,
  heightLevel: number,
  darkLevel: number,
  mode: SkewMode,
  manualDeg: number,
) => bridge().applyToPdf(path, backupPath, widthLevel, heightLevel, darkLevel, mode, manualDeg);

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
