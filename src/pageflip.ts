import {Dimensions, PixelRatio} from 'react-native';
import {
  FileUtils,
  NativeUIUtils,
  PluginCommAPI,
  PluginDocAPI,
  PluginFileAPI,
  PluginManager,
  PluginNoteAPI,
} from 'sn-plugin-lib';
import {FlipDirection, FlipTracker, targetPage} from './flip';
import {DISTANCE_RATIO, getFlipSettings} from './flipSettings';
import {Motion} from './gesture';
import {kindOf} from './recent';
import {ACTIONS} from './settings';
import {lastGesture, subscribeGesture} from './switcher';
import {showView} from './view';

/** Ignore a new flip right after one. */
const COOLDOWN_MS = 500;
/** Where custom templates (PDFs or images used as templates) live on the device. */
const MY_STYLE = '/storage/emulated/0/MyStyle';

function shortSidePx(): number {
  const s = Dimensions.get('screen');
  const px = Math.min(s.width, s.height) * PixelRatio.get();
  return px > 0 ? px : 1404;
}

const tracker = new FlipTracker(() => {
  const s = getFlipSettings();
  return {
    minDistance: shortSidePx() * DISTANCE_RATIO[s.distance],
    maxDurationMs: 800,
    maxSlope: 0.6,
    penQuietMs: 400,
    leftToRightIsNext: !s.reversed,
  };
});

function numberOf(res: any): number | null {
  return res?.success && typeof res.result === 'number' ? res.result : null;
}

async function notify(message: string) {
  try {
    await NativeUIUtils.showRattaDialog(message, '', 'OK', false);
  } catch {
    console.warn('[PDF Tool]', message);
  }
}

export type PageInfo = {path: string; current: number; total: number | null};

/** The displayed file, its current page (0-indexed) and its page count (null if unknown). */
export async function pageInfo(): Promise<PageInfo | null> {
  const comm: any = PluginCommAPI;
  const pathRes: any = await comm.getCurrentFilePath();
  const path: string | null = pathRes?.success ? pathRes.result ?? null : null;
  const current = numberOf(await comm.getCurrentPageNum());
  if (!path || current === null) {
    return null;
  }
  let total: number | null = null;
  try {
    total =
      kindOf(path) === 'note'
        ? numberOf(await (PluginFileAPI as any).getNoteTotalPageNum(path))
        : numberOf(await (PluginDocAPI as any).getCurrentTotalPages());
  } catch {
    total = null;
  }
  return {path, current, total};
}

/** Jumps to a page (0-indexed) of the displayed file. */
export async function goToPage(page: number): Promise<void> {
  try {
    await (PluginCommAPI as any).jumpToPage(page);
  } catch (e) {
    console.warn('[PDF Tool] goToPage', e);
  }
}

async function ensureWritePermission(): Promise<boolean> {
  const permission = 'plugin.permission.FILE:WRITE';
  if ((await PluginManager.hasPermission(permission)) >= 1) {
    return true;
  }
  const choice = await PluginManager.requestPermission(permission, 'PDF Tool adds a new page when you flip past the last page of a note.');
  return choice === 1 || choice === 2;
}

/**
 * Exports the template of a page as a PNG in the plugin's private directory.
 * A custom template (a PDF or image chosen by the user) cannot be named in
 * insertNotePage, but an image path can: the new page then looks the same.
 */
async function exportTemplatePng(path: string, page: number): Promise<string | null> {
  try {
    const dir = await PluginManager.getPluginDirPath();
    if (!dir) {
      return null;
    }
    const out = `${dir}/last_template.png`;
    try {
      await FileUtils.deleteFile(out);
    } catch {
      // nothing to delete
    }
    const res: any = await (PluginFileAPI as any).generateNoteTemplatePng(path, page, out);
    return res?.success && res.result !== false ? out : null;
  } catch (e) {
    console.warn('[PDF Tool] exportTemplatePng', e);
    return null;
  }
}

/**
 * Adds an empty page AFTER the last page of the open note, with the same
 * template as the last page, and shows it. The page number given to
 * insertNotePage is the position of the NEW page.
 */
async function appendPage(path: string, total: number): Promise<boolean> {
  if (!(await ensureWritePermission())) {
    return false;
  }
  const note: any = PluginNoteAPI;
  const file: any = PluginFileAPI;
  const comm: any = PluginCommAPI;
  const saved = await note.saveCurrentNote();
  if (!saved?.success || saved.result === false) {
    return false; // never touch the file while the note holds unsaved writing
  }

  let wanted: string | null = null;
  let md5 = '';
  try {
    const src: any = await file.getNotePageTemplate(path, total - 1);
    if (src?.success && typeof src.result?.name === 'string' && src.result.name) {
      wanted = src.result.name;
      md5 = String(src.result.md5 ?? '');
    }
  } catch {
    wanted = null;
  }

  // Attempts in order. A custom template (md5 other than 0) is reused through its exported image.
  const attempts: {template: string; verify: boolean}[] = [];
  const custom = md5 !== '' && md5 !== '0';
  if (custom) {
    const png = await exportTemplatePng(path, total - 1);
    if (png) {
      attempts.push({template: png, verify: false});
    }
  }
  if (wanted) {
    const base = wanted.replace(/^user_pdf_\d+_/, '');
    attempts.push({template: wanted, verify: true});
    attempts.push({template: `${MY_STYLE}/${wanted}`, verify: true});
    attempts.push({template: `${MY_STYLE}/${wanted}.png`, verify: true});
    attempts.push({template: `${MY_STYLE}/${base}.pdf`, verify: true});
    attempts.push({template: `${MY_STYLE}/${base}.png`, verify: true});
  }
  for (const attempt of attempts) {
    const res = await file.insertNotePage({notePath: path, page: total, template: attempt.template});
    if (!res?.success || !res.result) {
      continue;
    }
    if (attempt.verify) {
      const check: any = await file.getNotePageTemplate(path, total);
      if (!(check?.success && check.result?.name === wanted)) {
        await file.removeNotePage(path, total); // not the same template: undo and try the next form
        continue;
      }
    }
    await comm.reloadFile();
    await comm.jumpToPage(total);
    return true;
  }

  const res = await file.insertNotePage({notePath: path, page: total, template: 'style_white'});
  if (!res?.success || !res.result) {
    await notify('Could not add a new page to this note.');
    return false;
  }
  await comm.reloadFile();
  await comm.jumpToPage(total);
  await notify(`New page added with a blank template: the template of the last page could not be reused.\nTemplate: ${wanted ?? 'unknown'} (md5 ${md5 || '?'})`);
  return true;
}

let busy = false;
let lastFlipAt = 0;

async function flip(dir: FlipDirection) {
  if (busy || Date.now() - lastFlipAt < COOLDOWN_MS) {
    return;
  }
  busy = true;
  try {
    const info = await pageInfo();
    if (!info) {
      return;
    }
    const target = targetPage(info.current, info.total, dir);
    if (target !== null) {
      await goToPage(target);
      lastFlipAt = Date.now();
      return;
    }
    const atEnd = dir === 'next' && info.total !== null && info.current >= info.total - 1;
    if (atEnd && kindOf(info.path) === 'note' && getFlipSettings().newPage) {
      if (await appendPage(info.path, info.total as number)) {
        lastFlipAt = Date.now();
      }
    }
  } catch (e) {
    console.warn('[PDF Tool] flip', e);
  } finally {
    busy = false;
  }
}

// ---------------------------------------------------------------------------
// "Go to page" action for the multi-finger gestures
// ---------------------------------------------------------------------------

let gotoInfo: PageInfo | null = null;
let gotoStamp = 0;

export const getGotoInfo = () => gotoInfo;
export const gotoVersion = () => gotoStamp;

/**
 * Adds "Go to page..." to the actions a gesture can have. The multi-finger
 * detector does nothing for an action it does not know, so this module opens
 * the page picker itself when a gesture with that action is recognized.
 */
function registerGotoAction() {
  const list = ACTIONS as unknown as {id: string; label: string}[];
  if (!list.some(a => a.id === 'goto')) {
    list.push({id: 'goto', label: 'Go to page...'});
  }
  subscribeGesture(() => {
    if (!lastGesture.endsWith('\u2192 goto')) {
      return;
    }
    pageInfo()
      .then(info => {
        gotoInfo = info;
        gotoStamp += 1;
        showView('goto');
      })
      .catch(e => console.warn('[PDF Tool] goto', e));
  });
}

/** One-finger horizontal swipe flips the page; the direction and length come from the settings. */
export function startPageFlip() {
  registerGotoAction();
  PluginManager.registerMotionListener(1, {
    onMsg(msg: unknown) {
      const e = msg as Motion;
      if (!e || !Array.isArray(e.pointers) || !getFlipSettings().enabled) {
        return;
      }
      const dir = tracker.feed(e);
      if (dir) {
        flip(dir);
      }
    },
  });
}
