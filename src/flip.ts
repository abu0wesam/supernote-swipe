import {ACTION_CANCEL, ACTION_DOWN, ACTION_UP, Motion, TOOL_PEN} from './gesture';

/**
 * One-finger horizontal swipe -> page flip. SDK-free so it can be unit-tested.
 */

export type FlipDirection = 'next' | 'previous';

export type FlipOptions = {
  /** Minimum horizontal travel (px). */
  minDistance: number;
  /** Maximum time between finger down and finger up (ms). */
  maxDurationMs: number;
  /** Allowed vertical drift, as a fraction of the horizontal travel. */
  maxSlope: number;
  /** No flip while the pen was used in the last N ms (resting palm). */
  penQuietMs: number;
  /** Arabic books: a left-to-right swipe goes to the next page. */
  leftToRightIsNext: boolean;
};

export function classifyFlip(dx: number, dy: number, durationMs: number, o: FlipOptions): FlipDirection | null {
  if (durationMs > o.maxDurationMs || Math.abs(dx) < o.minDistance) {
    return null;
  }
  if (Math.abs(dy) > o.maxSlope * Math.abs(dx)) {
    return null;
  }
  const rightwards = dx > 0;
  return rightwards === o.leftToRightIsNext ? 'next' : 'previous';
}

/** Target page (0-indexed), or null when there is no such page. `total` null = unknown. */
export function targetPage(current: number, total: number | null, dir: FlipDirection): number | null {
  const target = dir === 'next' ? current + 1 : current - 1;
  if (target < 0 || (total !== null && target >= total)) {
    return null;
  }
  return target;
}

export class FlipTracker {
  private active = false;
  private startX = 0;
  private startY = 0;
  private lastX = 0;
  private lastY = 0;
  private startTime = 0;
  private lastPenAt = -Infinity;

  constructor(private readonly options: () => FlipOptions) {}

  /** Returns the flip at the moment the finger lifts, else null. */
  feed(e: Motion): FlipDirection | null {
    const o = this.options();
    const action = e.action & 0xff;
    const pointers = e.pointers ?? [];

    if (e.toolType === TOOL_PEN || pointers.some(p => p.toolType === TOOL_PEN)) {
      this.lastPenAt = e.eventTime;
      this.active = false;
      return null;
    }
    if (action === ACTION_DOWN) {
      this.active = pointers.length === 1 && e.pointerCount <= 1 && e.eventTime - this.lastPenAt > o.penQuietMs;
      if (this.active) {
        this.startX = this.lastX = pointers[0].x;
        this.startY = this.lastY = pointers[0].y;
        this.startTime = e.eventTime;
      }
      return null;
    }
    if (!this.active) {
      return null;
    }
    if (pointers.length > 1 || e.pointerCount > 1 || action === ACTION_CANCEL) {
      this.active = false; // second finger or cancelled: not a one-finger flip
      return null;
    }
    if (pointers[0]) {
      this.lastX = pointers[0].x;
      this.lastY = pointers[0].y;
    }
    if (action === ACTION_UP) {
      this.active = false;
      return classifyFlip(this.lastX - this.startX, this.lastY - this.startY, e.eventTime - this.startTime, o);
    }
    return null;
  }
}
