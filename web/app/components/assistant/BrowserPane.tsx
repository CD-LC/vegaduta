"use client";

// The Assistant's integrated browser: the agent's own headless browser, in a pane beside the chat. The person can
// sign in to a site themselves (live view with click / key / scroll relay, via the existing BrowserTakeoverPanel)
// and hand control back, so the agent carries on logged in. It opens by itself only when the agent asks for help
// (captcha, 2FA, login) - see AssistantPane - otherwise when the person presses Browser.

import Link from "next/link";
import { Globe, Lock, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { docPageHref } from "../../lib/siteLinks";
import type { BrowserProfile } from "../../lib/api/browserAutomation";
import EmptyState from "../EmptyState";
import { isPersonalInstall, tierCommand } from "../../lib/personalInstall";
import { Button } from "../ui/button";
import BrowserTakeoverPanel from "../agents/BrowserTakeoverPanel";
import type { BrowserAvailability } from "./browserAvailability";

export default function BrowserPane({
  availability,
  agentId,
  sessionId,
  profile,
  profilesOn,
  onHandedBack,
  onClose,
}: {
  availability: BrowserAvailability;
  agentId: string | undefined;
  sessionId: string | null | undefined;
  profile: BrowserProfile | null;
  /** Saved logins are switched on in core; without them there is no profile line to show. */
  profilesOn: boolean;
  /** Receives the ready-made "I'm done" line after a hand-back, so the chat can offer to send it. */
  onHandedBack?: (message: string) => void;
  onClose: () => void;
}) {
  const t = useTranslations("assistantBrowser");
  const tu = useTranslations("featureUnavailable");

  return (
    <aside
      className="absolute inset-y-0 right-0 z-20 flex w-full flex-col border-l border-border bg-background shadow-lg lg:static lg:z-auto lg:w-[44%] lg:min-w-[22rem] lg:shadow-none"
      data-testid="browser-pane"
      aria-label={t("title")}
    >
      <header className="flex items-center gap-1.5 border-b border-border px-3 py-2">
        <Globe size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
        <h3 className="text-sm font-semibold text-foreground">{t("title")}</h3>
        <span className="flex-1" />
        <Button type="button" size="icon" variant="ghost" aria-label={t("close")} title={t("close")} onClick={onClose}>
          <X size={14} aria-hidden="true" />
        </Button>
      </header>
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        {availability === "unavailable" ? (
          <div data-testid="browser-unavailable" role="status">
            <EmptyState
              icon={Lock}
              title={tu("titlePersonal", { feature: t("title") })}
              hint={
                <>
                  {t(isPersonalInstall() ? "unavailableReasonPersonal" : "unavailableReason", {
                    cmd: tierCommand("--browser"),
                  })}
                  <br />
                  {tu("guidance")}
                </>
              }
              action={
                <a className="btn btn-ghost mt-2" href={docPageHref("installation")} target="_blank" rel="noopener noreferrer">
                  {tu("docsLink")}
                </a>
              }
            />
          </div>
        ) : (
          <>
            <section>
              <h4 className="text-sm font-medium text-foreground">{t("signInTitle")}</h4>
              <p className="mt-0.5 text-xs text-muted-foreground">{t("signInHint")}</p>
            </section>
            {sessionId ? (
              <BrowserTakeoverPanel
                agentId={agentId}
                sessionId={sessionId}
                usedBrowser
                pauseLabel={t("signInButton")}
                onHandedBack={onHandedBack}
                className="flex w-full flex-col gap-2"
              />
            ) : (
              <p className="text-xs text-muted-foreground" data-testid="browser-no-session">
                {t("noSession")}
              </p>
            )}
            {profilesOn && <ProfileLine profile={profile} agentId={agentId} />}
          </>
        )}
      </div>
    </aside>
  );
}

function ProfileLine({ profile, agentId }: { profile: BrowserProfile | null; agentId: string | undefined }) {
  const t = useTranslations("assistantBrowser");
  return (
    <section className="rounded-lg border border-border px-3 py-2 text-xs" data-testid="browser-profile">
      <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("profileTitle")}</h4>
      {profile ? (
        <>
          <p className="mt-1 break-words text-sm font-medium text-foreground">{profile.name}</p>
          <p className="mt-0.5 break-words text-muted-foreground">{profile.allowedDomains.join(", ")}</p>
          <p className="mt-0.5 text-muted-foreground">{profile.hasState && !profile.expired ? t("profileSaved") : t("profileEmpty")}</p>
        </>
      ) : (
        <p className="mt-1 text-muted-foreground">{t("profileNone")}</p>
      )}
      {agentId && (
        <Link href={`/agents?agent=${encodeURIComponent(agentId)}`} className="mt-1.5 inline-block text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
          {t("profileManage")}
        </Link>
      )}
    </section>
  );
}
