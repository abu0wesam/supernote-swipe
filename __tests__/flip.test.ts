import {FlipOptions, FlipTracker, classifyFlip, targetPage} from '../src/flip';
import {ACTION_DOWN, ACTION_UP, Motion} from '../src/gesture';

const ACTION_MOVE = 2;
const o: FlipOptions = {minDistance: 100, maxDurationMs: 800, maxSlope: 0.6, penQuietMs: 400, leftToRightIsNext: true};

function ev(action: number, x: number, y: number, t: number, extra: Partial<Motion> = {}): Motion {
  return {
    action,
    pointerCount: 1,
    pointers: [{x, y, pointerId: 0, toolType: 1}],
    eventTime: t,
    downTime: 0,
    toolType: 1,
    ...extra,
  };
}

function swipe(tracker: FlipTracker, x0: number, x1: number, y0 = 500, y1 = 500, dt = 200) {
  tracker.feed(ev(ACTION_DOWN, x0, y0, 1000));
  tracker.feed(ev(ACTION_MOVE, (x0 + x1) / 2, (y0 + y1) / 2, 1000 + dt / 2));
  return tracker.feed(ev(ACTION_UP, x1, y1, 1000 + dt));
}

describe('classifyFlip', () => {
  it('left-to-right goes to the next page', () => {
    expect(classifyFlip(200, 10, 300, o)).toBe('next');
  });
  it('right-to-left goes to the previous page', () => {
    expect(classifyFlip(-200, 10, 300, o)).toBe('previous');
  });
  it('ignores short, slow or diagonal drags', () => {
    expect(classifyFlip(50, 0, 300, o)).toBeNull();
    expect(classifyFlip(200, 0, 900, o)).toBeNull();
    expect(classifyFlip(200, 190, 300, o)).toBeNull();
  });
});

describe('FlipTracker', () => {
  it('recognises a one-finger swipe on lift', () => {
    expect(swipe(new FlipTracker(() => o), 100, 400)).toBe('next');
    expect(swipe(new FlipTracker(() => o), 400, 100)).toBe('previous');
  });
  it('ignores a vertical drag', () => {
    expect(swipe(new FlipTracker(() => o), 300, 320, 100, 600)).toBeNull();
  });
  it('ignores two fingers', () => {
    const t = new FlipTracker(() => o);
    t.feed(ev(ACTION_DOWN, 100, 500, 1000));
    const two = ev(ACTION_MOVE, 250, 500, 1100, {
      pointerCount: 2,
      pointers: [
        {x: 250, y: 500, pointerId: 0, toolType: 1},
        {x: 300, y: 500, pointerId: 1, toolType: 1},
      ],
    });
    t.feed(two);
    expect(t.feed(ev(ACTION_UP, 400, 500, 1200))).toBeNull();
  });
  it('ignores a finger right after the pen', () => {
    const t = new FlipTracker(() => o);
    t.feed(ev(ACTION_MOVE, 10, 10, 990, {toolType: 2, pointers: [{x: 10, y: 10, pointerId: 0, toolType: 2}]}));
    expect(swipe(t, 100, 400)).toBeNull();
  });
});

describe('targetPage', () => {
  it('stays inside the document', () => {
    expect(targetPage(0, 10, 'next')).toBe(1);
    expect(targetPage(9, 10, 'next')).toBeNull();
    expect(targetPage(0, 10, 'previous')).toBeNull();
    expect(targetPage(5, null, 'previous')).toBe(4);
  });
});
