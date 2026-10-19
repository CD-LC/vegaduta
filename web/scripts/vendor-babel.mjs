// Vendors @babel/standalone into public/vendored so the app-builder's
// SandboxPreview (web/app/build/SandboxPreview.tsx) can transpile the
// generated app's TSX/JSX in-browser — same-origin instead of a CDN at
// runtime. Runs via the `prebuild`/`predev` npm hooks like
// vendor-webllm.mjs.
//
// Source: the installed node_modules copy only. @babel/standalone is an
// exact-pinned devDependency, so package-lock.json carries its integrity
// hash and the build needs no network. (It used to fall back to a jsDelivr
// download on every build, because the package was not a dependency.) The
// pin matters: the file drives generated-code transforms, so it must not
// drift with whatever "latest" resolves to on build day.
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const pkgDir = join(webRoot, "node_modules", "@babel", "standalone");
const src = join(pkgDir, "babel.min.js");
const outDir = join(webRoot, "public", "vendored");
const out = join(outDir, "babel.min.js");
const stamp = join(outDir, "babel.version.json");

if (!existsSync(src)) {
  console.error(`[vendor-babel] ${src} not found — run npm install in web/ first`);
  process.exit(1);
}
const { version } = JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8"));

mkdirSync(outDir, { recursive: true });
copyFileSync(src, out);
writeFileSync(stamp, JSON.stringify({ version, copiedAt: new Date().toISOString() }) + "\n");
console.log(`[vendor-babel] @babel/standalone@${version} -> public/vendored/babel.min.js`);
