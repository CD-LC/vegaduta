"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Globe, Check, Download, Loader2, ArrowLeft } from "lucide-react";
import { useLocale, useTranslations } from "../lib/i18n";
import { SUPPORTED_LOCALES, LOCALE_COOKIE, splitLocalePrefix, localizedPath } from "../lib/i18n-config";
import { packNeedsConsent, grantPackConsent, packSizeLabel } from "../lib/langPacks";

// On a locale-prefixed URL ("/hi/billing") the URL decides the language, so
// writing the cookie and refreshing would change nothing. Go to the chosen
// language's own URL instead - or to the unprefixed one when that language
// has none (English, or a locale outside INDEXABLE_LOCALES). Returns false on
// an unprefixed URL, where the cookie alone is still the whole mechanism.
//
// router.push (client-side transition), NOT window.location.assign (a full
// hard browser navigation) - this used to be a hard nav, and on one of the
// four locale-prefixed routes ("/", "/billing", "/help", "/signup", see
// LOCALIZED_ROUTES) that included "/", which renders the authenticated
// Dashboard. A hard nav discards lib/auth.ts's module-level Keycloak
// singleton and forces a brand-new silent check-sso cycle, whose hidden
// iframe modern browsers' third-party-cookie partitioning routinely blocks -
// and the one recovery path for that (AuthProvider's tryTopLevelSsoRecovery)
// is a one-shot-per-tab guard, so a second language switch in the same tab
// had no second chance and fell through to "unauthenticated", logging the
// user out even though their Keycloak SSO session was still alive. A client
// transition keeps the singleton (and the whole React tree) alive; the root
// layout already reads locale from a per-request header (see layout.tsx),
// so it re-resolves correctly on this navigation exactly as it does on any
// other route change.
function navigateToLocaleUrl(router: ReturnType<typeof useRouter>, code: string): boolean {
  const { locale: urlLocale, pathname } = splitLocalePrefix(window.location.pathname);
  if (!urlLocale) return false;
  router.push(localizedPath(pathname, code) + window.location.search + window.location.hash);
  return true;
}

// The one control this whole system needs to satisfy "user should be able
// to change the language to english at any point in time" - available from
// the console topbar (AppShell.js) and from the signup/sign-in shell, the
// two places someone is looking at this app before they'd know a Settings
// page exists at all.
//
// Writes the same NEXT_LOCALE cookie proxy.js sets on first visit, so
// an explicit choice here always wins over auto-detection on every later
// request (see proxy.js's precedence comment) - picking English here
// is exactly as durable as picking any other language, not a one-time
// override that geo-detection would silently undo next visit.
//
// router.refresh() (not a full page reload) re-runs layout.js's server-side
// cookie read with the new value and re-renders with the new messages -
// client-side React state (open panels, form input, etc.) elsewhere on the
// page is preserved.
/** Gap kept between the open menu and the viewport edge when fitting it. */
const EDGE_MARGIN = 8;

/**
 * Floor for the fitted menu height - below this a dropdown is unusable, so
 * it is better to overflow slightly (and flip, see the layout effect) than to
 * render a 30-item list into a 40px slot.
 */
const MIN_MENU_HEIGHT = 160;

export default function LanguageSwitcher({ className = "" }: { className?: string }) {
  const locale = useLocale();
  const t = useTranslations("languageSwitcher");
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [packError, setPackError] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLElement>(null);

  // Edge flip, measured rather than assumed (mobile-nav audit, 2026-09-11).
  // The menu is anchored `right-0` - its right edge to the trigger's - which
  // is correct for the topbar/nav placements, where the trigger sits at the
  // RIGHT of its row. In the landing page's mobile sheet the trigger sits at
  // the LEFT margin instead, and a 146px menu hanging leftward off a ~105px
  // trigger rendered at left:-41px, a third of the language list clipped off
  // the side of the screen. Measuring covers every placement (and every
  // locale's trigger width, which is why a fixed class per call site would
  // not) instead of making each caller declare which way it should open.
  useLayoutEffect(() => {
    const menu = menuRef.current;
    const root = rootRef.current;
    if (!open || !menu || !root) return;
    // Reset first: this runs again on every open, and a flip decided for the
    // previous viewport must not stick.
    menu.style.left = "";
    menu.style.right = "";
    menu.style.top = "";
    menu.style.bottom = "";
    menu.style.maxHeight = "";

    const dir = document.documentElement.getAttribute("dir");
    const isRtl = dir === "rtl";

    if (isRtl) {
      // RTL: menu should flip to the right if not enough space on the left
      const menuRect = menu.getBoundingClientRect();
      if (window.innerWidth - menuRect.right < EDGE_MARGIN) {
        menu.style.right = "0";
        menu.style.left = "auto";
      }
    } else {
      // LTR: menu should flip to the left if not enough space on the right
      if (menu.getBoundingClientRect().left < EDGE_MARGIN) {
        menu.style.left = "0";
        menu.style.right = "auto";
      }
    }

    // Vertical fit. The trigger can sit low on the page (the landing page's
    // mobile sheet puts it ~640px down a 812px screen), and the menu lives
    // inside a `position: sticky` header - so it moves WITH the header and
    // scrolling the page does NOT bring a cut-off menu into view. It has to
    // be sized, or flipped, to the room that actually exists.
    const trigger = root.getBoundingClientRect();
    const below = window.innerHeight - trigger.bottom - EDGE_MARGIN;
    const above = trigger.top - EDGE_MARGIN;
    if (below < MIN_MENU_HEIGHT && above > below) {
      // Not enough room underneath and more of it overhead: open upward.
      menu.style.top = "auto";
      menu.style.bottom = "100%";
      menu.style.maxHeight = Math.floor(above) + "px";
    } else {
      menu.style.maxHeight = Math.floor(Math.max(below, MIN_MENU_HEIGHT)) + "px";
    }
  }, [open, pending]);

  // Move focus onto the current locale's option (or the first one) as soon
  // as the menu opens, and let Arrow/Home/End rove between options - the
  // listbox pattern's usual keyboard model (a11y audit follow-up: this menu
  // previously had no arrow-key handling at all).
  useEffect(() => {
    if (!open) return;
    const menu = menuRef.current;
    if (!menu) return;
    const buttons = Array.from(menu.querySelectorAll<HTMLButtonElement>('button[role="option"]'));
    const active = buttons.find((b) => b.getAttribute("aria-selected") === "true") ?? buttons[0];
    active?.focus();
  }, [open]);

  function onMenuKeyDown(e: React.KeyboardEvent<HTMLElement>) {
    const menu = menuRef.current;
    if (!menu) return;
    const buttons = Array.from(menu.querySelectorAll<HTMLButtonElement>('button[role="option"]'));
    if (buttons.length === 0) return;
    const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === "ArrowDown") {
      e.preventDefault();
      buttons[(current + 1 + buttons.length) % buttons.length]?.focus();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      buttons[(current - 1 + buttons.length) % buttons.length]?.focus();
    } else if (e.key === "Home") {
      e.preventDefault();
      buttons[0]?.focus();
    } else if (e.key === "End") {
      e.preventDefault();
      buttons[buttons.length - 1]?.focus();
    }
  }

  // Click-outside + Escape (mobile-nav audit, 2026-09-11). This menu is 30
  // options tall and, on a phone, covers most of what's behind it - without
  // these two handlers the ONLY way back out was to find and re-tap the
  // trigger underneath it, which the menu itself is usually covering.
  // Matches ThemePicker.js, the sibling control sitting next to this one.
  useEffect(() => {
    if (!open) return undefined;
    function onPointerDown(e: MouseEvent | TouchEvent) {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    // `mousedown`/`touchstart` rather than `click`: closes on the press, so
    // a tap that lands outside doesn't also activate whatever is under it.
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("touchstart", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("touchstart", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
      // A close of any kind drops a half-answered consent prompt: reopening
      // shows the language list again, not a stale "Download X?" panel.
      setPending(null);
    };
  }, [open]);

  function applyLocale(code: string) {
    document.cookie = `${LOCALE_COOKIE}=${code}; path=/; max-age=${60 * 60 * 24 * 365}`;
    setOpen(false);
    setPending(null);
    if (navigateToLocaleUrl(router, code)) return;
    router.refresh();
  }

  function selectLocale(code: string) {
    // Personal edition: a language nobody has asked for is kept off the
    // device path entirely (see lib/langPacks.ts) - first selection is a
    // consent step, not a silent ~0.6 MB fetch.
    if (packNeedsConsent(code)) {
      setPending(code);
      return;
    }
    applyLocale(code);
  }

  async function confirmPack() {
    if (!pending) return;
    setDownloading(true);
    setPackError(false);
    try {
      // Real fetch, not theatre: warm /api/i18n/<locale> so the switch that
      // follows never shows an empty catalog on a slow link, and so a refused
      // connection fails the CONSENT, not the whole locale switch.
      const res = await fetch(`/api/i18n/${encodeURIComponent(pending)}`, { credentials: "omit" });
      if (!res.ok) throw new Error(String(res.status));
      grantPackConsent(pending);
      applyLocale(pending);
    } catch {
      setPackError(true);
    } finally {
      setDownloading(false);
    }
  }

  const current = SUPPORTED_LOCALES.find((l) => l.code === locale) || SUPPORTED_LOCALES[0];
  const pendingLabel = pending ? (SUPPORTED_LOCALES.find((l) => l.code === pending)?.label ?? pending) : null;

  return (
    <div ref={rootRef} className={`lang-switcher ${className}`}>
      <button
        type="button"
        className="lang-switcher-trigger"
        onClick={() => {
          setPending(null);
          setOpen((v) => !v);
        }}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Language: ${current.label}`}
      >
        <Globe aria-hidden="true" size={15} strokeWidth={2} />
        <span className="topbar-label">{current.label}</span>
      </button>
      {open && pending && (
        <div
          ref={menuRef as React.RefObject<HTMLDivElement>}
          className="lang-switcher-menu lang-switcher-consent"
          role="dialog"
          aria-label={t("packTitle", { label: pendingLabel })}
        >
          <p className="px-2.5 pb-1 pt-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            {t("packTitle", { label: pendingLabel })}
          </p>
          <p className="px-2.5 pb-2 text-xs text-muted-foreground">
            {t("packBody", { label: pendingLabel, size: packSizeLabel(pending) })}
          </p>
          {packError && <p className="px-2.5 pb-1.5 text-xs text-destructive">{t("packFailed")}</p>}
          <div className="flex items-center gap-1.5 px-1 pb-0.5">
            <button
              type="button"
              className="lang-switcher-option lang-switcher-option-active"
              onClick={() => void confirmPack()}
              disabled={downloading}
            >
              {downloading ? (
                <Loader2 size={13} className="animate-spin" aria-hidden="true" />
              ) : (
                <Download size={13} strokeWidth={2.5} aria-hidden="true" />
              )}
              <span>{downloading ? t("packDownloading") : t("packDownload")}</span>
            </button>
            <button
              type="button"
              className="lang-switcher-option"
              onClick={() => setPending(null)}
              disabled={downloading}
              aria-label={t("packCancel")}
            >
              <ArrowLeft size={13} strokeWidth={2.5} aria-hidden="true" />
            </button>
          </div>
        </div>
      )}
      {open && !pending && (
        <ul
          ref={menuRef as React.RefObject<HTMLUListElement>}
          className="lang-switcher-menu"
          role="listbox"
          aria-label={`${SUPPORTED_LOCALES.length} languages`}
          onKeyDown={onMenuKeyDown}
        >
          <li
            role="presentation"
            aria-hidden="true"
            className="px-2.5 pb-1 pt-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground"
          >
            {SUPPORTED_LOCALES.length} languages
          </li>
          {SUPPORTED_LOCALES.map((l) => (
            <li key={l.code} role="presentation">
              <button
                type="button"
                role="option"
                aria-selected={l.code === locale}
                className={`lang-switcher-option${l.code === locale ? " lang-switcher-option-active" : ""}`}
                onClick={() => selectLocale(l.code)}
              >
                <span>{l.label}</span>
                {l.code === locale ? (
                  <Check size={14} strokeWidth={2.5} aria-hidden="true" />
                ) : (
                  packNeedsConsent(l.code) && (
                    <Download size={12} strokeWidth={2} aria-hidden="true" className="text-muted-foreground" />
                  )
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
