"use client";

import { useEffect } from "react";
import { useTheme, SESSION_ACCENT_KEY, SESSION_ACCENT_SID_KEY, ACCENTS } from "./ThemeProvider";
import { auth } from "../lib/auth";

// Kept in sync with ThemeProvider's own key (a value, not worth exporting a 4th
// symbol just for this). Explicit ThemePicker picks outrank the session accent.
const ACCENT_EXPLICIT_KEY = "agentic_theme_accent_user_set";

// Tier 1 theme engine (2026-09-24): once logged in the accent is STABLE — the
// per-refresh roll is an anonymous-visitor thing only. The brand default is
// the first ACCENTS entry (indigo).
function rollAccent(): string {
  return ACCENTS[0].key;
}

// Chooses ONE random accent per Keycloak login session and keeps it stable
// across refreshes (sessionStorage), re-rolling on a genuinely new login (sid
// change). Explicit picks always win, so it no-ops when ACCENT_EXPLICIT_KEY is
// set.
//
// Mounted under AppChromeProvider (see layout.tsx). AppChromeProvider renders
// for authenticated console routes AND — because AuthProvider passes children
// straight through on PUBLIC_ROUTES — for logged-out visitors on public pages
// like /explore or /docs. So this component canNOT assume its mere mounting
// means "logged in"; it gates on auth.isAuthenticated() and does nothing for an
// anonymous visitor, leaving the landing per-refresh random in place. It lives
// under ThemeProvider (a parent of AuthProvider), so useTheme() resolves.
export default function SessionAccentEngine(): null {
  const { setSessionAccent } = useTheme();
  useEffect(() => {
    if (typeof window === "undefined") return;
    // Only a genuinely authenticated session gets a stable per-login accent.
    // An anonymous public-route visitor must keep the per-refresh random, which
    // is persisted nowhere and re-rolls on every load.
    if (!auth.isAuthenticated()) return;
    if (window.localStorage.getItem(ACCENT_EXPLICIT_KEY) === "1") return; // explicit wins

    let accent: string | null = null;
    let storedSid: string | null = null;
    try {
      accent = window.sessionStorage.getItem(SESSION_ACCENT_KEY);
      storedSid = window.sessionStorage.getItem(SESSION_ACCENT_SID_KEY);
    } catch {
      return; // sessionStorage blocked — leave the landing random in place
    }

    const sid = auth.getSessionId(); // may be null if the realm omits sid/session_state
    const isNewLogin = !!sid && !!storedSid && sid !== storedSid;

    if (!accent || isNewLogin) {
      accent = rollAccent();
      try {
        window.sessionStorage.setItem(SESSION_ACCENT_KEY, accent);
        if (sid) window.sessionStorage.setItem(SESSION_ACCENT_SID_KEY, sid);
      } catch {
        /* best-effort — a blocked write just means it re-rolls next mount */
      }
    } else if (sid && !storedSid) {
      // Accent already chosen but no sid was recorded then — backfill it so a
      // genuine later login (different sid) is detectable without re-rolling now.
      try {
        window.sessionStorage.setItem(SESSION_ACCENT_SID_KEY, sid);
      } catch {
        /* best-effort */
      }
    }

    setSessionAccent(accent);
  }, [setSessionAccent]);
  return null;
}
