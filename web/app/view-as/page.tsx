"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Eye, ShieldAlert } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "../components/ui/button";
import {
  activateViewAs,
  clearPendingGrant,
  exitViewAs,
  parseGrantFromHash,
  peekPendingGrant,
  stashPendingGrant,
} from "../lib/viewAs";
import { checkViewAsSession } from "../lib/api/viewAsSession";

// Landing route for a read-only view-as link opened from web-admin:
//   https://app.<domain>/view-as#grant=<grant>
// The grant is in the fragment, never the query string. ViewAsBanner (root
// layout) normally lifts it into tab-scoped storage and strips the hash before
// this page even renders (the Keycloak sign-in redirect would otherwise lose
// it); this page also reads the hash itself in case it is still there. It
// validates the grant against GET /api/view-as/session with the admin's own
// token, then activates view-as for THIS tab only and goes to the app home.
// `?ended=expired|exited|invalid` is where the banner sends the tab when a
// session ends.

type Phase =
  | { kind: "checking" }
  | { kind: "none" }
  | { kind: "ended"; reason: string }
  | { kind: "invalid" }
  | { kind: "error"; grant: string };

export default function ViewAsPage() {
  const t = useTranslations("viewAs");
  const router = useRouter();
  const [phase, setPhase] = useState<Phase>({ kind: "checking" });

  const validate = useCallback(
    async (grant: string) => {
      setPhase({ kind: "checking" });
      const result = await checkViewAsSession(grant);
      if (result.kind === "ok") {
        clearPendingGrant();
        activateViewAs(grant, result.session);
        router.replace("/home");
      } else if (result.kind === "invalid") {
        clearPendingGrant();
        setPhase({ kind: "invalid" });
      } else {
        setPhase({ kind: "error", grant });
      }
    },
    [router]
  );

  useEffect(() => {
    const fromHash = parseGrantFromHash(window.location.hash);
    if (window.location.hash) {
      try {
        window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
      } catch {
        // ignore
      }
    }
    if (fromHash) stashPendingGrant(fromHash);

    function start() {
      const grant = fromHash || peekPendingGrant();
      if (grant) {
        void validate(grant);
        return true;
      }
      return false;
    }

    if (start()) return undefined;
    const ended = new URLSearchParams(window.location.search).get("ended");
    if (ended) {
      // Arriving here after an end: make sure nothing of the session lingers in this tab.
      exitViewAs();
      setPhase({ kind: "ended", reason: ended });
    } else {
      setPhase({ kind: "none" });
    }
    // The banner may stash the grant a tick after this effect ran.
    const onPending = (): void => void start();
    window.addEventListener("vd:view-as-pending", onPending);
    return () => window.removeEventListener("vd:view-as-pending", onPending);
  }, [validate]);

  let title = t("checkingTitle");
  let body: string | null = t("checkingBody");
  if (phase.kind === "none") {
    title = t("noneTitle");
    body = t("noneBody");
  } else if (phase.kind === "ended") {
    title = t("endedTitle");
    body =
      phase.reason === "expired"
        ? t("endedExpired")
        : phase.reason === "invalid"
          ? t("endedInvalid")
          : t("endedExited");
  } else if (phase.kind === "invalid") {
    title = t("invalidTitle");
    body = t("invalidBody");
  } else if (phase.kind === "error") {
    title = t("errorTitle");
    body = t("errorBody");
  }

  const Icon = phase.kind === "invalid" || phase.kind === "error" ? ShieldAlert : Eye;

  return (
    <main className="flex min-h-[70vh] items-center justify-center p-4">
      <div
        className="w-full max-w-md rounded-xl border border-border bg-card p-6 text-center shadow-sm"
        role="status"
        aria-live="polite"
      >
        <Icon className="mx-auto size-8 text-primary" aria-hidden="true" />
        <h1 className="mt-3 text-lg font-semibold text-foreground">{title}</h1>
        {body && <p className="mt-2 text-sm text-muted-foreground">{body}</p>}
        <div className="mt-5 flex flex-wrap justify-center gap-2">
          {phase.kind === "error" && (
            <Button size="sm" onClick={() => void validate(phase.grant)}>
              {t("retry")}
            </Button>
          )}
          {phase.kind !== "checking" && (
            <Button size="sm" variant="outline" asChild>
              <Link href="/home">{t("backToConsole")}</Link>
            </Button>
          )}
        </div>
      </div>
    </main>
  );
}
