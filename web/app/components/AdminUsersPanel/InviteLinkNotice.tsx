"use client";

import { useState } from "react";
import { Copy, Check, X } from "lucide-react";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";

// The one-time invite link an install with no mail server hands back (core: local auth,
// UserAdminController#create / #reissueInviteLink). The admin passes it on by chat or in person.
// Core sends just the path when app.web.public-url is unset, so the console's own origin goes in
// front - the same address the admin is using right now.
export function absoluteInviteLink(link: string, origin: string): string {
  return link.startsWith("/") ? origin + link : link;
}

/** True when the console is only reachable from this machine, so a teammate elsewhere cannot open a link to it. */
export function isLoopbackHost(hostname: string): boolean {
  const h = hostname.toLowerCase();
  return h === "localhost" || h.endsWith(".localhost") || h === "[::1]" || h === "::1" || h.startsWith("127.");
}

interface Props {
  email: string;
  link: string;
  onDismiss: () => void;
}

export default function InviteLinkNotice({ email, link, onDismiss }: Props) {
  const t = useTranslations("adminUsersPanel");
  const [copied, setCopied] = useState(false);
  const url = absoluteInviteLink(link, window.location.origin);
  const loopback = isLoopbackHost(new URL(url, window.location.origin).hostname);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard blocked (insecure origin, permissions): the link stays selectable below.
    }
  }

  return (
    <div className="ax-kb-success" role="status" data-testid="invite-link-notice">
      <div className="flex items-start justify-between gap-2">
        <strong>{t("inviteLinkTitle", { email })}</strong>
        <button type="button" aria-label={t("inviteLinkDismiss")} onClick={onDismiss}>
          <X aria-hidden="true" size={14} />
        </button>
      </div>
      <p className="mt-1 text-[0.8rem]">{t("inviteLinkHint")}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <code className="min-w-0 flex-1 break-all rounded border border-border bg-secondary/40 px-2 py-1 font-mono text-[0.75rem] select-all">
          {url}
        </code>
        <Button type="button" variant="outline" size="sm" onClick={copy}>
          {copied ? <Check aria-hidden="true" className="size-3" /> : <Copy aria-hidden="true" className="size-3" />}
          {copied ? t("inviteLinkCopied") : t("inviteLinkCopy")}
        </Button>
      </div>
      {loopback && <p className="mt-2 text-[0.8rem]">{t("inviteLinkLoopbackWarning")}</p>}
    </div>
  );
}
