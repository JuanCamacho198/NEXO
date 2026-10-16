const HEX_PATTERN = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i;

function parseHex(hex: string): [number, number, number] {
  const match = HEX_PATTERN.exec(hex.trim());
  if (!match) throw new Error(`contrast: unsupported colour value "${hex}"`);
  let body = match[1];
  if (body.length === 3) {
    body = `${body[0]}${body[0]}${body[1]}${body[1]}${body[2]}${body[2]}`;
  }
  const value = Number.parseInt(body, 16);
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
}

function linearize(channel: number): number {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHex(hex);
  return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
}

export function contrastRatio(fg: string, bg: string): number {
  const l1 = relativeLuminance(fg);
  const l2 = relativeLuminance(bg);
  const light = Math.max(l1, l2);
  const dark = Math.min(l1, l2);
  return (light + 0.05) / (dark + 0.05);
}

const BLOCK_PATTERNS: Record<'dark' | 'light', RegExp> = {
  dark: /:root,\s*\[data-theme='dark'\]\s*\{([\s\S]*?)\n\}/,
  light: /\[data-theme='light'\]\s*\{([\s\S]*?)\n\}/,
};

const DECLARATION_PATTERN = /(--[\w-]+)\s*:\s*([^;]+);/g;

export function readThemeColors(css: string, theme: 'dark' | 'light'): Record<string, string> {
  const block = BLOCK_PATTERNS[theme].exec(css);
  if (!block) throw new Error(`readThemeColors: no "${theme}" theme block found in stylesheet`);
  const colors: Record<string, string> = {};
  for (const declaration of block[1].matchAll(DECLARATION_PATTERN)) {
    colors[declaration[1]] = declaration[2].trim();
  }
  return colors;
}
