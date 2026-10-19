"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { auth } from "../../lib/auth";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";

// Keycloak identity brokering, not custom OAuth code in this app - each
// button just asks Keycloak's own login page to jump straight to that
// broker via `idpHint` (see auth.js's login(opts), which forwards whatever
// object it's given verbatim to the Keycloak JS adapter's own login()).
// Only actually works once the platform operator has added that provider
// in the Keycloak admin console (see README.md's "Optional - social
// sign-in via Keycloak identity brokering" section).
//
// Infra audit (2026-07-27): these used to render unconditionally, since this
// app had no way to ask Keycloak at build time which providers were
// configured - clicking one that wasn't set up yet 404s on Keycloak's side
// rather than this app's. Fixed by asking at RUNTIME instead: api.getSocialProviders()
// hits GET /api/auth/social-providers (SocialAuthConfigController, core/admin),
// which reflects the realm's live identity-provider enabled state, and only
// the aliases it returns get a button. Defaults to showing nothing until that
// call resolves (or if it fails) rather than flashing all three and then
// removing some - see the loading-state render below.
//
// redirectUri is pinned to the app root, same reason as SignInPanel.js's
// handleContinue: these buttons are only ever rendered on /signup, and
// AuthProvider.js never runs its Keycloak lifecycle there - leaving
// redirectUri to default to the current URL would send a successful login
// straight back to a page that ignores the auth code it carries.
//
// Real inline SVG brand marks (not a generic icon-library stand-in) -
// Google's own "Sign in with Google" branding guidelines require the
// standard multicolor "G" on a light chip even inside a dark button, which
// GoogleIcon's wrapping <span> (.su-social-icon-chip) provides; GitHub's
// mark and Microsoft's four-square mark render directly in their usual
// single/multi-color form, matching each platform's own button convention.
function GoogleIcon() {
  return (
    <svg viewBox="0 0 18 18" width="14" height="14" aria-hidden="true" focusable="false">
      <path fill="#4285F4" d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.259h2.908c1.702-1.567 2.684-3.874 2.684-6.617z" />
      <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9 18z" />
      <path fill="#FBBC05" d="M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.348 6.173 0 7.548 0 9s.348 2.827.957 4.042l3.007-2.332z" />
      <path fill="#EA4335" d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z" />
    </svg>
  );
}

function GitHubIcon() {
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor" aria-hidden="true" focusable="false">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

function MicrosoftIcon() {
  return (
    <svg viewBox="0 0 21 21" width="14" height="14" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="9" height="9" fill="#F25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7FBA00" />
      <rect x="1" y="11" width="9" height="9" fill="#00A4EF" />
      <rect x="11" y="11" width="9" height="9" fill="#FFB900" />
    </svg>
  );
}

const PROVIDERS = [
  { alias: "github", label: "GitHub", Icon: GitHubIcon, chip: false },
  { alias: "google", label: "Google", Icon: GoogleIcon, chip: true },
  { alias: "microsoft", label: "Microsoft", Icon: MicrosoftIcon, chip: false },
];

// Rebuilt on the shadcn Button primitive (outline variant) + Tailwind, so it
// matches the ShadCN signup rebuild. Same Keycloak identity-brokering behavior
// (idpHint → Keycloak) and same pinned redirectUri — presentation only changed.
export default function SocialLoginRow({ label }: { label?: any }) {
  const t = useTranslations("signup");
  const resolvedLabel = label ?? t("socialConnectLabel");

  // Starts empty (not "all three") so there is never a flash of buttons that
  // then disappear once the real, enabled-only answer comes back - see
  // getSocialProviders()'s own comment in lib/api.ts for why a failed call
  // resolves to `{ providers: [] }` instead of throwing.
  const [enabledAliases, setEnabledAliases] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    void api.getSocialProviders().then((res) => {
      if (!cancelled) setEnabledAliases(res?.providers ?? []);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const providers = PROVIDERS.filter((p) => enabledAliases.includes(p.alias));
  // Nothing configured (or the lookup hasn't resolved yet) - render nothing
  // rather than an "or continue with" divider sitting above zero buttons.
  if (providers.length === 0) return null;

  return (
    <div className="space-y-3">
      <div
        role="separator"
        aria-hidden="true"
        className="flex items-center gap-3 text-xs uppercase tracking-wider text-muted-foreground"
      >
        <span className="h-px flex-1 bg-border" />
        {resolvedLabel}
        <span className="h-px flex-1 bg-border" />
      </div>
      <div className="grid gap-2.5" style={{ gridTemplateColumns: `repeat(${providers.length}, minmax(0, 1fr))` }}>
        {providers.map(({ alias, label: providerLabel, Icon, chip }) => (
          <Button
            key={alias}
            variant="outline"
            size="sm"
            aria-label={t("socialContinueWith", { provider: providerLabel })}
            onClick={() => auth.login({ idpHint: alias, redirectUri: `${window.location.origin}/` })}
          >
            <span className={chip ? "grid size-4 place-items-center rounded-sm bg-white" : "inline-flex"} aria-hidden="true">
              <Icon />
            </span>
            <span className="truncate">{providerLabel}</span>
          </Button>
        ))}
      </div>
    </div>
  );
}
