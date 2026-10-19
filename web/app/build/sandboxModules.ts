// Pure module-graph helpers for SandboxPreview (no React, no Babel import:
// the transpiler and the blob factory are injected so this is unit-testable).

export const CODE_EXT = [".tsx", ".ts", ".jsx", ".js", ".mjs"];
export const CSS_EXT = [".css"];
export const DATA_EXT = [".json"];
export const ASSET_EXT = [".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico"];

export const MIME: Record<string, string> = {
  ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
  ".ico": "image/x-icon",
};

/** Specifier prefix used for back-edges of import cycles (resolved by an import map). */
export const ALIAS_PREFIX = "vdmod:";

/** Join `spec` onto `dir`. A leading "/" means the project root, not the
 * importing file's folder. */
export function normPath(dir: string, spec: string): string {
  const base = spec.startsWith("/") ? "" : dir;
  const parts = (base ? base + "/" : "").split("/").filter(Boolean);
  for (const seg of spec.split("/")) {
    if (seg === "..") parts.pop();
    else if (seg !== "." && seg !== "") parts.push(seg);
  }
  return parts.join("/");
}

export function dirOf(path: string): string {
  const i = path.lastIndexOf("/");
  return i < 0 ? "" : path.slice(0, i);
}

export function resolveSpec(dir: string, spec: string, files: Map<string, string>): string | null {
  const base = normPath(dir, spec);
  const candidates = [base];
  for (const ext of [...CODE_EXT, ...CSS_EXT, ...DATA_EXT, ...ASSET_EXT]) {
    candidates.push(base + ext);
  }
  for (const ext of CODE_EXT) candidates.push(`${base}/index${ext}`);
  return candidates.find((c) => files.has(c)) || null;
}

// ---------------------------------------------------------------------------
// import rewriting

const DYNAMIC_RX = /\s*\(\s*(["'])((?:\\.|(?!\1)[^\\\n])*)\1/y;
const IMPORT_RX = /\s*(?:[\w$*\s,{}]*?\bfrom\s*|)(["'])((?:\\.|(?!\1)[^\\\n])*)\1/y;
const EXPORT_FROM_RX = /\s*(?:\*\s*(?:as\s+[\w$]+\s*)?|\{[^}]*\}\s*)from\s*(["'])((?:\\.|(?!\1)[^\\\n])*)\1/y;

const REGEX_PRECEDERS = new Set(["", "(", ",", "=", ":", "[", "!", "&", "|", "?", "{", ";", "+", "-", "*", "%", "<", ">", "~", "^"]);
const REGEX_KEYWORDS = new Set(["return", "typeof", "case", "do", "else", "in", "of", "void", "delete", "throw", "new", "instanceof", "yield", "await"]);

function skipString(code: string, i: number): number {
  const q = code[i];
  let j = i + 1;
  while (j < code.length) {
    const c = code[j];
    if (c === "\\") j += 2;
    else if (c === q) return j + 1;
    else if (c === "\n") return j; // unterminated: bail out at the line end
    else j++;
  }
  return code.length;
}

function skipBalanced(code: string, i: number): number {
  // i is just past an opening "{" of a template expression.
  let depth = 1;
  let j = i;
  while (j < code.length && depth > 0) {
    const c = code[j];
    if (c === '"' || c === "'") j = skipString(code, j);
    else if (c === "`") j = skipTemplate(code, j);
    else {
      if (c === "{") depth++;
      else if (c === "}") depth--;
      j++;
    }
  }
  return j;
}

function skipTemplate(code: string, i: number): number {
  let j = i + 1;
  while (j < code.length) {
    const c = code[j];
    if (c === "\\") j += 2;
    else if (c === "`") return j + 1;
    else if (c === "$" && code[j + 1] === "{") j = skipBalanced(code, j + 2);
    else j++;
  }
  return code.length;
}

function skipRegex(code: string, i: number): number {
  let j = i + 1;
  let inClass = false;
  while (j < code.length) {
    const c = code[j];
    if (c === "\n") return i + 1; // not a regex after all
    if (c === "\\") j += 2;
    else if (c === "[") { inClass = true; j++; }
    else if (c === "]") { inClass = false; j++; }
    else if (c === "/" && !inClass) {
      j++;
      while (j < code.length && /[a-z]/i.test(code[j])) j++;
      return j;
    } else j++;
  }
  return i + 1;
}

/** Rewrite import specifiers in already-transpiled ESM. Only real
 * `import ... from "x"`, `import "x"`, `export ... from "x"` statements (at
 * statement boundaries) and `import("x")` calls are touched; string literals,
 * template literals, regexes and comments that merely look like imports are
 * copied through untouched. */
export function rewriteImports(
  code: string,
  dir: string,
  resolve: (dir: string, spec: string) => string
): string {
  let out = "";
  let i = 0;
  const n = code.length;
  let last = ""; // last significant char of code
  let lastWord = "";

  const tryRewrite = (rx: RegExp, start: number, at: number): number => {
    rx.lastIndex = at;
    const m = rx.exec(code);
    if (!m) return -1;
    const end = rx.lastIndex;
    const lit = m[2].length + 2;
    out += code.slice(start, end - lit) + JSON.stringify(resolve(dir, m[2]));
    return end;
  };

  while (i < n) {
    const c = code[i];
    if (c === "/" && code[i + 1] === "/") {
      const j = code.indexOf("\n", i);
      const end = j < 0 ? n : j;
      out += code.slice(i, end);
      i = end;
      continue;
    }
    if (c === "/" && code[i + 1] === "*") {
      const j = code.indexOf("*/", i + 2);
      const end = j < 0 ? n : j + 2;
      out += code.slice(i, end);
      i = end;
      continue;
    }
    if (c === '"' || c === "'") {
      const end = skipString(code, i);
      out += code.slice(i, end);
      i = end;
      last = c;
      lastWord = "";
      continue;
    }
    if (c === "`") {
      const end = skipTemplate(code, i);
      out += code.slice(i, end);
      i = end;
      last = "`";
      lastWord = "";
      continue;
    }
    if (c === "/" && (REGEX_PRECEDERS.has(last) || REGEX_KEYWORDS.has(lastWord))) {
      const end = skipRegex(code, i);
      if (end > i + 1) {
        out += code.slice(i, end);
        i = end;
        last = "/";
        lastWord = "";
        continue;
      }
    }
    if (/[A-Za-z_$]/.test(c)) {
      let j = i + 1;
      while (j < n && /[\w$]/.test(code[j])) j++;
      const word = code.slice(i, j);
      if ((word === "import" || word === "export") && last !== ".") {
        const boundary = last === "" || last === ";" || last === "{" || last === "}";
        let end = -1;
        if (word === "import") {
          end = tryRewrite(DYNAMIC_RX, i, j);
          if (end < 0 && boundary) end = tryRewrite(IMPORT_RX, i, j);
        } else if (boundary) {
          end = tryRewrite(EXPORT_FROM_RX, i, j);
        }
        if (end >= 0) {
          i = end;
          last = code[end - 1];
          lastWord = "";
          continue;
        }
      }
      out += word;
      i = j;
      last = word[word.length - 1];
      lastWord = word;
      continue;
    }
    out += c;
    if (!/\s/.test(c)) {
      last = c;
      lastWord = "";
    }
    i++;
  }
  return out;
}

// ---------------------------------------------------------------------------
// assets

/** A real Blob for an asset, or null when the bytes cannot be recovered.
 * Files arrive as text, so binary formats only work when delivered as a
 * `data:` URL; SVG is text and always works. */
export function assetBlob(path: string, content: string): Blob | null {
  const ext = path.slice(path.lastIndexOf(".")).toLowerCase();
  const dm = /^data:([^;,]*)((?:;[^;,]*)*),([\s\S]*)$/.exec(content);
  if (dm) {
    const type = dm[1] || MIME[ext] || "application/octet-stream";
    try {
      if (/;base64/i.test(dm[2])) {
        const bin = atob(dm[3].replace(/\s+/g, ""));
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new Blob([bytes], { type });
      }
      return new Blob([decodeURIComponent(dm[3])], { type });
    } catch {
      return null;
    }
  }
  if (ext === ".svg") return new Blob([content], { type: MIME[".svg"] });
  return null;
}

// ---------------------------------------------------------------------------
// graphs

export interface GraphDeps {
  transpile: (path: string, code: string) => string;
  makeUrl: (content: string | Blob, type: string) => string;
}

export interface BuiltGraph {
  entryUrl: string;
  urls: string[];
  /** alias specifier -> module URL, for back-edges of import cycles. */
  importMap: Record<string, string>;
  /** Human-readable things the preview could not do faithfully. */
  notes: string[];
}

const cssShim = (css: string) =>
  `const s=document.createElement("style");s.textContent=${JSON.stringify(css)};document.head.appendChild(s);`;

const assetShim = (url: string) => `export default ${JSON.stringify(url)};`;

/** Transform a file set into a blob-URL module graph. Returns null when
 * the set has no runnable JS entry point. */
export function buildModuleGraph(files: Map<string, string>, deps: GraphDeps): BuiltGraph | null {
  let entry = ["src/main.tsx", "src/main.ts", "src/main.jsx", "src/main.js",
               "main.tsx", "main.js", "index.tsx", "index.js"].find((p) => files.has(p));
  if (!entry) {
    const app = ["src/App.tsx", "src/App.jsx", "src/App.js", "App.tsx"].find((p) => files.has(p));
    if (!app) return null;
    files.set(
      "__preview_entry__.js",
      `import React from "react";\nimport { createRoot } from "react-dom/client";\n` +
        `import App from "./${app}";\n` +
        `createRoot(document.getElementById("root")).render(<App />);`
    );
    entry = "__preview_entry__.js";
  }

  const urls: string[] = [];
  const notes: string[] = [];
  const importMap: Record<string, string> = {};
  const blobFor = new Map<string, string>();
  const assetModuleFor = new Map<string, string>();
  const inProgress = new Set<string>();
  const late = new Set<string>();
  const makeBlob = (content: string | Blob, type: string) => {
    const u = deps.makeUrl(content, type);
    urls.push(u);
    return u;
  };
  const note = (m: string) => {
    if (!notes.includes(m)) notes.push(m);
  };

  const assetModule = (target: string): string => {
    const cached = assetModuleFor.get(target);
    if (cached) return cached;
    const blob = assetBlob(target, files.get(target)!);
    let shim: string;
    if (blob) {
      shim = makeBlob(assetShim(makeBlob(blob, blob.type)), "text/javascript");
    } else {
      note(`Skipped binary asset ${target}: the preview only has text for it (SVG works; other images show once deployed).`);
      shim = makeBlob(assetShim(""), "text/javascript");
    }
    assetModuleFor.set(target, shim);
    return shim;
  };

  const blobUrlFor = (path: string): string => {
    const cached = blobFor.get(path);
    if (cached) return cached;
    if (inProgress.has(path)) {
      // Circular import: this module's URL does not exist yet. Point at an
      // alias the iframe's import map resolves once it does.
      late.add(path);
      return ALIAS_PREFIX + path;
    }
    const raw = files.get(path)!;
    let url: string;
    if (CSS_EXT.some((e) => path.endsWith(e))) {
      url = makeBlob(cssShim(raw), "text/javascript");
    } else if (DATA_EXT.some((e) => path.endsWith(e))) {
      url = makeBlob(`export default ${raw};`, "text/javascript");
    } else if (ASSET_EXT.some((e) => path.endsWith(e))) {
      url = assetModule(path);
    } else {
      inProgress.add(path);
      try {
        const js = deps.transpile(path, raw);
        const resolved = rewriteImports(js, dirOf(path), (dir, spec) => {
          if (spec.startsWith(".") || spec.startsWith("/")) {
            const target = resolveSpec(dir, spec, files);
            if (!target) {
              note(`Unresolved import "${spec}" in ${path}.`);
              return spec;
            }
            const ext = target.slice(target.lastIndexOf("."));
            if (ASSET_EXT.includes(ext)) return assetModule(target);
            return blobUrlFor(target);
          }
          // Already a URL (https:, blob:, data:, our alias): leave alone.
          if (/^[a-z][a-z0-9+.-]*:/i.test(spec)) return spec;
          // Bare npm specifier -> esm.sh CDN.
          return `https://esm.sh/${spec}`;
        });
        url = makeBlob(resolved, "text/javascript");
      } finally {
        inProgress.delete(path);
      }
    }
    blobFor.set(path, url);
    if (late.has(path)) importMap[ALIAS_PREFIX + path] = url;
    return url;
  };

  return { entryUrl: blobUrlFor(entry), urls, importMap, notes };
}

/** For the static-html template: inline <link>/<script src> references as
 * blob URLs into a srcdoc. */
export function buildStaticDoc(
  files: Map<string, string>,
  makeUrl: (content: string | Blob, type: string) => string
): { doc: string; urls: string[]; notes: string[] } | null {
  const html = files.get("index.html");
  if (html === undefined) return null;
  const urls: string[] = [];
  const notes: string[] = [];
  const urlFor = (path: string): string | null => {
    const content = files.get(path);
    if (content === undefined) return null;
    const ext = path.slice(path.lastIndexOf(".")).toLowerCase();
    let u: string;
    if (ASSET_EXT.includes(ext)) {
      const blob = assetBlob(path, content);
      if (!blob) {
        const msg = `Skipped binary asset ${path}: the preview only has text for it.`;
        if (!notes.includes(msg)) notes.push(msg);
        return null;
      }
      u = makeUrl(blob, blob.type);
    } else {
      const type = MIME[ext] || (ext === ".css" ? "text/css" : ext === ".js" ? "text/javascript" : "text/plain");
      u = makeUrl(content, type);
    }
    urls.push(u);
    return u;
  };
  const doc = html.replace(/(href|src)=["'](\.?\/?[^"':]+?)["']/g, (m, attr, ref) => {
    const u = urlFor(normPath("", ref));
    return u ? `${attr}="${u}"` : m;
  });
  return { doc, urls, notes };
}

/** The srcdoc shell that boots the entry module (with an import map when
 * the graph has cycles). */
export function shellDoc(entryUrl: string, importMap: Record<string, string> = {}): string {
  const map = Object.keys(importMap).length
    ? `<script type="importmap">${JSON.stringify({ imports: importMap }).replace(/</g, "\\u003c")}</script>`
    : "";
  return (
    `<!doctype html><html><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width,initial-scale=1">` +
    map +
    `</head><body><div id="root"></div>` +
    `<script type="module">import ${JSON.stringify(entryUrl)};</script>` +
    `</body></html>`
  );
}
