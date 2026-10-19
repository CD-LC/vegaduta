// "App check": a static lint of an app's HTML for the mistakes that make a
// phone app feel broken - no viewport, images with no alt, tiny tap targets,
// unreadable text colours. Pure and string-based (no DOM), so it is cheap to
// run on every version and trivial to unit-test. It is a HEURISTIC: it only
// sees inline styles and the studio theme tokens, never computed styles, so
// every message says "looks like" and nothing here blocks anything.

import { stripConnections } from "./connect";
import { contrastRatio, readTheme } from "./theme";

export type CheckSeverity = "error" | "warn";

export interface CheckFinding {
  id: string;
  severity: CheckSeverity;
  title: string;
  detail: string;
  /** What to tell the AI to do about it. */
  fix: string;
}

const MIN_TAP = 44;
const MIN_CONTRAST = 4.5;

/** "#rgb" / "#rrggbb" / "rgb(r,g,b)" -> "#rrggbb"; null for anything else. */
export function toHex(raw: string): string | null {
  const v = raw.trim().toLowerCase();
  let m = v.match(/^#([0-9a-f]{3})$/);
  if (m) return "#" + m[1].split("").map((c) => c + c).join("");
  m = v.match(/^#([0-9a-f]{6})$/);
  if (m) return "#" + m[1];
  m = v.match(/^rgb\(\s*(\d{1,3})\s*[, ]\s*(\d{1,3})\s*[, ]\s*(\d{1,3})\s*\)$/);
  if (m) return "#" + [m[1], m[2], m[3]].map((n) => Math.min(255, Number(n)).toString(16).padStart(2, "0")).join("");
  const named: Record<string, string> = { white: "#ffffff", black: "#000000", red: "#ff0000", yellow: "#ffff00", lightgray: "#d3d3d3", lightgrey: "#d3d3d3", gray: "#808080", grey: "#808080" };
  return named[v] ?? null;
}

function styleProp(style: string, prop: string): string | null {
  // (?:^|;) so `color` does not match inside `background-color`.
  const m = style.match(new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, "i"));
  return m ? m[1].trim() : null;
}

function px(v: string | null): number | null {
  if (!v) return null;
  const m = v.match(/^(-?\d+(?:\.\d+)?)px$/i);
  return m ? Number(m[1]) : null;
}

function tagsOf(html: string, names: string): { tag: string; attrs: string }[] {
  const out: { tag: string; attrs: string }[] = [];
  const re = new RegExp(`<(${names})(\\s[^>]*)?>`, "gi");
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) out.push({ tag: m[1].toLowerCase(), attrs: m[2] || "" });
  return out;
}

function attr(attrs: string, name: string): string | null {
  const m = attrs.match(new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, "i"));
  return m ? (m[1] ?? m[2] ?? m[3] ?? "") : null;
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}

export function appCheck(rawHtml: string): CheckFinding[] {
  // The studio-owned vd:connect block is generated code, not the author's app.
  const html = stripConnections(rawHtml || "");
  const out: CheckFinding[] = [];

  if (!/<meta[^>]+name\s*=\s*["']?viewport["']?/i.test(html)) {
    out.push({
      id: "viewport",
      severity: "error",
      title: "No viewport meta tag",
      detail: "Without it phones render the page at desktop width and zoom it out.",
      fix: 'Add <meta name="viewport" content="width=device-width,initial-scale=1"> inside <head>.',
    });
  }

  const htmlTag = html.match(/<html(\s[^>]*)?>/i);
  if (!htmlTag || !attr(htmlTag[1] || "", "lang")) {
    out.push({
      id: "lang",
      severity: "warn",
      title: "Missing page language",
      detail: "Screen readers and translation tools need <html lang>.",
      fix: 'Add a lang attribute to the <html> tag, e.g. <html lang="en">.',
    });
  }

  if (!/<title[^>]*>[^<]+<\/title>/i.test(html)) {
    out.push({
      id: "title",
      severity: "warn",
      title: "Missing page title",
      detail: "The title names the app on the home screen and in the task switcher.",
      fix: "Add a short <title> inside <head>.",
    });
  }

  const noAlt = tagsOf(html, "img").filter((t) => attr(t.attrs, "alt") === null);
  if (noAlt.length) {
    out.push({
      id: "img-alt",
      severity: "error",
      title: `${noAlt.length} ${plural(noAlt.length, "image has", "images have")} no alt text`,
      detail: 'Blind users hear nothing for them. Decorative images should say alt="".',
      fix: `Add a meaningful alt attribute to every <img> that lacks one (${noAlt.length} found); use alt="" for purely decorative images.`,
    });
  }

  // Tap targets: only INLINE size is knowable here, so flag explicit small sizes.
  const tiny: string[] = [];
  for (const t of tagsOf(html, "button|a|input|select|textarea|summary")) {
    if (t.tag === "input" && /^(hidden)$/i.test(attr(t.attrs, "type") || "")) continue;
    const style = attr(t.attrs, "style") || "";
    const h = px(styleProp(style, "height"));
    const w = px(styleProp(style, "width"));
    const minH = px(styleProp(style, "min-height"));
    const minW = px(styleProp(style, "min-width"));
    const smallH = h !== null && h < MIN_TAP && !(minH !== null && minH >= MIN_TAP);
    const smallW = w !== null && w < MIN_TAP && !(minW !== null && minW >= MIN_TAP);
    if (smallH || smallW) tiny.push(`<${t.tag}> ${smallW ? w : "auto"}x${smallH ? h : "auto"}px`);
  }
  if (tiny.length) {
    out.push({
      id: "tap-target",
      severity: "warn",
      title: `${tiny.length} tap ${plural(tiny.length, "target looks", "targets look")} smaller than ${MIN_TAP}px`,
      detail: `Small buttons are hard to hit on a phone. Found: ${tiny.slice(0, 3).join(", ")}${tiny.length > 3 ? ", ..." : ""}.`,
      fix: `Make every button, link and input at least ${MIN_TAP}px tall and wide (use min-height:${MIN_TAP}px;min-width:${MIN_TAP}px) - ${tiny.length} inline-sized control(s) are smaller.`,
    });
  }

  // Contrast: inline color + background on one element, then the theme tokens.
  const lowPairs: string[] = [];
  for (const t of tagsOf(html, "[a-z][a-z0-9]*")) {
    const style = attr(t.attrs, "style");
    if (!style) continue;
    const fg = toHex(styleProp(style, "color") || "");
    const bg = toHex(styleProp(style, "background-color") || styleProp(style, "background") || "");
    if (!fg || !bg) continue;
    const r = contrastRatio(fg, bg);
    if (r !== null && r < MIN_CONTRAST) lowPairs.push(`${fg} on ${bg} (${r.toFixed(1)}:1)`);
  }
  const theme = readTheme(html);
  if (theme) {
    const pairs: [string, string, string][] = [
      ["text", theme.text, theme.bg],
      ["text on cards", theme.text, theme.surface],
      ["muted text", theme.muted, theme.bg],
      ["muted text on cards", theme.muted, theme.surface],
    ];
    for (const [label, fg, bg] of pairs) {
      const r = contrastRatio(toHex(fg) || fg, toHex(bg) || bg);
      if (r !== null && r < MIN_CONTRAST) lowPairs.push(`${label} ${fg} on ${bg} (${r.toFixed(1)}:1)`);
    }
  }
  if (lowPairs.length) {
    out.push({
      id: "contrast",
      severity: "warn",
      title: `${lowPairs.length} low-contrast colour ${plural(lowPairs.length, "pair", "pairs")}`,
      detail: `Text needs at least ${MIN_CONTRAST}:1 against its background. ${lowPairs.slice(0, 3).join("; ")}${lowPairs.length > 3 ? "; ..." : ""}.`,
      fix: `Fix low-contrast text so every text/background pair reaches 4.5:1 contrast. Offenders: ${lowPairs.slice(0, 6).join("; ")}.`,
    });
  }

  return out;
}

/** The one request that sends every finding to the AI edit path. */
export function fixRequestFromFindings(findings: CheckFinding[]): string {
  if (!findings.length) return "";
  return (
    "Fix these accessibility and mobile-readiness problems in the app, changing nothing else:\n" +
    findings.map((f, i) => `${i + 1}. ${f.title}. ${f.fix}`).join("\n")
  );
}
