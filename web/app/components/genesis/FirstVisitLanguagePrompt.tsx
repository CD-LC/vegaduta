"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "motion/react";
import { Globe, ArrowRight, X } from "lucide-react";
import BrandName from "../BrandName";
import { useLocale } from "../../lib/i18n";
import { SUPPORTED_LOCALES, LOCALE_COOKIE } from "../../lib/i18n-config";
import { useRegion } from "../../lib/useRegion";
import { Button } from "../ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

// First-visit language prompt for India visitors. The proxy already geo-sets
// NEXT_LOCALE=hi for many Indian requests, and LanguageSwitcher already
// persists an explicit choice — this component adds the PROACTIVE first-visit
// invitation on top: if an India visitor lands on the English default and has
// never been asked, offer their regional language in that language, with a
// "make it my default" option, then get out of the way for good.
//
// It renders nothing on the server and nothing until an effect decides to show
// it, so there's no hydration flash. Once dismissed (either way) it sets a
// localStorage flag and never nags again — and it also stays silent if a
// NEXT_LOCALE cookie already exists (the person, or geo, already decided).
const SEEN_KEY = "vd_lang_prompt_seen";

// Native greeting + CTA for the languages we can render confidently. The
// STRUCTURE stays bilingual (an English-defaulted India visitor reads English
// fine); what matters is greeting them in their own script. Anything detected
// outside this set falls back to Hindi — India's most widely understood link
// language — so the prompt is always shown in confident, correct native text.
const GREETINGS: Record<string, { hello: string; ask: string; cta: string }> = {
  hi: { hello: "नमस्ते! 👋", ask: "क्या आप VegaDūta को हिन्दी में देखना चाहेंगे?", cta: "हिन्दी में देखें" },
  bn: { hello: "নমস্কার! 👋", ask: "আপনি কি VegaDūta বাংলায় দেখতে চান?", cta: "বাংলায় দেখুন" },
  ta: { hello: "வணக்கம்! 👋", ask: "VegaDūta-ஐ தமிழில் பார்க்க விரும்புகிறீர்களா?", cta: "தமிழில் காட்டு" },
  te: { hello: "నమస్కారం! 👋", ask: "VegaDūtaని తెలుగులో చూడాలనుకుంటున్నారా?", cta: "తెలుగులో చూడండి" },
  mr: { hello: "नमस्कार! 👋", ask: "तुम्हाला VegaDūta मराठीत पाहायचे आहे का?", cta: "मराठीत पाहा" },
  gu: { hello: "નમસ્તે! 👋", ask: "શું તમે VegaDūta ગુજરાતીમાં જોવા માંગો છો?", cta: "ગુજરાતીમાં જુઓ" },
  kn: { hello: "ನಮಸ್ಕಾರ! 👋", ask: "VegaDūtaವನ್ನು ಕನ್ನಡದಲ್ಲಿ ನೋಡಬೇಕೇ?", cta: "ಕನ್ನಡದಲ್ಲಿ ನೋಡಿ" },
  ml: { hello: "നമസ്കാരം! 👋", ask: "VegaDūta മലയാളത്തിൽ കാണണോ?", cta: "മലയാളത്തിൽ കാണുക" },
  pa: { hello: "ਸਤ ਸ੍ਰੀ ਅਕਾਲ! 👋", ask: "ਕੀ ਤੁਸੀਂ VegaDūta ਪੰਜਾਬੀ ਵਿੱਚ ਵੇਖਣਾ ਚਾਹੋਗੇ?", cta: "ਪੰਜਾਬੀ ਵਿੱਚ ਵੇਖੋ" },
  or: { hello: "ନମସ୍କାର! 👋", ask: "VegaDūta ଓଡ଼ିଆରେ ଦେଖିବାକୁ ଚାହାଁନ୍ତି କି?", cta: "ଓଡ଼ିଆରେ ଦେଖନ୍ତୁ" },
  ur: { hello: "السلام علیکم! 👋", ask: "کیا آپ VegaDūta اردو میں دیکھنا چاہیں گے؟", cta: "اردو میں دیکھیں" },
};

function detectSuggestedLocale(): string {
  try {
    const langs = [navigator.language, ...(navigator.languages || [])].filter(Boolean).map((l) => l.toLowerCase());
    for (const l of langs) {
      const base = l.split("-")[0];
      if (GREETINGS[base]) return base;
    }
  } catch {
    /* navigator unavailable */
  }
  return "hi"; // India's most widely understood link language
}

function hasLocaleCookie(): boolean {
  if (typeof document === "undefined") return false;
  return document.cookie.split(";").some((c) => c.trim().startsWith(`${LOCALE_COOKIE}=`));
}

export default function FirstVisitLanguagePrompt() {
  const locale = useLocale();
  const router = useRouter();
  const { isIndia, ready } = useRegion();
  const [open, setOpen] = useState(false);
  const [makeDefault, setMakeDefault] = useState(true);
  const [suggested, setSuggested] = useState("hi");

  useEffect(() => {
    if (!ready) return;
    // Only invite India visitors who are on the English default and have never
    // been asked / never chosen a language.
    if (!isIndia) return;
    if (locale !== "en") return;
    if (hasLocaleCookie()) return;
    let seen = false;
    try {
      seen = localStorage.getItem(SEEN_KEY) === "1";
    } catch {
      /* storage blocked — treat as not-seen, we'll still set the flag on close */
    }
    if (seen) return;
    // Mark asked at OPEN time, not on close: the most common exit from this
    // prompt is a navigation/reload with it still on screen, and the old
    // mark-on-close contract re-asked on every visit for anyone who never
    // clicked a button (repeat-popup audit, 2026-09-23). If the write throws
    // (private mode / blocked site data) we skip opening entirely - an
    // un-persistable prompt would nag on every page view, worse than never
    // asking.
    try {
      window.localStorage.setItem(SEEN_KEY, "1");
    } catch {
      return;
    }
    setSuggested(detectSuggestedLocale());
    setOpen(true);
  }, [ready, isIndia, locale]);

  function markSeen() {
    try {
      localStorage.setItem(SEEN_KEY, "1");
    } catch {
      /* storage blocked — the NEXT_LOCALE cookie set below still prevents a re-prompt */
    }
  }

  function applyLocale(code: string, durable: boolean) {
    // Durable → 1-year cookie (LanguageSwitcher's exact contract, so it wins
    // over geo on every later request). Not durable → a session cookie that
    // still applies now but doesn't outlive the browser session.
    const maxAge = durable ? `; max-age=${60 * 60 * 24 * 365}` : "";
    document.cookie = `${LOCALE_COOKIE}=${code}; path=/${maxAge}`;
  }

  function chooseRegional() {
    applyLocale(suggested, makeDefault);
    markSeen();
    setOpen(false);
    router.refresh(); // re-run the server-side locale read with the new cookie
  }

  function dismiss() {
    // "Continue in English" — record the choice so we don't ask again. If they
    // ticked "make default", make English durable too; otherwise just mark seen.
    if (makeDefault) applyLocale("en", true);
    markSeen();
    setOpen(false);
    if (makeDefault) router.refresh();
  }

  const g = GREETINGS[suggested] ?? GREETINGS.hi;
  const localeDef = SUPPORTED_LOCALES.find((l) => l.code === suggested);
  const isRtl = localeDef?.dir === "rtl";

  return (
    <AnimatePresence>
      {open && (
        <LanguagePromptDialog
          g={g}
          isRtl={isRtl}
          makeDefault={makeDefault}
          setMakeDefault={setMakeDefault}
          chooseRegional={chooseRegional}
          dismiss={dismiss}
        />
      )}
    </AnimatePresence>
  );
}

// Its own component so useDialogFocus's mount-time effect (focus in, Tab
// trap, Escape close, focus restore on close) re-runs every time the dialog
// actually opens — the parent above never unmounts, only this does.
function LanguagePromptDialog({
  g,
  isRtl,
  makeDefault,
  setMakeDefault,
  chooseRegional,
  dismiss,
}: {
  g: { hello: string; ask: string; cta: string };
  isRtl: boolean;
  makeDefault: boolean;
  setMakeDefault: (v: boolean) => void;
  chooseRegional: () => void;
  dismiss: () => void;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  useDialogFocus(panelRef, closeRef, dismiss);

  return (
    <motion.div
      className="fixed inset-0 z-[100] flex items-end justify-center p-4 sm:items-center"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.2 }}
      role="dialog"
      aria-modal="true"
      aria-label="Choose your language"
    >
      {/* Backdrop */}
      <button
        type="button"
        aria-label="Continue in English"
        onClick={dismiss}
        className="absolute inset-0 cursor-default bg-black/60 backdrop-blur-sm"
      />

      <motion.div
        ref={panelRef}
        className="relative max-h-[90vh] w-full max-w-md overflow-y-auto overscroll-contain rounded-2xl border border-border bg-card text-foreground shadow-2xl"
        initial={{ opacity: 0, y: 24, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 24, scale: 0.97 }}
        transition={{ type: "spring", stiffness: 260, damping: 24 }}
      >
        <div
          aria-hidden
          className="pointer-events-none absolute -right-16 -top-16 size-40 rounded-full bg-primary/25 blur-[70px]"
        />

        <button
          ref={closeRef}
          type="button"
          onClick={dismiss}
          aria-label="Close"
          className="absolute right-3 top-3 z-10 flex size-8 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
        >
          <X aria-hidden="true" className="size-4" />
        </button>

        <div className="relative p-6 sm:p-7">
          <div className="flex size-11 items-center justify-center rounded-xl bg-primary/12 text-primary">
            <Globe aria-hidden="true" className="size-6" />
          </div>

          <div dir={isRtl ? "rtl" : "ltr"} className="mt-5">
            <h2 className="text-2xl font-semibold tracking-tight">{g.hello}</h2>
            <p className="mt-2 text-pretty text-muted-foreground">{g.ask}</p>
          </div>

          {/* English structural line — an English-defaulted visitor reads this fine */}
          <p className="mt-3 text-sm text-muted-foreground">
            Prefer another language? The <BrandName /> console is available in all 22 official Indian languages.
          </p>

          <label className="mt-5 flex cursor-pointer items-center gap-2.5 text-sm">
            <input
              type="checkbox"
              checked={makeDefault}
              onChange={(e) => setMakeDefault(e.target.checked)}
              className="size-4 accent-[var(--primary)]"
            />
            <span className="text-muted-foreground">Make this my default language</span>
          </label>

          <div className="mt-6 flex flex-col gap-2.5">
            <Button onClick={chooseRegional} size="lg" className="w-full justify-center">
              {g.cta}
              <ArrowRight aria-hidden="true" className="size-4" />
            </Button>
            <Button
              onClick={dismiss}
              variant="ghost"
              size="lg"
              className="w-full justify-center text-muted-foreground hover:text-foreground"
            >
              Continue in English
            </Button>
          </div>

          <p className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-muted-foreground">
            <Globe aria-hidden="true" className="size-3.5" />
            You can change the language anytime from the menu at the top.
          </p>
        </div>
      </motion.div>
    </motion.div>
  );
}
