import { DEFAULT_LOCALE } from "./i18n-config";

// Moved here from app/layout.tsx (2026-09-19) so route-level generateMetadata
// (lib/seo-i18n.ts) can read the same bundles the root layout renders from.
// Server-only by usage: never import this from a "use client" module.
// LOCALE MESSAGES ARE LAZY — DO NOT GO BACK TO STATIC IMPORTS (2026-09-12).
//
// The root layout used to `import` all 30 `messages/<locale>.json` files eagerly. That
// was survivable only while the non-English files were nearly empty stubs. On
// 2026-09-12 the Vertex translation pipeline filled them: `messages/` went to
// **26.1 MB across 30 files** (ta/ml/kn/te/or are ~1 MB each). Because those
// imports sat in the ROOT layout, that JSON became part of every route's module
// graph, and `next build` stopped completing at all — it did not fail, it hung,
// timing out at ACR's 60-minute ceiling (run cu14) and never finishing locally
// across five attempts. A control build of the parent commit hung identically,
// which is what proved the cause was here and not in any one feature branch.
//
// This layout is a server component that already reads cookies()/headers() on
// every request, and it needs exactly ONE locale per request. So each locale is
// now its own `() => import(...)` thunk: the bundler emits 30 small lazy chunks
// instead of inlining 26 MB into the root graph, and a request pays for one file.
//
// The map is written out longhand rather than as a template literal
// (`import(\`../messages/${locale}.json\`)`) on purpose — a template literal makes
// the bundler include the whole directory as a dynamic context, which is most of
// the problem again. Add a locale by adding a line, and keep the key equal to the
// real locale code ("or"/"as" are JS reserved-ish words, which is why the old
// static imports needed `or_`/`as_` aliases; thunks need no alias).
export type MessageBundle = Record<string, unknown>;
const MESSAGE_LOADERS: Record<string, () => Promise<{ default: MessageBundle }>> = {
  "en": () => import("../../messages/en.json"),
  "es": () => import("../../messages/es.json"),
  "fr": () => import("../../messages/fr.json"),
  "de": () => import("../../messages/de.json"),
  "pt": () => import("../../messages/pt.json"),
  "ar": () => import("../../messages/ar.json"),
  "zh": () => import("../../messages/zh.json"),
  "ja": () => import("../../messages/ja.json"),
  "hi": () => import("../../messages/hi.json"),
  "bn": () => import("../../messages/bn.json"),
  "mr": () => import("../../messages/mr.json"),
  "te": () => import("../../messages/te.json"),
  "ta": () => import("../../messages/ta.json"),
  "gu": () => import("../../messages/gu.json"),
  "ur": () => import("../../messages/ur.json"),
  "kn": () => import("../../messages/kn.json"),
  "or": () => import("../../messages/or.json"),
  "ml": () => import("../../messages/ml.json"),
  "pa": () => import("../../messages/pa.json"),
  "as": () => import("../../messages/as.json"),
  "mai": () => import("../../messages/mai.json"),
  "sat": () => import("../../messages/sat.json"),
  "ks": () => import("../../messages/ks.json"),
  "ne": () => import("../../messages/ne.json"),
  "kok": () => import("../../messages/kok.json"),
  "sd": () => import("../../messages/sd.json"),
  "doi": () => import("../../messages/doi.json"),
  "mni": () => import("../../messages/mni.json"),
  "brx": () => import("../../messages/brx.json"),
  "sa": () => import("../../messages/sa.json"),
};

// English is the fallback every other locale falls back to (I18nProvider takes
// it as `enMessages`), so it is always loaded — but still lazily, so it is a
// chunk rather than root-graph weight.
export async function loadMessages(locale: string): Promise<MessageBundle> {
  const loader = MESSAGE_LOADERS[locale] || MESSAGE_LOADERS[DEFAULT_LOCALE];
  return (await loader()).default as MessageBundle;
}

// Content hash of a locale's bundle (plus the English fallback it resolves
// against), memoized per process. It is the cache key I18nProvider appends
// when it fetches the full catalog from /api/i18n/<locale> - see that route
// for why a content hash rather than a build id (nothing at runtime exposes
// the build id reliably, and the catalog is what actually has to match).
const versionCache = new Map<string, Promise<string>>();
export function messagesVersion(locale: string): Promise<string> {
  const key = MESSAGE_LOADERS[locale] ? locale : DEFAULT_LOCALE;
  let p = versionCache.get(key);
  if (p === undefined) {
    p = (async () => {
      const { createHash } = await import("node:crypto");
      const [active, en] = await Promise.all([loadMessages(key), loadMessages(DEFAULT_LOCALE)]);
      return createHash("sha1").update(JSON.stringify(active)).update(JSON.stringify(en)).digest("hex").slice(0, 12);
    })();
    versionCache.set(key, p);
  }
  return p;
}
