"use client";

import { useEffect, useState } from "react";
import { visibleInterval } from "../lib/visibleInterval";
import { Eye, LogOut } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { auth } from "../lib/auth";
import { useViewAs } from "../lib/useViewAs";
import {
  exitViewAs,
  formatCountdown,
  msUntil,
  parseGrantFromHash,
  stashPendingGrant,
  updateViewAsSession,
} from "../lib/viewAs";
import { checkViewAsSession } from "../lib/api/viewAsSession";

// Persistent red bar for a read-only view-as tab (viewAs.ts). Mounted once in
// the root layout, above AuthProvider, for two reasons:
//  1. It lifts `#grant=` off /view-as on first paint - before a Keycloak
//     sign-in redirect could drop the fragment - into tab-scoped storage and
//     strips it from the address bar (history.replaceState). /view-as then
//     validates it once the admin's own session is up.
//  2. It shows on every page while the session is active, with a live
//     countdown, and ends it in this tab on expiry or when core says the grant
//     is no longer valid (401/410 from /api/view-as/session).
// Exit only clears this tab: ending the server-side session early is a
// platform-admin action in web-admin (/impersonation).

const REVALIDATE_MS = 60_000;

function endedUrl(reason: "expired" | "exited" | "invalid"): string {
  return `/view-as?ended=${reason}`;
}

export default function ViewAsBanner() {
  const t = useTranslations("viewAs");
  const { state } = useViewAs();
  const [now, setNow] = useState(() => Date.now());

  // (1) Fragment capture - runs on every page load, acts only on /view-as.
  useEffect(() => {
    if (typeof window === "undefined" || window.location.pathname !== "/view-as") return;
    const grant = parseGrantFromHash(window.location.hash);
    if (window.location.hash) {
      try {
        window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
      } catch {
        // ignore - the grant is still never sent anywhere from the hash
      }
    }
    if (grant) {
      stashPendingGrant(grant);
      // /view-as listens for this to pick the stashed grant up.
      window.dispatchEvent(new Event("vd:view-as-pending"));
    }
  }, []);

  const active = state != null;
  const expiresAt = state?.session?.expiresAt ?? null;

  // Countdown tick + auto-exit on expiry.
  useEffect(() => {
    if (!active) return undefined;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [active]);

  const left = msUntil(expiresAt, now);
  useEffect(() => {
    if (active && left != null && left <= 0) {
      exitViewAs();
      window.location.assign(endedUrl("expired"));
    }
  }, [active, left]);

  // Server revalidation: picks up an early end from web-admin.
  const grant = state?.grant ?? null;
  useEffect(() => {
    if (!grant) return undefined;
    let cancelled = false;
    async function check() {
      if (cancelled || !auth.isAuthenticated()) return;
      const result = await checkViewAsSession(grant as string);
      if (cancelled) return;
      if (result.kind === "invalid") {
        exitViewAs();
        window.location.assign(endedUrl("invalid"));
      } else if (result.kind === "ok") {
        updateViewAsSession(result.session);
      }
      // "error": keep going; a blip must not end the session.
    }
    void check();
    const stop = visibleInterval((): void => void check(), REVALIDATE_MS);
    return () => {
      cancelled = true;
      stop();
    };
  }, [grant]);

  if (!state) return null;

  const email = state.session?.userEmail || t("unknownUser");
  const tenant = state.session?.tenantName || t("unknownTenant");

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="view-as-banner"
      className="sticky top-0 z-[70] flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-sm font-medium"
      style={{ backgroundColor: "var(--destructive)", color: "var(--destructive-foreground)" }}
    >
      <Eye className="size-4 shrink-0" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        {t("bannerViewing", { email, tenant })} · {t("bannerReadOnly")} ·{" "}
        <span className="font-mono tabular-nums">{t("bannerExpiresIn", { time: formatCountdown(left) })}</span>
      </span>
      <button
        type="button"
        onClick={() => {
          exitViewAs();
          window.location.assign(endedUrl("exited"));
        }}
        className="inline-flex shrink-0 items-center gap-1.5 rounded-md border px-2.5 py-1 text-xs font-semibold"
        style={{ borderColor: "currentColor", backgroundColor: "transparent", color: "inherit" }}
      >
        <LogOut className="size-3.5" aria-hidden="true" />
        {t("exit")}
      </button>
    </div>
  );
}
