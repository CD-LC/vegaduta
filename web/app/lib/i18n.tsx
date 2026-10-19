"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { SUPPORTED_LOCALES, DEFAULT_LOCALE, LOCALE_COOKIE } from "./i18n-config";

// Hand-rolled instead of pulling in next-intl or react-intl: this sandbox's
// npm registry access is blocked (confirmed while investigating this - a
// separate `npx postcss-cli` install attempt during this same session
// returned a 403), so adding a new runtime dependency isn't actually
// possible here. This covers exactly what SUPPORTED_LOCALES need today -
// locale state, {placeholder} interpolation, and a dotted-path lookup with
// an English fallback - without pretending to be a general-purpose i18n
// library. See proxy.js for how the initial locale is chosen
// (Accept-Language / geo) and LanguageSwitcher.js for how a person changes
// or resets it.
//
// Deliberately NOT routing-based (no /es/, /fr/ URL prefixes): that would
// mean moving every existing route under an app/[locale]/ segment, which
// touches every page in this app and every Playwright spec that references
// a URL like /admin/users - a huge, unjustified regression risk for what
// this pass actually needs. Locale is cookie-based instead (see
// proxy.js), read server-side in layout.js and handed down as plain
// data - the URL for a given page is identical in every language.
//
// Re-exported from i18n-config.js so every existing import of these three
// names from "./i18n" (LanguageSwitcher.js, etc.) keeps working unchanged.
export { SUPPORTED_LOCALES, DEFAULT_LOCALE, LOCALE_COOKIE };

function getByPath(obj: any, path: any) {
  return path.split(".").reduce((acc: any, key: any) => (acc && typeof acc === "object" ? acc[key] : undefined), obj);
}

function interpolate(template: any, values: any) {
  if (!values) return template;
  return template.replace(/\{(\w+)\}/g, (match: any, key: any) => (key in values ? String(values[key]) : match));
}

const I18nContext = createContext(null);

// ---------------------------------------------------------------------------
// Lazy completion of a pruned catalog (prod latency pass, 2026-09-24)
// ---------------------------------------------------------------------------
// The root layout inlines only the namespaces the requested route can reach
// (app/lib/i18n-namespaces.ts) and passes `complete: false`. Two things then
// fill in the rest from /api/i18n/<locale>?v=<version> - one immutable-cached
// request, never on the critical path:
//   * an idle-time prefetch shortly after mount, so a client-side navigation
//     to another route already has that route's namespaces; and
//   * an immediate fetch the first time t() misses a key while the catalog is
//     still partial - the worst case is a brief flash of the key path on a
//     route the static analysis could not predict, not a permanent gap.
// While a fetch is in flight, misses keep returning the key path exactly as
// they always did for a genuinely missing key.
type Bundles = { messages: any; enMessages: any; complete: boolean };
const completionPromises = new Map<string, Promise<any>>();
// Resolved full catalogs, by locale:version. A later server render of the root
// layout (router.refresh(), a server action) hands I18nProvider fresh PRUNED
// props; without this the provider fell back to the partial catalog and, its
// one-shot fetch already spent, rendered raw key paths ("agentGlance.title")
// until a full page reload.
const completedCatalogs = new Map<string, Bundles>();
function catalogKey(locale: string, version: string | undefined): string {
  return `${locale}:${version || ""}`;
}
function fetchFullCatalog(locale: string, version: string | undefined): Promise<any> {
  const cacheKey = catalogKey(locale, version);
  if (!completionPromises.has(cacheKey)) {
    const url = `/api/i18n/${encodeURIComponent(locale)}${version ? `?v=${encodeURIComponent(version)}` : ""}`;
    const promise = fetch(url, { credentials: "omit" })
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`i18n catalog ${res.status}`))))
      .then((full) => {
        completedCatalogs.set(cacheKey, {
          messages: full.messages,
          // The English route answers `en: null` - its messages ARE the fallback.
          enMessages: full.en ?? full.messages,
          complete: true,
        });
        return full;
      })
      .catch((err) => {
        completionPromises.delete(cacheKey); // allow a retry on the next miss
        throw err;
      });
    completionPromises.set(cacheKey, promise);
  }
  return completionPromises.get(cacheKey)!;
}

// `messages` and `enMessages` (the English source, always loaded as a
// fallback) come from the server. `complete` is false when they were pruned
// to the route's namespaces; `version` is the catalog content hash the lazy
// fetch uses as its cache key.
export function I18nProvider({ locale, messages, enMessages, complete = true, version, children }: any) {
  const initial = (): Bundles =>
    (!complete && completedCatalogs.get(catalogKey(locale, version))) || { messages, enMessages, complete };
  const [bundles, setBundles] = useState<Bundles>(initial);
  const requestedRef = useRef(false);
  const retryRef = useRef<{ attempts: number; timer: ReturnType<typeof setTimeout> | null }>({
    attempts: 0,
    timer: null,
  });
  // A new server render replaces the bundles: a locale switch wholesale; a
  // re-render of the same locale keeps an already-completed catalog instead of
  // dropping back to the pruned one (see completedCatalogs).
  useEffect(() => {
    setBundles(initial());
    requestedRef.current = false;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, enMessages, complete, locale, version]);
  useEffect(
    () => () => {
      if (retryRef.current.timer) clearTimeout(retryRef.current.timer);
    },
    []
  );

  const requestCompletion = useCallback(() => {
    if (requestedRef.current || typeof window === "undefined") return;
    requestedRef.current = true;
    fetchFullCatalog(locale, version)
      .then(() => {
        const done = completedCatalogs.get(catalogKey(locale, version));
        if (done) setBundles(done);
        retryRef.current.attempts = 0;
      })
      .catch(() => {
        requestedRef.current = false;
        // A failed fetch used to wait for the next key miss, which never comes
        // while nothing re-renders: retry on its own, backing off, a few times.
        const r = retryRef.current;
        if (r.attempts >= 4 || r.timer) return;
        const delay = 2000 * 2 ** r.attempts++;
        r.timer = setTimeout(() => {
          r.timer = null;
          requestCompletion();
        }, delay);
      });
  }, [locale, version]);

  useEffect(() => {
    if (bundles.complete) return undefined;
    const w = window as any;
    const schedule =
      typeof w.requestIdleCallback === "function" ? w.requestIdleCallback : (fn: () => void) => setTimeout(fn, 1500);
    const cancel = typeof w.cancelIdleCallback === "function" ? w.cancelIdleCallback : clearTimeout;
    const id = schedule(() => requestCompletion(), { timeout: 4000 });
    return () => cancel(id);
  }, [bundles.complete, requestCompletion]);

  const t = useCallback(
    (namespace: any) => (key: any, values: any) => {
      const path = `${namespace}.${key}`;
      const value = getByPath(bundles.messages, path) ?? getByPath(bundles.enMessages, path);
      if (value == null) {
        if (!bundles.complete) {
          // Partial catalog and a miss: fetch the rest now. Starting a fetch
          // during render is idempotent (guarded above) and never sets state
          // synchronously, so React is unaffected.
          requestCompletion();
          return path;
        }
        // Missing key in every locale, including English - a real content
        // gap (see the migration plan in docs/I18N-MIGRATION-PLAN.md), not
        // something to silently swallow. Falls back to the key itself so
        // it's at least visibly wrong instead of blank.
        return path;
      }
      return interpolate(value, values);
    },
    [bundles, requestCompletion]
  );

  const value = useMemo(() => ({ locale, t }), [locale, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/** useTranslations("signup") returns a t(key, values?) function scoped to that namespace - same call shape as next-intl's useTranslations, so swapping to that library later (once/if npm access is available) is a drop-in replacement, not a rewrite. */
export function useTranslations(namespace: any) {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error("useTranslations must be used within I18nProvider");
  }
  // MUST memoize: ctx.t(namespace) builds a NEW scoped function on every
  // render, so returning it raw makes `t` an unstable reference. Any consumer
  // that puts `t` in a useCallback/useEffect/useMemo dep array (e.g.
  // admin/mcp-servers' refresh -> load effect) then re-runs every render -
  // an infinite re-fetch/re-render loop that reads as a flickering section.
  // ctx is itself memoized on [locale, t], so this stays stable until the
  // locale or messages actually change.
  return useMemo(() => ctx.t(namespace), [ctx, namespace]);
}

export function useLocale() {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error("useLocale must be used within I18nProvider");
  }
  return ctx.locale;
}
