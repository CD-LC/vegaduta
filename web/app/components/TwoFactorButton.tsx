"use client";

import { useEffect, useState } from "react";
import { ExternalLink, ShieldCheck } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { auth } from "../lib/auth";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";

// Topbar entry point for two-factor authentication (2026-10-09, direct
// instruction: "inform consumers to set the 2 factor authentication from top
// header menu"). 2FA itself is Keycloak's - the Account Console's "Signing in"
// page owns enrolment - so this only explains it and deep-links there. The
// dot is a per-user nudge that disappears once they open the setup link or
// choose "Later"; the button stays so it can always be found again.

const NUDGE_KEY = "vd_2fa_nudge_dismissed";

function nudgeKey(): string {
  let id: string | null = null;
  try {
    id = auth.getUserId();
  } catch {
    id = null;
  }
  return id ? `${NUDGE_KEY}:${id}` : NUDGE_KEY;
}

/** The Account Console URL keycloak-js builds (".../account?referrer=..."),
 *  pointed at its Signing in page, where "Set up authenticator application" is. */
export function signingInUrl(accountUrl: string | null): string | null {
  if (!accountUrl) return null;
  try {
    const url = new URL(accountUrl);
    url.pathname = url.pathname.replace(/\/account\/?$/, "/account/account-security/signing-in");
    return url.toString();
  } catch {
    return null;
  }
}

export default function TwoFactorButton({ accountUrl }: { accountUrl: string | null }) {
  const t = useTranslations("nav");
  const [open, setOpen] = useState(false);
  const [nudge, setNudge] = useState(false);
  const href = signingInUrl(accountUrl);

  useEffect(() => {
    try {
      setNudge(!window.localStorage.getItem(nudgeKey()));
    } catch {
      setNudge(false);
    }
  }, []);

  const dismiss = () => {
    setNudge(false);
    try {
      window.localStorage.setItem(nudgeKey(), "1");
    } catch {
      /* storage unavailable - the dot simply returns next visit */
    }
  };

  if (!href) return null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          title={t("twoFactorAriaLabel")}
          aria-label={t("twoFactorAriaLabel")}
          data-tour="security"
          // NOT topbar-collapsible: the crowded-header compact mode (console.css,
          // data-compact="2") hides those, and the way to 2FA must never disappear.
          className="hud-tour-reopen relative"
          style={{ minWidth: "2.75rem", minHeight: "2.75rem" }}
        >
          <ShieldCheck className="size-4" aria-hidden="true" />
          {nudge && (
            <span
              className="absolute right-2 top-2 size-2 rounded-full bg-primary"
              aria-hidden="true"
              data-testid="two-factor-nudge"
            />
          )}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-[20rem] p-3" data-testid="two-factor-panel">
        <p className="m-0 text-sm font-semibold text-foreground">{t("twoFactorTitle")}</p>
        <p className="mt-1.5 text-xs leading-snug text-muted-foreground">{t("twoFactorBody")}</p>
        <div className="mt-3 flex items-center gap-2">
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            onClick={() => {
              dismiss();
              setOpen(false);
            }}
            className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground no-underline hover:opacity-90"
          >
            {t("twoFactorSetUp")}
            <ExternalLink className="size-3" aria-hidden="true" />
          </a>
          <button
            type="button"
            onClick={() => {
              dismiss();
              setOpen(false);
            }}
            className="rounded-md border-0 bg-transparent px-2 py-1.5 text-xs font-medium text-muted-foreground shadow-none hover:text-foreground"
          >
            {t("twoFactorLater")}
          </button>
        </div>
      </PopoverContent>
    </Popover>
  );
}
