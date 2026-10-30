import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const source = readFileSync(new URL('../src/pdfApplyGuard.js', import.meta.url), 'utf8');
const {createApplyGuard} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
let checks = 0;
let calls = [];
let run = createApplyGuard(enabled => calls.push(enabled));
assert.equal(await run(async () => 42), 42);
assert.deepEqual(calls, [true, false]); checks++;

calls = [];
const failure = new Error('native failure');
await assert.rejects(run(async () => {throw failure;}), error => error === failure);
assert.deepEqual(calls, [true, false]); checks++;

calls = [];
const cancelled = Object.assign(new Error('Cancelled'), {code: 'E_CANCELLED'});
await assert.rejects(run(async () => {throw cancelled;}), error => error === cancelled);
assert.deepEqual(calls, [true, false]); checks++;

calls = [];
let finish;
const pending = run(() => new Promise(resolve => {finish = resolve;}));
await Promise.resolve();
await assert.rejects(run(async () => 0), /already running/);
finish(7);
assert.equal(await pending, 7);
assert.deepEqual(calls, [true, false]); checks++;

calls = [];
let started = false;
run = createApplyGuard(enabled => {calls.push(enabled); return enabled ? {success: false} : undefined;});
await assert.rejects(run(async () => {started = true;}), /Conversion was not started/);
assert.equal(started, false);
assert.deepEqual(calls, [true, false]); checks++;

calls = [];
run = createApplyGuard(enabled => {calls.push(enabled); if (enabled) throw new Error('missing SDK API');});
await assert.rejects(run(async () => {started = true;}), /missing SDK API/);
assert.equal(started, false);
assert.deepEqual(calls, [true, false]); checks++;

const oldWarn = console.warn;
const warnings = [];
console.warn = (...args) => warnings.push(args);
try {
  run = createApplyGuard(enabled => {if (!enabled) throw new Error('release failure');});
  assert.equal(await run(async () => 9), 9);
  await assert.rejects(run(async () => {throw failure;}), error => error === failure);
  assert.equal(warnings.length, 2); checks++;
} finally {
  console.warn = oldWarn;
}
console.log(`PDF apply guard: ${checks} tests passed.`);
