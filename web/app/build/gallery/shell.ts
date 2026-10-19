// Shared page shell for the App Builder gallery templates. Matches the Studio
// conventions in ../studio/templates.ts: one marked theme block (so the Design
// panel can restyle it), localStorage for data (the preview harness backs it),
// esc/load/save helpers, no network calls, one self-contained HTML file.

import { DEFAULT_TOKENS, themeBlockCss, type ThemeTokens } from "../studio/theme";

export const GALLERY_CSS = `*{box-sizing:border-box;margin:0;padding:0}
html,body{min-height:100%}
body{font-family:var(--font);background:var(--bg);color:var(--text);-webkit-font-smoothing:antialiased;-webkit-tap-highlight-color:transparent;line-height:1.45}
.app{max-width:560px;margin:0 auto;min-height:100vh;display:flex;flex-direction:column;padding-bottom:env(safe-area-inset-bottom)}
.wide{max-width:920px}
header.top{padding:calc(20px + env(safe-area-inset-top)) 20px 6px}
header.top h1{font-size:26px;font-weight:800;letter-spacing:-.02em}
header.top p{color:var(--muted);font-size:14px;margin-top:2px}
main{flex:1;padding:12px 16px 60px;display:flex;flex-direction:column;gap:12px}
.card{background:var(--surface);border-radius:var(--radius);padding:16px;box-shadow:0 1px 2px rgba(0,0,0,.05),0 6px 20px rgba(0,0,0,.05)}
.btn{appearance:none;border:0;border-radius:calc(var(--radius) - 4px);background:var(--primary);color:var(--on-primary,#fff);font:600 15px var(--font);padding:12px 18px;min-height:44px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;justify-content:center}
.btn:active{transform:scale(.97)}
.btn:disabled{opacity:.45;cursor:not-allowed}
.btn.ghost{background:transparent;color:var(--primary);box-shadow:inset 0 0 0 1.5px var(--primary)}
input,select,textarea{width:100%;font:16px var(--font);color:var(--text);background:var(--bg);border:1.5px solid rgba(127,127,140,.25);border-radius:calc(var(--radius) - 6px);padding:12px 14px;min-height:44px}
input:focus,select:focus,textarea:focus{outline:none;border-color:var(--primary)}
label.f{display:flex;flex-direction:column;gap:4px;font-size:13px;font-weight:600;color:var(--muted)}
.row{display:flex;align-items:center;gap:10px}
.grow{flex:1;min-width:0}
.muted{color:var(--muted)}
.empty{text-align:center;color:var(--muted);padding:28px 12px;font-size:14px}
.chips{display:flex;gap:8px;overflow-x:auto;padding-bottom:2px}
.chip{flex:none;border:0;border-radius:999px;padding:8px 14px;background:var(--surface);color:var(--text);font:600 13px var(--font);min-height:36px;cursor:pointer}
.chip.on{background:var(--text);color:var(--bg)}
.x{border:0;background:none;color:var(--muted);font-size:18px;min-width:36px;min-height:44px;cursor:pointer}
.toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:var(--text);color:var(--bg);padding:10px 16px;border-radius:999px;font-size:14px;display:none;z-index:20}
.toast.show{display:block}`;

export function themeBlock(t: ThemeTokens): string {
  return themeBlockCss(t);
}

export function galleryPage(o: {
  title: string;
  theme?: Partial<ThemeTokens>;
  css?: string;
  body: string;
  script: string;
}): string {
  const t = { ...DEFAULT_TOKENS, ...(o.theme || {}) };
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="${t.primary}">
<title>${o.title}</title>
<style>
${themeBlock(t)}
${GALLERY_CSS}
${(o.css || "").trim()}
</style>
</head>
<body>
${o.body.trim()}
<script>
// Local calendar date as YYYY-MM-DD (toISOString() is UTC and flips the day for anyone east/west of it).
const ymd = d => { d = d || new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
const esc = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const load = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };
const $ = id => document.getElementById(id);
const toast = m => { const t = $("toast"); if (!t) return; t.textContent = m; t.classList.add("show"); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove("show"), 2200); };
${o.script.trim()}
</script>
</body>
</html>
`;
}
