// Copies the installed @mlc-ai/web-llm ESM bundle into public/vendored so the
// tier-1 service worker (public/webllm-sw.js) imports it same-origin instead
// of from a third-party CDN at runtime. Runs via the `prebuild`/`predev` npm
// hooks, so the copy always matches the exact version the page-side dynamic
// import (web/app/lib/edge/tier1.ts) resolves — the client/worker message
// protocol can never drift between the two.
//
// lib/index.js is a single self-contained ESM file (no bare imports), so a
// plain copy is a complete bundle; no bundler step is needed.
//
// Caching (review FE-050, 2026-09-30): the library is written to a VERSIONED
// file, web-llm-<version>.js, which next.config.js serves `immutable` - a new
// release is a new URL. web-llm.js becomes a one-line re-export of it, served
// with a short TTL: the service worker's static `import "/vendored/web-llm.js"`
// and the page's import in lib/edge/tier1.ts both resolve through it to the
// same versioned URL, so the 6.6 MB file is downloaded once and cached for good.
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const pkgDir = join(webRoot, "node_modules", "@mlc-ai", "web-llm");
const src = join(pkgDir, "lib", "index.js");
const outDir = join(webRoot, "public", "vendored");
const out = join(outDir, "web-llm.js");
const stamp = join(outDir, "web-llm.version.json");

if (!existsSync(src)) {
  console.error(`[vendor-webllm] ${src} not found — run npm install in web/ first`);
  process.exit(1);
}

const { version } = JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8"));
const source = readFileSync(src, "utf8");
if (/^import\s.+from\s+["'][^./]/m.test(source)) {
  console.error("[vendor-webllm] lib/index.js has bare imports — a plain copy is no longer a complete bundle");
  process.exit(1);
}

mkdirSync(outDir, { recursive: true });
const versioned = `web-llm-${version}.js`;
// Drop copies of older releases so public/vendored never accumulates them.
for (const f of readdirSync(outDir)) {
  if (/^web-llm-.+\.js$/.test(f) && f !== versioned) rmSync(join(outDir, f));
}
copyFileSync(src, join(outDir, versioned));
writeFileSync(out, `export * from "./${versioned}";\n`);
writeFileSync(stamp, JSON.stringify({ version, file: versioned, copiedAt: new Date().toISOString() }) + "\n");
console.log(`[vendor-webllm] @mlc-ai/web-llm@${version} -> public/vendored/${versioned} (+ web-llm.js re-export)`);
