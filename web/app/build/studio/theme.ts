// Design tokens for studio apps. Every starter keeps its look in one marked
// CSS block of custom properties:
//
//   /* studio:theme:start */
//   :root { --primary: #6d5dfc; --bg: #f6f6fb; ... }
//   /* studio:theme:end */
//
// so the Design panel restyles an app instantly and deterministically -
// no model call, no tokens, no chance of the AI "helpfully" rewriting
// the layout while changing a colour. Pure string functions (unit-tested).

export interface ThemeTokens {
  primary: string;
  bg: string;
  surface: string;
  text: string;
  muted: string;
  radius: string;
  font: string;
}

export const TOKEN_KEYS: (keyof ThemeTokens)[] = [
  "primary",
  "bg",
  "surface",
  "text",
  "muted",
  "radius",
  "font",
];

const START = "/* studio:theme:start */";
const END = "/* studio:theme:end */";

export const FONT_CHOICES: { label: string; value: string }[] = [
  { label: "System", value: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif" },
  { label: "Rounded", value: "ui-rounded, 'SF Pro Rounded', 'Nunito', system-ui, sans-serif" },
  { label: "Serif", value: "ui-serif, Georgia, 'Times New Roman', serif" },
  { label: "Mono", value: "ui-monospace, 'SF Mono', Menlo, Consolas, monospace" },
];

export const THEME_PRESETS: { name: string; tokens: Partial<ThemeTokens> }[] = [
  { name: "Violet", tokens: { primary: "#6d5dfc", bg: "#f6f6fb", surface: "#ffffff", text: "#17171f", muted: "#6b6b7b" } },
  { name: "Saffron", tokens: { primary: "#f97316", bg: "#fff8f1", surface: "#ffffff", text: "#1f1a17", muted: "#7a6a5e" } },
  { name: "Mint", tokens: { primary: "#10b981", bg: "#f2fbf7", surface: "#ffffff", text: "#10201a", muted: "#5b7169" } },
  { name: "Ocean", tokens: { primary: "#0ea5e9", bg: "#f1f8fc", surface: "#ffffff", text: "#0f1c24", muted: "#5a6d78" } },
  { name: "Rose", tokens: { primary: "#e11d48", bg: "#fff5f7", surface: "#ffffff", text: "#23121a", muted: "#7d5e69" } },
  { name: "Midnight", tokens: { primary: "#8b7bff", bg: "#0e0f16", surface: "#191b26", text: "#ecebf5", muted: "#9a9ab0" } },
  { name: "Forest", tokens: { primary: "#4ade80", bg: "#0c1510", surface: "#15211a", text: "#e6f2ea", muted: "#8fa898" } },
];

function blockRange(html: string): [number, number] | null {
  const s = html.indexOf(START);
  if (s < 0) return null;
  const e = html.indexOf(END, s);
  if (e < 0) return null;
  return [s, e + END.length];
}

/** Read the tokens, or null when the app has no theme block (e.g. the model
 * rewrote the stylesheet and dropped it). */
export function readTheme(html: string): ThemeTokens | null {
  const r = blockRange(html);
  if (!r) return null;
  const block = html.slice(r[0], r[1]);
  const out: Partial<ThemeTokens> = {};
  for (const k of TOKEN_KEYS) {
    const m = block.match(new RegExp(`--${k}\\s*:\\s*([^;]+);`));
    if (m) out[k] = m[1].trim();
  }
  return TOKEN_KEYS.every((k) => typeof out[k] === "string") ? (out as ThemeTokens) : null;
}

/** The marked theme block, as CSS. Besides the seven editable tokens it
 * carries a DERIVED `--on-primary` (readable text on a primary-coloured
 * button, picked by luminance) so a light primary like Forest's green never
 * gets unreadable white text. readTheme ignores it; every write recomputes it. */
export function themeBlockCss(t: ThemeTokens): string {
  const lines = TOKEN_KEYS.map((k) => `  --${k}: ${t[k]};`);
  lines.push(`  --on-primary: ${onColor(t.primary)};`);
  return `${START}\n:root {\n${lines.join("\n")}\n}\n${END}`;
}

function renderBlock(t: ThemeTokens): string {
  return themeBlockCss(t);
}

/** Text colour (white or near-black) with the better contrast on `bg`.
 * Falls back to white for colours we cannot parse (var(), gradients, names). */
export function onColor(bg: string): string {
  const l = luminance(bg);
  if (l === null) return "#ffffff";
  // Contrast with white = 1.05/(l+.05); with near-black #111 (L ~ .0056) = (l+.05)/.0556.
  return 1.05 / (l + 0.05) >= (l + 0.05) / 0.0556 ? "#ffffff" : "#111111";
}

/** Write tokens back. If the block is missing, inject one at the top of the
 * first <style> (or a new <style> in <head>) so the panel still works. */
export function writeTheme(html: string, patch: Partial<ThemeTokens>): string {
  const current = readTheme(html);
  const merged = { ...(current || DEFAULT_TOKENS), ...sanitize(patch) } as ThemeTokens;
  const r = blockRange(html);
  if (r) return html.slice(0, r[0]) + renderBlock(merged) + html.slice(r[1]);
  const styleAt = html.search(/<style[^>]*>/i);
  if (styleAt >= 0) {
    const close = html.indexOf(">", styleAt) + 1;
    return html.slice(0, close) + "\n" + renderBlock(merged) + "\n" + html.slice(close);
  }
  const headClose = html.search(/<\/head>/i);
  const style = `<style>\n${renderBlock(merged)}\n</style>\n`;
  if (headClose >= 0) return html.slice(0, headClose) + style + html.slice(headClose);
  return style + html;
}

export const DEFAULT_TOKENS: ThemeTokens = {
  primary: "#6d5dfc",
  bg: "#f6f6fb",
  surface: "#ffffff",
  text: "#17171f",
  muted: "#6b6b7b",
  radius: "16px",
  font: FONT_CHOICES[0].value,
};

/** Token values end up inside a <style> block - refuse anything that could
 * close it or smuggle a second declaration. */
function sanitize(patch: Partial<ThemeTokens>): Partial<ThemeTokens> {
  const out: Partial<ThemeTokens> = {};
  for (const k of TOKEN_KEYS) {
    const v = patch[k];
    if (typeof v !== "string") continue;
    if (/[;{}<>]/.test(v) || v.length > 120) continue;
    out[k] = v.trim();
  }
  return out;
}

/** Relative luminance check so the panel can warn about unreadable
 * text/background pairs (WCAG contrast ratio). Hex colours only. */
export function contrastRatio(a: string, b: string): number | null {
  const la = luminance(a);
  const lb = luminance(b);
  if (la === null || lb === null) return null;
  const [hi, lo] = la > lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
}

function luminance(hex: string): number | null {
  const m = hex.trim().match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (!m) return null;
  let h = m[1];
  if (h.length === 3) h = h.split("").map((c) => c + c).join("");
  const ch = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const lin = ch.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}
