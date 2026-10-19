"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type ThemeMode = "light" | "dark" | "system";
export type ResolvedThemeMode = "light" | "dark";

// Theme customizer (chosen over a full Chakra UI migration - see globals.css's
// file-level comment for why): six accent palettes plus light/dark/system
// mode, applied as `data-accent`/`data-mode` attributes on <html> and read
// by globals.css's CSS custom properties. Persisted to localStorage so a
// returning tenant's choice survives a reload; layout.js also runs a tiny
// synchronous inline script before hydration that applies the saved values
// immediately, so there's no flash of the default indigo/light theme before
// this provider's own effect runs.
const ACCENT_KEY = "agentic_theme_accent";
// Modern display options (2026-09-24): background mesh on/off, reduced
// motion, high contrast. Persisted per browser, applied as data-* attributes
// on <html> pre-paint (NO_FLASH_SCRIPT) and kept in sync here.
export const PREF_DEFAULTS = { bg: "mesh", motion: "full", contrast: "normal" } as const;
export type PrefKey = keyof typeof PREF_DEFAULTS;
const PREF_STORAGE: Record<PrefKey, string> = { bg: "agentic_theme_bg", motion: "agentic_theme_motion", contrast: "agentic_theme_contrast" };
export const PREF_OPTIONS: Record<PrefKey, string[]> = { bg: ["mesh", "plain"], motion: ["full", "reduced"], contrast: ["normal", "high"] };
const MODE_KEY = "agentic_theme_mode";
// Random light/dark per VISIT until the visitor picks one (direct instruction
// 2026-09-10: "just dark or light theme, it should be random on user visits
// until they set it explicitly"). Two keys, mirroring the accent precedence
// that already lives in this file:
//   MODE_EXPLICIT_KEY (localStorage) - set the moment ThemePicker is used.
//     Once present, the stored MODE_KEY wins forever and no roll happens.
//   SESSION_MODE_KEY (sessionStorage) - the roll for THIS visit. Rolling
//     once per session rather than per page load is deliberate: a fresh roll
//     on every navigation would flip the whole site light/dark mid-journey,
//     which reads as a bug, not a theme. A new visit (new tab/session) rolls
//     again, which is what "random on user visits" asks for.
// The roll itself happens in NO_FLASH_SCRIPT, before first paint; this
// provider only READS the result, so React can never disagree with the DOM.
const MODE_EXPLICIT_KEY = "agentic_theme_mode_explicit";
const SESSION_MODE_KEY = "agentic_theme_mode_session";
const DEFAULT_ACCENT = "indigo";
// DARK is the product default (direct instruction 2026-08-12: "make dark
// theme as default instead of geo time"). Previously "system", which the
// WeatherThemeEngine then corrected to local day/night — so a first-time
// visitor's theme depended on their clock. Now every visitor without an
// explicit ThemePicker choice lands on dark; because the stored-or-default
// mode is no longer "system", WeatherThemeEngine's day/night mode driving
// (setWeatherResolvedMode's gate below) is inert unless a user explicitly
// picks System in ThemePicker — which remains a real, honored choice.
const DEFAULT_MODE = "light"; // Tier 1 (2026-09-24): light paper is the default; dark stays as the one alternate.

// Landing per-refresh RANDOM accent retired (2026-08-12, direct instruction
// "everywhere it should be same branding colours used"): anonymous/public
// pages now always paint the DEFAULT_ACCENT brand palette, so the funnel
// looks identical on every visit and every page. Explicit ThemePicker choice
// and the per-login session accent keep their precedence unchanged.
// Set the first time a person actually clicks a swatch in ThemePicker (see
// setAccent below) - lets WeatherThemeEngine.js tell "tenant genuinely
// picked Rose" apart from "still just sitting at the indigo default,"
// without needing a seventh accent value or any change to what's stored in
// ACCENT_KEY itself. Once this flag is set, WeatherThemeEngine's weather ->
// accent mapping permanently stops touching accent for this browser -
// exactly the "never override an explicit choice" rule it has to follow.
const ACCENT_EXPLICIT_KEY = "agentic_theme_accent_user_set";

// Per-login accent (sessionStorage, per-tab). Owned exclusively by
// SessionAccentEngine; the no-flash script and mount effect only READ it. This
// is what makes a logged-in visitor's accent stable across refreshes within one
// login while a logged-out landing re-rolls every refresh (the landing random is
// persisted nowhere).
export const SESSION_ACCENT_KEY = "agentic_theme_session_accent";
// The Keycloak sid the session accent above was generated for — lets a
// logout->login inside the same tab (sessionStorage survives) re-roll.
export const SESSION_ACCENT_SID_KEY = "agentic_theme_session_sid";

// Tier 1 (2026-09-24): the eight palettes offered in ThemePicker's swatch
// grid and rolled per visit for anonymous visitors (NO_FLASH_SCRIPT).
// ThemePicker hides the grid only if this list ever shrinks to one entry.
export const ACCENTS = [
  // Light, calm hues only (direct instruction 2026-09-24: "always use very
  // light colours, not red, pink"): coral, rose and amber were removed.
  { key: "indigo", label: "Indigo", swatch: "#6d5bff" },
  { key: "teal", label: "Teal", swatch: "#0d9488" },
  { key: "sky", label: "Sky", swatch: "#0284c7" },
  { key: "emerald", label: "Emerald", swatch: "#16a34a" },
  { key: "violet", label: "Violet", swatch: "#8b3fd9" },
];
// Keys the pre-paint script may roll for an ANONYMOUS visit (see NO_FLASH_SCRIPT).
export const VISIT_ACCENT_KEYS = ACCENTS.map((a) => a.key);

const ThemeContext = createContext(null);

function systemPrefersDark() {
  return typeof window !== "undefined" && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function applyTheme(accent: string, mode: ThemeMode): ResolvedThemeMode {
  const resolved: ResolvedThemeMode = mode === "system" ? (systemPrefersDark() ? "dark" : "light") : mode;
  document.documentElement.setAttribute("data-accent", accent);
  document.documentElement.setAttribute("data-mode", resolved);
  return resolved;
}

// The mode a non-mode write (accent change) must PRESERVE. An explicit
// ThemePicker choice lives in MODE_KEY; otherwise the once-per-session roll
// (or the logged-in default) is only ever painted on <html> by
// NO_FLASH_SCRIPT, never stored in MODE_KEY, so `MODE_KEY || DEFAULT_MODE`
// would silently flip a rolled-dark visitor to light. Read the live DOM
// instead - the same trick setMode already uses for the live accent.
function currentMode(): ThemeMode {
  if (window.localStorage.getItem(MODE_EXPLICIT_KEY)) return (window.localStorage.getItem(MODE_KEY) || DEFAULT_MODE) as ThemeMode;
  return document.documentElement.getAttribute("data-mode") === "dark" ? "dark" : "light";
}

export default function ThemeProvider({ children }: { children: ReactNode }) {
  const [accent, setAccentState] = useState<string>(DEFAULT_ACCENT);
  const [prefs, setPrefsState] = useState<Record<PrefKey, string>>({ ...PREF_DEFAULTS });
  const [mode, setModeState] = useState<ThemeMode>(DEFAULT_MODE);
  // "dark" matches the new default so pre-reconciliation React state never
  // disagrees with what the no-flash script already painted on <html>.
  const [resolvedMode, setResolvedMode] = useState<ResolvedThemeMode>("dark");

  // Runs once on mount - reconciles with whatever the inline no-flash script
  // in layout.js already applied (it reads the same localStorage keys), and
  // subscribes to OS-level theme changes for mode="system".
  useEffect(() => {
    // Mirror the SAME precedence the no-flash inline script already applied
    // (explicit > per-login session > landing per-refresh random) so React
    // never disagrees with the pre-paint DOM.
    // Tier 1 theme engine (2026-09-24): the pre-paint script already decided
    // accent + mode (explicit > logged-in brand default > anonymous per-visit
    // roll). React only reads back what it painted so the two never disagree.
    // Display options: read what NO_FLASH_SCRIPT painted (same storage keys).
    const paintedPrefs: Record<PrefKey, string> = { ...PREF_DEFAULTS };
    (Object.keys(PREF_STORAGE) as PrefKey[]).forEach((k) => {
      const v = window.localStorage.getItem(PREF_STORAGE[k]);
      if (v && PREF_OPTIONS[k].includes(v)) paintedPrefs[k] = v;
      if (paintedPrefs[k] !== PREF_DEFAULTS[k]) document.documentElement.setAttribute("data-" + k, paintedPrefs[k]);
      else document.documentElement.removeAttribute("data-" + k);
    });
    setPrefsState(paintedPrefs);

    const nextAccent = document.documentElement.getAttribute("data-accent") || DEFAULT_ACCENT;
    const source = document.documentElement.getAttribute("data-accent-source") || "brand";

    // Same precedence as NO_FLASH_SCRIPT, and deliberately NON-rolling: the
    // pre-paint script already rolled and painted, so reading the DOM here
    // keeps React in step instead of picking a second, different mode.
    let savedMode: ThemeMode;
    if (window.localStorage.getItem(MODE_EXPLICIT_KEY)) {
      savedMode = (window.localStorage.getItem(MODE_KEY) || DEFAULT_MODE) as ThemeMode;
    } else {
      const painted = document.documentElement.getAttribute("data-mode");
      savedMode = ((painted || DEFAULT_MODE) === "dark" ? "dark" : "light") as ThemeMode;
    }
    setAccentState(nextAccent);
    setModeState(savedMode);
    setResolvedMode(applyTheme(nextAccent, savedMode));
    document.documentElement.setAttribute("data-accent-source", source);

    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      if ((window.localStorage.getItem(MODE_KEY) || DEFAULT_MODE) === "system") {
        // Re-read the LIVE accent, not a stale closure — a prefers-color-scheme
        // flip must keep the current random/session/explicit accent.
        const liveAccent = document.documentElement.getAttribute("data-accent") || DEFAULT_ACCENT;
        setResolvedMode(applyTheme(liveAccent, "system"));
      }
    };
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
     
  }, []);

  const setAccent = useCallback((next: string) => {
    setAccentState(next);
    window.localStorage.setItem(ACCENT_KEY, next);
    window.localStorage.setItem(ACCENT_EXPLICIT_KEY, "1"); // a real click - lock out WeatherThemeEngine's auto accent for good
    document.documentElement.setAttribute("data-accent-source", "explicit"); // top of precedence, everywhere, forever
    setResolvedMode(applyTheme(next, currentMode())); // accent click must never change mode
  }, []);

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next);
    window.localStorage.setItem(MODE_KEY, next);
    // A real click ends the per-visit roll for good - from here on this
    // browser gets exactly what it chose, on every future visit.
    window.localStorage.setItem(MODE_EXPLICIT_KEY, "1");
    // Read the LIVE accent off the DOM, not localStorage ACCENT_KEY: that key is
    // now null for any non-explicit user (session/landing accents are no longer
    // persisted there), so `getItem(ACCENT_KEY) || DEFAULT_ACCENT` would reset a
    // live random/session accent to indigo on any light/dark toggle.
    const liveAccent = document.documentElement.getAttribute("data-accent") || DEFAULT_ACCENT;
    setResolvedMode(applyTheme(liveAccent, next));
  }, []);

  // WeatherThemeEngine.js's two entry points - deliberately separate from
  // setAccent/setMode above rather than reusing them, because those two
  // always persist as a genuine user preference. Both of these instead
  // check/respect that a real preference hasn't already been set, and never
  // write ACCENT_EXPLICIT_KEY or MODE_KEY themselves - a later real click on
  // either control still always wins, and (mode) a page reload still
  // resolves "system" from scratch (OS preference first, corrected to the
  // weather-engine's day/night read a moment later) rather than getting
  // stuck on whatever the last computed value happened to be.
  const setWeatherAccent = useCallback((next: string) => {
    if (!ACCENTS.some((a) => a.key === next)) return; // single-accent build: ignore palettes no longer offered
    if (window.localStorage.getItem(ACCENT_EXPLICIT_KEY) === "1") return; // real user choice already made - never override
    const source = document.documentElement.getAttribute("data-accent-source");
    // Weather is the BASE layer only. An explicit pick, a per-login session
    // accent, the logged-in brand default AND the anonymous per-visit roll
    // ('visit', painted by NO_FLASH_SCRIPT) all outrank it. Because every
    // load paints one of those before this can run, the weather accent is in
    // practice always superseded — that is the requested precedence, NOT a
    // bug. Do NOT "restore" a weather->accent write here to fix it. Weather
    // still drives day/night MODE via setWeatherResolvedMode below
    // (untouched), and WeatherThemeEngine still sets data-weather.
    // Inverted guard: anything other than "no source yet" or an earlier
    // weather write wins, so a newly added source can't slip past this.
    if (source !== null && source !== "weather") return;
    setAccentState(next);
    document.documentElement.setAttribute("data-accent-source", "weather");
    // No longer persisted to ACCENT_KEY: ACCENT_KEY is now the explicit-choice
    // slot only, and persisting a weather accent there would re-stick it across
    // refreshes exactly like the old new-user random did.
    setResolvedMode(applyTheme(next, currentMode())); // never lets a weather write change mode
  }, []);

  const setWeatherResolvedMode = useCallback((resolved: ResolvedThemeMode) => {
    // Only meaningful while mode is still "system" - the moment a tenant
    // explicitly picks Light or Dark in ThemePicker, that's a real
    // preference and this must never touch it again.
    if ((window.localStorage.getItem(MODE_KEY) || DEFAULT_MODE) !== "system") return;
    document.documentElement.setAttribute("data-mode", resolved);
    setResolvedMode(resolved);
  }, []);

  // Called by SessionAccentEngine to sync React state + <html> to whatever
  // per-login accent it wrote to sessionStorage. Does NOT touch sessionStorage
  // (the engine owns that) or ACCENT_KEY (that stays explicit-only).
  const setSessionAccent = useCallback((next: string) => {
    if (!ACCENTS.some((a) => a.key === next)) return; // single-accent build
    if (window.localStorage.getItem(ACCENT_EXPLICIT_KEY) === "1") return; // explicit always wins
    setAccentState(next);
    document.documentElement.setAttribute("data-accent-source", "session");
    // Login moves an anonymous visitor off the per-session roll onto the
    // logged-in default (matches NO_FLASH_SCRIPT's `authed` branch on the
    // next load); an explicit choice still wins. Sync `mode` state too, or a
    // ThemePicker reached by client-side navigation shows the rolled value.
    const m: ThemeMode = window.localStorage.getItem(MODE_EXPLICIT_KEY) ? currentMode() : DEFAULT_MODE;
    setModeState(m);
    setResolvedMode(applyTheme(next, m));
  }, []);

  const setPref = useCallback((key: PrefKey, next: string) => {
    if (!PREF_OPTIONS[key].includes(next)) return;
    window.localStorage.setItem(PREF_STORAGE[key], next);
    if (next !== PREF_DEFAULTS[key]) document.documentElement.setAttribute("data-" + key, next);
    else document.documentElement.removeAttribute("data-" + key);
    setPrefsState((p) => ({ ...p, [key]: next }));
  }, []);

  const value = useMemo(
    () => ({ accent, mode, resolvedMode, setAccent, setMode, setWeatherAccent, setWeatherResolvedMode, setSessionAccent, accents: ACCENTS, prefs, setPref }),
    [accent, mode, resolvedMode, setAccent, setMode, setWeatherAccent, setWeatherResolvedMode, setSessionAccent, prefs, setPref]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return ctx;
}

/** The exact source the no-flash inline script in layout.js runs, kept here as the single source of truth for both. */
export const NO_FLASH_SCRIPT = `
(function() {
  try {
    var accentKey = '${ACCENT_KEY}';
    var modeKey = '${MODE_KEY}';
    var explicitKey = '${ACCENT_EXPLICIT_KEY}';
    var sessionAccentKey = '${SESSION_ACCENT_KEY}';
    var accent, source;
    // Logged-in hint written by AuthProvider once a Keycloak session exists.
    var authed = localStorage.getItem('vd_authed') === '1';
    var visitKeys = ${JSON.stringify(VISIT_ACCENT_KEYS)};
    if (localStorage.getItem(explicitKey)) {
      // (1) explicit ThemePicker choice — highest precedence, forever.
      accent = localStorage.getItem(accentKey) || '${DEFAULT_ACCENT}';
      source = 'explicit';
    } else if (authed) {
      // (2) logged in: stable brand default (SessionAccentEngine keeps it).
      var sess = null;
      try { sess = sessionStorage.getItem(sessionAccentKey); } catch (e) {}
      accent = sess || '${DEFAULT_ACCENT}';
      source = sess ? 'session' : 'brand';
    } else {
      // (3) anonymous: a NEW accent on every refresh (direct instruction
      // 2026-09-24: "everytime when consumer refreshes, it should show a new
      // colour theme"). Never persisted, so the next load rolls again.
      accent = visitKeys[Math.floor(Math.random() * visitKeys.length)];
      source = 'visit';
    }
    // MODE. Explicit choice wins forever. Logged in: the light default.
    // Anonymous: rolled ONCE per browser session and reused on every page
    // and refresh of that session ("dark or light once per session, till he
    // logs in"). Rolled here, before first paint, to avoid a theme flash.
    var modeExplicitKey = '${MODE_EXPLICIT_KEY}';
    var sessionModeKey = '${SESSION_MODE_KEY}';
    var mode;
    if (localStorage.getItem(modeExplicitKey)) {
      mode = localStorage.getItem(modeKey) || '${DEFAULT_MODE}';
    } else if (authed) {
      mode = '${DEFAULT_MODE}';
    } else {
      var sessMode = null;
      try { sessMode = sessionStorage.getItem(sessionModeKey); } catch (e) {}
      if (sessMode !== 'light' && sessMode !== 'dark') {
        sessMode = Math.random() < 0.5 ? 'light' : 'dark';
        try { sessionStorage.setItem(sessionModeKey, sessMode); } catch (e) {}
      }
      mode = sessMode;
    }
    var resolved = mode === 'system' ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : mode;
    var el = document.documentElement;
    el.setAttribute('data-accent', accent);
    el.setAttribute('data-mode', resolved);
    el.setAttribute('data-accent-source', source);
    // Display options (mesh background / reduced motion / high contrast).
    var prefKeys = ${JSON.stringify(PREF_STORAGE)};
    var prefDefaults = ${JSON.stringify(PREF_DEFAULTS)};
    for (var k in prefKeys) {
      var v = localStorage.getItem(prefKeys[k]);
      if (v && v !== prefDefaults[k]) el.setAttribute('data-' + k, v);
    }
  } catch (e) {}
})();
`;
