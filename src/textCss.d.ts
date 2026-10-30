export type TextSettings = {
  font: 'original' | 'serif' | 'sans-serif';
  size: number;
  weight: number;
  line: number;
  marginH: number;
  marginV: number;
  align: 'original' | 'right' | 'left' | 'justify';
  direction: 'original' | 'rtl' | 'ltr';
};
export const defaults: Readonly<TextSettings>;
export function normalize(value?: unknown): TextSettings;
export function buildCss(value: unknown): string;
