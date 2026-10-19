// Copies the installed kokoro-js WEB bundle next to the TTS worker so the
// worker imports it by RELATIVE PATH instead of by package name.
//
// WHY THIS EXISTS — it is the fix for a repo-wide broken `next build`.
// kokoro-js's package.json `exports` map declares only two conditions:
//     "node":    "./dist/kokoro.js"
//     "default": "./dist/kokoro.js"
// There is NO "browser" condition, even though the package ships a perfectly
// good `dist/kokoro.web.js`. So `import("kokoro-js")` resolves to the NODE
// build in every context — including a browser Web Worker — and that build
// pulls @huggingface/transformers' node entry -> onnxruntime-node -> its
// per-platform native `onnxruntime_binding.node` binaries. The bundler then
// tries to parse compiled native code as JavaScript. Under webpack that is a
// hard "Module parse failed"; under Turbopack the build simply never finishes
// (ACR runs cu14 and cu16 both hit the 60-minute ceiling).
//
// turbopack.resolveAlias and webpack resolve.alias/IgnorePlugin were all tried
// first and none of them reach the child compilation that builds the worker.
//
// The copy lands in public/ ON PURPOSE, not inside app/. Anything under app/ is
// still part of the module graph, so an earlier version of this script that
// copied next to the worker merely swapped "bundler parses the NODE build" for
// "bundler parses a 2 MB bundle with ONNX runtime inlined" — the build got no
// faster. public/ is served as a static asset and never enters the graph, which
// is exactly why scripts/vendor-webllm.mjs targets public/vendored too and why
// public/webllm-sw.js imports it same-origin as "/vendored/web-llm.js".
//
// Verified before relying on it: `dist/kokoro.web.js` contains NO bare imports
// (the node build imports @huggingface/transformers, fs/promises, path and
// phonemizer; the web build imports none of them), so a plain copy is a
// complete, self-contained ESM bundle — exactly the same property that makes
// vendor-webllm.mjs a plain copy too.
//
// ORT RUNTIME ASSETS — vendored for the same reason the bundle is: the page
// CSP allows cdn.jsdelivr.net in connect-src only, NOT script-src. The bundled
// transformers.js otherwise loads its ONNX Runtime JSEP loader
// (ort-wasm-simd-threaded.jsep.mjs, a module import inside the worker) from
// `https://cdn.jsdelivr.net/npm/@huggingface/transformers@<ver>/dist/` and the
// load dies under the policy as a bare "Download failed". Copying the two
// jsep files plus ort.bundle.min.mjs (the wasm proxy worker, created as
// `new Worker(new URL("ort.bundle.min.mjs", import.meta.url))` — relative to
// THIS file's directory, so it must sit directly under /vendored/) and pointing
// env.wasmPaths at /vendored/ort/ keeps the whole chain same-origin. The worker
// sets that in kokoroWorker.ts right after the dynamic import.
import { copyFileSync, mkdirSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const pkgDir = join(webRoot, "node_modules", "kokoro-js");
const src = join(pkgDir, "dist", "kokoro.web.js");
const outDir = join(webRoot, "public", "vendored");
const out = join(outDir, "kokoro.web.js");

if (!existsSync(src)) {
  console.error(`[vendor-kokoro] ${src} not found — run npm install in web/ first`);
  process.exit(1);
}

mkdirSync(outDir, { recursive: true });
copyFileSync(src, out);

let version = "unknown";
try {
  version = JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8")).version || "unknown";
} catch {}
writeFileSync(join(outDir, "kokoro.version.json"), JSON.stringify({ version, source: "kokoro-js/dist/kokoro.web.js" }, null, 2) + "\n");

// The ONNX Runtime pieces the bundled transformers.js resolves at RUNTIME (see
// the header comment). Sources: the transformers copy nested inside kokoro-js
// ships the canonical jsep pair; ort.bundle.min.mjs lives in onnxruntime-web.
const tfDir = join(pkgDir, "node_modules", "@huggingface", "transformers", "dist");
const ortDir = join(pkgDir, "node_modules", "onnxruntime-web", "dist");
const ortOutDir = join(outDir, "ort");
mkdirSync(ortOutDir, { recursive: true });
const ortAssets = [
  [join(tfDir, "ort-wasm-simd-threaded.jsep.mjs"), join(ortOutDir, "ort-wasm-simd-threaded.jsep.mjs")],
  [join(tfDir, "ort-wasm-simd-threaded.jsep.wasm"), join(ortOutDir, "ort-wasm-simd-threaded.jsep.wasm")],
  // Resolved relative to kokoro.web.js's own URL -> must sit next to it.
  [join(ortDir, "ort.bundle.min.mjs"), join(outDir, "ort.bundle.min.mjs")],
];
for (const [from, to] of ortAssets) {
  if (!existsSync(from)) {
    console.error(`[vendor-kokoro] ${from} not found — ORT runtime layout changed?`);
    process.exit(1);
  }
  copyFileSync(from, to);
}

console.log(`[vendor-kokoro] kokoro-js@${version} (web build) -> public/vendored/kokoro.web.js`);
console.log(`[vendor-kokoro] ORT runtime -> public/vendored/ort/ + public/vendored/ort.bundle.min.mjs`);
