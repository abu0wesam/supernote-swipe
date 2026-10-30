import {Dimensions, PixelRatio} from 'react-native';
import {PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {FlipDirection, FlipTracker, targetPage} from './flip';
import {Motion} from './gesture';
import {kindOf} from './recent';

/** Horizontal travel needed, as a share of the (short side of the) screen. */
const MIN_DISTANCE_RATIO = 0.12;
/** Ignore a new flip right after one. */
const COOLDOWN_MS = 500;

function shortSidePx(): number {
  const s = Dimensions.get('screen');
  const px = Math.min(s.width, s.height) * PixelRatio.get();
  return px > 0 ? px : 1404;
}

const tracker = new FlipTracker(() => ({
  minDistance: shortSidePx() * MIN_DISTANCE_RATIO,
  maxDurationMs: 800,
  maxSlope: 0.6,
  penQuietMs: 400,
  leftToRightIsNext: true,
}));

function numberOf(res: any): number | null {
  return res?.success && typeof res.result === 'number' ? res.result : null;
}

let busy = false;
let lastFlipAt = 0;

async function flip(dir: FlipDirection) {
  if (busy || Date.now() - lastFlipAt < COOLDOWN_MS) {
    return;
  }
  busy = true;
  try {
    const comm: any = PluginCommAPI;
    const pathRes: any = await comm.getCurrentFilePath();
    const path: string | null = pathRes?.success ? pathRes.result ?? null : null;
    const current = numberOf(await comm.getCurrentPageNum());
    if (!path || current === null) {
      return;
    }
    let total: number | null = null;
    try {
      total =
        kindOf(path) === 'note'
          ? numberOf(await (PluginFileAPI as any).getNoteTotalPageNum(path))
          : numberOf(await (PluginDocAPI as any).getCurrentTotalPages());
    } catch {
      total = null; // unknown: jumpToPage refuses an invalid page itself
    }
    const target = targetPage(current, total, dir);
    if (target === null) {
      return;
    }
    await comm.jumpToPage(target);
    lastFlipAt = Date.now();
  } catch (e) {
    console.warn('[Swipe] flip', e);
  } finally {
    busy = false;
  }
}

/** One-finger horizontal swipe: left-to-right = next page, right-to-left = previous page. */
export function startPageFlip() {
  PluginManager.registerMotionListener(1, {
    onMsg(msg: unknown) {
      const e = msg as Motion;
      if (!e || !Array.isArray(e.pointers)) {
        return;
      }
      const dir = tracker.feed(e);
      if (dir) {
        flip(dir);
      }
    },
  });
}
