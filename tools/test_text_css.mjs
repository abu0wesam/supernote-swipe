import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const source = readFileSync(new URL('../src/textCss.js', import.meta.url), 'utf8');
const {defaults, normalize, buildCss} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
assert.equal(buildCss(defaults), '');
assert.deepEqual(normalize(null), defaults);
assert.deepEqual(normalize({font: 'url(https://bad)', size: 999, align: 'x; color:red'}), defaults);
assert.match(buildCss({size: 24}), /font-size:24px !important/);
assert.match(buildCss({size: 24}), /font-size:inherit !important/);
assert.match(buildCss({weight: 700}), /font-weight:700 !important/);
assert.match(buildCss({direction: 'rtl'}), /direction:rtl !important/);
assert.match(buildCss({direction: 'ltr'}), /direction:ltr !important/);
assert.match(buildCss({marginH: 4, marginV: 1.5}), /margin-left:4% !important/);
assert.match(buildCss({marginH: 4, marginV: 1.5}), /margin-bottom:1.5em !important/);
assert.match(buildCss({font: 'sans-serif', line: 1.6, align: 'justify'}), /font-family:sans-serif !important/);
assert.equal(buildCss({font: 'serif'}), buildCss(normalize({font: 'serif'})));
assert.ok(!/[<>&]/.test(buildCss({font: 'serif', size: 24, weight: 500, line: 1.8, marginH: 2, marginV: 1, align: 'right', direction: 'rtl'})));
console.log('Text CSS: 13 assertions passed.');
