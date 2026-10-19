"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { Globe2 } from "lucide-react";
import {
  SUPPORTED_LOCALES,
  DEFAULT_LOCALE,
  LOCALE_COOKIE,
  splitLocalePrefix,
  localizedPath,
  type LocaleDef,
} from "../lib/i18n-config";
import { useTranslations } from "../lib/i18n";
import { useGeo } from "../lib/geo";
import { Button } from "./ui/button";
import BrandText from "./BrandText";
import { setLanguagePromptPending } from "../lib/firstRunGate";

const STORAGE_KEY = "agentic_language_choice_seen_v1";
// CORS-open IP-geo (same provider WeatherThemeEngine uses since 2026-07-28 -
// ipapi.co started CORS-blocking this origin, logging a browser console error
// on every load even though the catch handled it). Only a fallback here: the
// CF-IPCountry header via useGeo() is preferred and normally answers first.
const GEO_URL = "https://get.geojs.io/v1/ip/geo.json";

// Elements a keyboard user can land on while tabbing inside the dialog - same
// selector/trap pattern as UpgradePrompt.tsx, used both to pick the initial
// focus target and to wrap Tab/Shift+Tab so focus can't escape into the page
// behind this (aria-modal) dialog.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Direct feedback: "based on the IP consumer is accessing from - application
// should ask consumer a choice of languages available in their country, all
// list - so consumer can select the language or display english by
// default." This is deliberately separate from proxy.js's own
// COUNTRY_TO_LOCALE (a single silent best-guess used to pick a locale for
// the very first, pre-JS server render, so the page never flashes English
// before English is even the answer). This component is the "ask, don't
// silently assume" layer on top of that: a country can map to several
// SUPPORTED_LOCALES entries here (e.g. all 22 Indian scheduled languages
// for IN), and the person explicitly picks one instead of the app guessing
// for them. English is always offered and is what happens with no action.
const COUNTRY_LANGUAGES: Record<string, string[]> = {
  IN: [
    "hi",
    "bn",
    "mr",
    "te",
    "ta",
    "gu",
    "ur",
    "kn",
    "or",
    "ml",
    "pa",
    "as",
    "mai",
    "sat",
    "ks",
    "ne",
    "kok",
    "sd",
    "doi",
    "mni",
    "brx",
    "sa",
    "en",
  ],
  ES: ["es", "en"],
  MX: ["es", "en"],
  AR: ["es", "en"],
  CO: ["es", "en"],
  CL: ["es", "en"],
  PE: ["es", "en"],
  FR: ["fr", "en"],
  BE: ["fr", "en"],
  DE: ["de", "en"],
  AT: ["de", "en"],
  CH: ["de", "en"],
  PT: ["pt", "en"],
  BR: ["pt", "en"],
  SA: ["ar", "en"],
  AE: ["ar", "en"],
  EG: ["ar", "en"],
  QA: ["ar", "en"],
  KW: ["ar", "en"],
  CN: ["zh", "en"],
  TW: ["zh", "en"],
  HK: ["zh", "en"],
  SG: ["zh", "en"],
  JP: ["ja", "en"],
  NP: ["ne", "hi", "en"],
  PK: ["ur", "en"],
  BD: ["bn", "en"],
  LK: ["ta", "en"],
};

const LOCALE_BY_CODE = new Map(SUPPORTED_LOCALES.map((l) => [l.code, l]));

/**
 * First-visit "choose your language" prompt, shown once (localStorage flag,
 * same pattern as OnboardingTour's STORAGE_KEY - a pure client preference,
 * no reason to sync across devices). IP-based country lookup reuses the
 * exact ipapi.co/json/ endpoint WeatherThemeEngine.js already calls for its
 * own IP geolocation - no navigator.geolocation permission prompt, same
 * "coarse and silent" trade-off that component's own comment explains.
 *
 * Renders nothing at all when the visitor's country isn't in
 * COUNTRY_LANGUAGES, or only maps to English - there's no real choice to
 * offer in that case, so no point interrupting anyone.
 *
 * `onDone` fires once this prompt has resolved either way (nothing to show,
 * or the person picked/dismissed it) - optional, for a parent that wants to
 * know when it has settled. It used to also gate OnboardingTour's first-visit
 * auto-open (so the two modals never appeared stacked back-to-back); that
 * auto-open was removed instead (UX audit - see OnboardingTour.tsx's own
 * comment), so this is the only Day-1 auto-shown modal now and nothing needs
 * to wait on it anymore.
 */
export default function LanguageChoicePrompt({ onDone = undefined }: { onDone?: () => void } = {}) {
  const t = useTranslations("languageChoicePrompt");
  const router = useRouter();
  // Country resolved server-side from Cloudflare's CF-IPCountry header (see
  // lib/geo.tsx) - preferred over the third-party ipapi.co lookup below.
  const { country: geoCountry } = useGeo();
  const [mounted, setMounted] = useState(false);
  const [options, setOptions] = useState<LocaleDef[] | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => setMounted(true), []);

  // Escape-to-close plus a Tab/Shift+Tab trap that keeps focus cycling within
  // the dialog's own focusable elements while it's open - same pattern as
  // UpgradePrompt.tsx/UpgradeCelebration.tsx. Escape maps to dismiss() (not
  // useEnglish()) so it reads as a neutral "close" rather than an active
  // language pick, matching dismiss()'s own "closing without an explicit pick
  // still counts as asked" contract.
  useEffect(() => {
    if (!options) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        dismiss();
        return;
      }
      if (e.key === "Tab" && modalRef.current) {
        const focusable = modalRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options]);

  // On open: remember what had focus and move focus into the dialog. On
  // close (or unmount): give focus back to whatever triggered the dialog.
  useEffect(() => {
    if (!options || !mounted) return undefined;
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = modalRef.current;
    if (node) {
      const focusable = node.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      if (focusable.length > 0) {
        focusable[0].focus();
      } else {
        node.focus();
      }
    }
    return () => {
      if (previousFocusRef.current && typeof previousFocusRef.current.focus === "function") {
        previousFocusRef.current.focus();
      }
    };
  }, [options, mounted]);

  useEffect(() => {
    let cancelled = false;

    function finish() {
      setLanguagePromptPending(false);
      if (cancelled) return;
      onDone?.();
    }

    let alreadySeen = false;
    try {
      alreadySeen = Boolean(window.localStorage.getItem(STORAGE_KEY));
    } catch {
      // No storage available - never persistently "seen", so skip asking
      // entirely rather than nagging on every visit.
      finish();
      return undefined;
    }

    if (alreadySeen) {
      finish();
      return undefined;
    }

    // May still ask: hold the product tour back until this resolves (lib/firstRunGate).
    setLanguagePromptPending(true);

    async function run() {
      // Prefer the country Cloudflare already told us (CF-IPCountry, read
      // server-side in RootLayout and carried down via GeoProvider). The
      // ipapi.co call below is now only a fallback for deployments not behind
      // Cloudflare: it is a cross-origin request to a third party that is
      // CORS-blocked (and rate-limited) in this deployment, and even though the
      // catch handles it, the BROWSER still logs an uncaught network error on
      // every single page load - which is exactly what the E2E console-error
      // trap caught. Using the header we already have removes that noise, one
      // network round-trip, and a third-party dependency on the critical path.
      let country: string | null = geoCountry ? geoCountry.toUpperCase() : null;

      if (!country) {
        let geo;
        try {
          const res = await fetch(GEO_URL);
          if (!res.ok) {
            finish();
            return;
          }
          geo = await res.json();
        } catch {
          finish();
          return; // offline, blocked, or rate-limited - nothing to ask, move on
        }
        if (cancelled) return;
        country = geo?.country_code ? geo.country_code.toUpperCase() : null;
      }
      const codes = country ? COUNTRY_LANGUAGES[country] : null;
      const resolved = (codes || [])
        .map((c: string) => LOCALE_BY_CODE.get(c))
        .filter((l): l is LocaleDef => Boolean(l));

      if (resolved.length > 1) {
        setOptions(resolved);
        // Deliberately not calling finish() here - it fires once the
        // person actually closes this prompt (see choose()/dismiss() below).
      } else {
        finish();
      }
    }

    // `void`: run() owns its own errors (the geo lookup is best-effort and
    // falls through to finish()), so there is nothing for a caller to await
    // or handle - marking it ignored rather than leaving it floating.
    void run();
    return () => {
      cancelled = true;
      setLanguagePromptPending(false);
    };
    // geoCountry is server-resolved and stable for the life of the page, but
    // list it so the effect re-runs if a provider ever supplies it late.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [geoCountry]);

  function markSeen() {
    try {
      window.localStorage.setItem(STORAGE_KEY, "1");
    } catch {
      // Best-effort only - worst case this asks again next visit.
    }
  }

  function choose(code: string) {
    document.cookie = `${LOCALE_COOKIE}=${code}; path=/; max-age=${60 * 60 * 24 * 365}`;
    setLanguagePromptPending(false);
    markSeen();
    setOptions(null);
    onDone?.();
    // Same rule as LanguageSwitcher: on "/hi/..." the URL outranks the cookie,
    // so a soft refresh would come back in the same language - navigate to the
    // chosen language's own URL instead. router.push/refresh (client-side),
    // NOT window.location.assign/reload (a full hard browser navigation) -
    // this component is mounted globally in the authenticated console shell
    // (AppShell), so a hard nav here discarded lib/auth.ts's module-level
    // Keycloak singleton on every first-visit language choice and forced a
    // fresh silent check-sso cycle that modern browsers' third-party-cookie
    // partitioning routinely fails, logging the user out. See
    // LanguageSwitcher.tsx's navigateToLocaleUrl for the full explanation.
    const { locale: urlLocale, pathname } = splitLocalePrefix(window.location.pathname);
    if (urlLocale) {
      router.push(localizedPath(pathname, code) + window.location.search + window.location.hash);
      return;
    }
    router.refresh();
  }

  function useEnglish() {
    choose(DEFAULT_LOCALE);
  }

  function dismiss() {
    // Closing without an explicit pick still counts as "asked" - whatever
    // locale proxy.js already detected/rendered stands (English by
    // default, matching "display english by default").
    markSeen();
    setOptions(null);
    setLanguagePromptPending(false);
    onDone?.();
  }

  if (!mounted || !options) return null;

  return createPortal(
    <div
      className="hud-tour-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) dismiss();
      }}
    >
      <div
        ref={modalRef}
        className="hud-tour-card lang-choice-card"
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={t("dialogLabel")}
      >
        {/* Was `.row` (flex-wrap) + an inline space-between: once the eyebrow
            and the count pill stopped fitting side by side - narrow phone, or
            simply a longer translation of "Language detection" - the pill
            wrapped onto its own line and space-between then flung it to one
            edge, reading as a misaligned stray chip. `.lang-choice-head` keeps
            them on one row and lets the eyebrow shrink instead. */}
        <div className="lang-choice-head">
          <span className="hud-tour-eyebrow">
            <Globe2 aria-hidden="true" size={12} />
            <span className="hud-tour-eyebrow-dot" aria-hidden="true" />
            {t("eyebrow")}
          </span>
          <span className="lang-choice-count">{t("availableCount", { count: options.length })}</span>
        </div>

        <h2 className="hud-tour-title">
          <BrandText text={t("title")} />
        </h2>
        <p className="hud-tour-body">
          <BrandText text={t("body")} />
        </p>

        <div className="lang-choice-grid" role="listbox" aria-label={t("listboxLabel")}>
          {options.map((l: LocaleDef, i: number) => (
            <button
              key={l.code}
              type="button"
              role="option"
              // Required on every role="option" (and it was missing): a
              // listbox whose options never announce selection state reads
              // as broken to a screen reader. Always false here - this
              // prompt appears BEFORE a choice exists, so nothing in the
              // grid is the current language yet.
              aria-selected={false}
              className="lang-choice-cell"
              onClick={() => choose(l.code)}
            >
              <span className="lang-choice-cell-index">{String(i + 1).padStart(2, "0")}</span>
              <span className="lang-choice-cell-label">{l.label}</span>
              <span className="lang-choice-cell-code">{l.code}</span>
            </button>
          ))}
        </div>

        <div className="hud-tour-footer">
          <Button type="button" variant="ghost" onClick={dismiss}>
            {t("notNow")}
          </Button>
          <Button type="button" onClick={useEnglish}>
            {t("continueEnglish")}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
