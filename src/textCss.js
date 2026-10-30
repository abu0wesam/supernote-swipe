export const defaults = Object.freeze({font: 'original', size: 0, weight: 0, line: 0, marginH: -1, marginV: -1, align: 'original', direction: 'original'});
const pick = (v, values, fallback) => (values.includes(v) ? v : fallback);
export function normalize(v = {}) {
  if (!v || typeof v !== 'object') {
    v = {};
  }
  return {
    font: pick(v.font, ['original', 'serif', 'sans-serif'], 'original'),
    size: pick(v.size, [0, 16, 18, 20, 22, 24, 26, 28, 32, 36, 40], 0),
    weight: pick(v.weight, [0, 400, 500, 700], 0),
    line: pick(v.line, [0, 1.2, 1.4, 1.6, 1.8, 2, 2.2], 0),
    marginH: pick(v.marginH, [-1, 0, 2, 4, 6, 8, 10], -1),
    marginV: pick(v.marginV, [-1, 0, 0.5, 1, 1.5, 2], -1),
    align: pick(v.align, ['original', 'right', 'left', 'justify'], 'original'),
    direction: pick(v.direction, ['original', 'rtl', 'ltr'], 'original'),
  };
}
export function buildCss(input) {
  const s = normalize(input);
  const root = [];
  const text = [];
  const body = [];
  if (s.font !== 'original') {
    text.push(`font-family:${s.font} !important`);
  }
  if (s.size) {
    root.push(`font-size:${s.size}px !important`);
    text.push('font-size:inherit !important');
  }
  if (s.weight) {
    text.push(`font-weight:${s.weight} !important`);
  }
  if (s.line) {
    text.push(`line-height:${s.line} !important`);
  }
  if (s.align !== 'original') {
    text.push(`text-align:${s.align} !important`);
  }
  if (s.direction !== 'original') {
    root.push(`direction:${s.direction} !important`);
  }
  if (s.marginH >= 0) {
    body.push(`margin-left:${s.marginH}% !important`, `margin-right:${s.marginH}% !important`);
  }
  if (s.marginV >= 0) {
    body.push(`margin-top:${s.marginV}em !important`, `margin-bottom:${s.marginV}em !important`);
  }
  const rules = [];
  if (root.length) {
    rules.push(`html,body{${root.join(';')}}`);
  }
  if (text.length) {
    rules.push(`body,body p,body li,body dd,body dt,body blockquote,body div{${text.join(';')}}`);
  }
  if (body.length) {
    rules.push(`body{${body.join(';')}}`);
  }
  return rules.join('\n');
}
