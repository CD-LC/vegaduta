"use client";

// Rail footer: avatar, manage-profile link (Keycloak) or sign-in methods link (local sign-in), sign-out.
// Lifted out of AppShell.tsx.

import Link from "next/link";
import { Power, ExternalLink } from "lucide-react";
import { useAuth } from "../AuthProvider";
import { useTranslations } from "../../lib/i18n";
import { useAppChrome } from "../AppChromeProvider";
import { isLocalAuth } from "../../lib/authMode";
import { initialsOf } from "./initialsOf";
import { railClasses } from "./railClasses";

// The signed-in person's passkeys, PIN devices and recovery codes (components/localauth/AccountSecurity).
// Local sign-in has no Keycloak account console, so this is the one in-product way to reach them.
const ACCOUNT_SECURITY_HREF = "/login?security=1";

export default function RailFooter({
  profileHref,
  hubMode,
  collapsibleRail,
  onOpenAvatar,
}: {
  profileHref: string | null;
  hubMode: boolean;
  collapsibleRail: boolean;
  onOpenAvatar: () => void;
}) {
  const tHC = useTranslations("hcComponentsShellRailFooter");
  const tNav = useTranslations("nav");
  const { username, firstName, logout } = useAuth();
  const { avatarBlobUrl } = useAppChrome();
  const { railLabelCls, footerRowCollapse, footerIdentityCollapse, footerNameCollapse, footerSignoutCollapse } =
    railClasses(collapsibleRail, hubMode);
  return (
    <div className="shrink-0 border-t border-[var(--surface-border)] p-3" data-tour="account">
      <div className={`flex items-center gap-1.5 ${footerRowCollapse}`}>
        {/* Avatar - clickable in its own right (opens the upload/crop
              modal below), deliberately pulled OUT of the "manage profile"
              link so clicking the picture itself never navigates away to
              Keycloak's Account Console. Shows the uploaded picture once
              one exists; falls back to initials exactly as before. */}
        <button
          type="button"
          onClick={() => onOpenAvatar()}
          title={tHC("s1")}
          aria-label={tHC("s1")}
          className="group/avatar flex size-8 shrink-0 items-center justify-center overflow-hidden rounded-full border-0 bg-primary/15 p-0 text-xs font-semibold text-primary shadow-none transition-opacity hover:opacity-80"
        >
          {avatarBlobUrl ? (
            <img src={avatarBlobUrl} alt="" className="size-full object-cover" />
          ) : (
            <span aria-hidden="true">{initialsOf(firstName || username)}</span>
          )}
        </button>
        {profileHref ? (
          <a
            href={profileHref}
            target="_blank"
            rel="noopener noreferrer"
            title={tNav("manageProfile")}
            /* hover uses --surface-raised, one rung BELOW the
                 --surface-hover the active nav row now owns, so a hovered
                 control can never be mistaken for the current page. */
            className={`group flex min-w-0 flex-1 items-center rounded-lg px-2 py-1.5 transition-colors hover:bg-[var(--surface-raised)] ${footerIdentityCollapse}`}
          >
            <div className={`min-w-0 flex-1 leading-tight ${railLabelCls} ${footerNameCollapse}`}>
              <div className="truncate text-sm font-medium" title={username || undefined}>
                {firstName || username || "—"}
              </div>
              <div className="flex items-center gap-1 text-xs text-muted-foreground transition-colors group-hover:text-foreground">
                {tNav("manageProfile")}
                <ExternalLink className="size-3 opacity-70" aria-hidden="true" />
              </div>
            </div>
          </a>
        ) : isLocalAuth() ? (
          <Link
            href={ACCOUNT_SECURITY_HREF}
            title={tNav("accountSecurity")}
            data-testid="rail-account-security"
            className={`group flex min-w-0 flex-1 items-center rounded-lg px-2 py-1.5 transition-colors hover:bg-[var(--surface-raised)] ${footerIdentityCollapse}`}
          >
            <div className={`min-w-0 flex-1 leading-tight ${railLabelCls} ${footerNameCollapse}`}>
              <div className="truncate text-sm font-medium" title={username || undefined}>
                {firstName || username || "—"}
              </div>
              <div className="text-xs text-muted-foreground transition-colors group-hover:text-foreground">
                {tNav("accountSecurity")}
              </div>
            </div>
          </Link>
        ) : (
          <div className={`flex min-w-0 flex-1 items-center px-2 py-1.5 ${footerIdentityCollapse}`}>
            <div
              className={`min-w-0 flex-1 truncate text-sm font-medium leading-tight ${railLabelCls} ${footerNameCollapse}`}
              title={username || undefined}
            >
              {firstName || username || "—"}
            </div>
          </div>
        )}
        <button
          type="button"
          onClick={logout}
          title={tNav("signOut")}
          aria-label={tNav("signOut")}
          /* border-0 bg-transparent p-0 shadow-none: neutralize the global
               `button {}` base rule, whose forced px-3.5 padding would clip
               this size-8 icon button's Power glyph to near-nothing. */
          className={`flex size-8 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none transition-colors hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)] ${footerSignoutCollapse}`}
        >
          <Power className="size-4" aria-hidden="true" />
        </button>
      </div>
      {hubMode && (
        // Hub mode never expands the rail, so the in-row sign-out above
        // stays lg:hidden forever - this compact second row is the only
        // desktop sign-out affordance in that mode.
        <button
          type="button"
          onClick={logout}
          title={tNav("signOut")}
          aria-label={tNav("signOut")}
          className="mx-auto mt-1 hidden size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none transition-colors hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)] lg:flex"
        >
          <Power className="size-4" aria-hidden="true" />
        </button>
      )}
    </div>
  );
}
