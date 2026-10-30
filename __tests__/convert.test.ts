import {DARK_LEVELS, WIDTH_LEVELS, darkExponent, fillScale, widthScale} from '../src/convert';

describe('fillScale', () => {
  it('stretches a narrow page up to the screen width', () => {
    expect(fillScale(0.6, 0.75)).toBeCloseTo(1.25);
  });
  it('never stretches a page that is as wide as the screen or wider', () => {
    expect(fillScale(0.75, 0.75)).toBe(1);
    expect(fillScale(0.9, 0.75)).toBe(1);
  });
  it('ignores invalid aspects', () => {
    expect(fillScale(0, 0.75)).toBe(1);
    expect(fillScale(0.7, NaN)).toBe(1);
  });
});

describe('widthScale', () => {
  it('goes from the original page to the full width in even steps', () => {
    expect(widthScale(0, 1.25)).toBe(1);
    expect(widthScale(2, 1.25)).toBeCloseTo(1.125);
    expect(widthScale(WIDTH_LEVELS - 1, 1.25)).toBeCloseTo(1.25);
  });
  it('clamps the level', () => {
    expect(widthScale(-3, 1.25)).toBe(1);
    expect(widthScale(99, 1.25)).toBeCloseTo(1.25);
  });
});

describe('darkExponent', () => {
  it('grows with the level', () => {
    expect(darkExponent(1)).toBeCloseTo(1.2);
    expect(darkExponent(DARK_LEVELS)).toBeCloseTo(3);
    expect(darkExponent(5)).toBeGreaterThan(darkExponent(4));
  });
});
