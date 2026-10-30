import {NativeModules} from 'react-native';
import {PluginManager} from 'sn-plugin-lib';
import {buildCss, normalize} from './textCss';
import type {TextSettings} from './textCss';

type EpubBridge = {
  inspect(path: string): Promise<{chapters: number}>;
  exportCopy(path: string, output: string, css: string): Promise<{path: string; chapters: number; bytes: number}>;
  cancel(): Promise<boolean>;
  loadProfile(dir: string, path: string): Promise<string | null>;
  saveProfile(dir: string, path: string, json: string): Promise<boolean>;
};

const native = NativeModules.EpubTool as EpubBridge | undefined;

export const textAvailable = () => !!native;

function epubModule(): EpubBridge {
  if (!native) {
    throw new Error('Native EPUB module is missing. Rebuild the plugin.');
  }
  return native;
}

export const inspectEpub = (path: string) => epubModule().inspect(path);

export const cancelText = () => epubModule().cancel();

/** Creates a separately named copy beside the book; the original is never overwritten. */
export const exportTextCopy = (path: string, settings: TextSettings) => {
  const output = path.replace(/\.epub$/i, '') + `_Text_${Date.now()}.epub`;
  return epubModule().exportCopy(path, output, buildCss(settings));
};

export async function loadTextSettings(path: string): Promise<TextSettings> {
  try {
    const dir = await PluginManager.getPluginDirPath();
    if (!dir) {
      return normalize();
    }
    const json = await epubModule().loadProfile(dir, path);
    return normalize(json ? JSON.parse(json) : undefined);
  } catch (error) {
    console.warn('[Text] Could not load saved settings; using original formatting.', error);
    return normalize();
  }
}

export async function saveTextSettings(path: string, settings: TextSettings) {
  const dir = await PluginManager.getPluginDirPath();
  if (!dir) {
    throw new Error('Plugin folder is unavailable.');
  }
  await epubModule().saveProfile(dir, path, JSON.stringify(normalize(settings)));
}
