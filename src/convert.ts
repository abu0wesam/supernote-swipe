/**
 * Levels of the page tool. SDK-free so it can be unit-tested.
 */

/** Manta screen: 1920 x 2560 px in portrait. */
export const MANTA_ASPECT = 1920 / 2560;

/** Width levels: 0 = the page as it is, the last level fills the screen width. */
export const WIDTH_LEVELS = 5;

/** Darkness levels: 1 = slightly darker, 10 = strongly darker. */
export const DARK_LEVELS = 10;

/**
 * Horizontal scale that makes a page fill the screen width when the reader
 * fits it to the screen height. A page wider than the screen is fitted to the
 * width already, so it is never stretched (scale 1).
 */
export function fillScale(pageAspect: number, screenAspect: number): number {
  if (!(pageAspect > 0) || !(screenAspect > 0)) {
    return 1;
  }
  return Math.max(1, screenAspect / pageAspect);
}

/** Scale of a width level (0..WIDTH_LEVELS-1), in even steps from 1 to `fill`. */
export function widthScale(level: number, fill: number): number {
  const l = Math.min(WIDTH_LEVELS - 1, Math.max(0, Math.round(level)));
  return 1 + ((fill - 1) * l) / (WIDTH_LEVELS - 1);
}

/** Gamma exponent of a darkness level: pixel = 255 * (pixel / 255) ^ exponent. */
export function darkExponent(level: number): number {
  const l = Math.min(DARK_LEVELS, Math.max(1, Math.round(level)));
  return 1 + 0.2 * l;
}
