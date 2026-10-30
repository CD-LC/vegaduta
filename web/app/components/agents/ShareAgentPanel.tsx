"use client";

import { useCallback, useEffect, useState } from "react";
import { Share2, Copy, Check, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../ui/button";
import { toast } from "../../lib/toastBus";
import { errorMessage } from "../../lib/errors";
import { ShareEndNotice, ShareLinkMeta, useShareEndReason } from "../channels/ShareLinkMeta";
import { QrCode } from "../ui/qr-code";
import { useTranslations } from "../../lib/i18n";

/**
 * Owner-side control for "even registered consumers should be able to share
 * their agents to external world" - mint/inspect/revoke a public link for
 * THIS agent. Sibling of CallPanel in the same playground column; unlike
 * CallPanel this needs no live connection, just a status fetch on mount and
 * three actions (create/copy/revoke) against AgentShareOwnerController.
 */
// AgentShareOwnerController.ShareStatusResponse - the one wire shape all
// three endpoints here (GET status / POST create / DELETE revoke) speak.
// shareId/shareUrl are null whenever active is false (the controller's own
// inactive() factory).
interface AgentShareStatus {
  active: boolean;
  shareId: string | null;
  shareUrl: string | null;
  viewerCount: number;
  reportCount: number;
  expiresAt?: string | null;
}

// Only the id is read here - a structural prop type keeps this panel usable
// from any surface that has *some* agent-shaped object (AgentPlayground
// passes its full agent), without re-declaring the whole AgentResponse shape.
export default function ShareAgentPanel({ agent }: { agent: { id: string } | null }) {
    const tHC = useTranslations("hcComponentsAgentsShareAgentPanel");
  const [status, setStatus] = useState<AgentShareStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const refetch = useCallback(() => {
    if (!agent?.id) return;
    api
      .getAgentShareStatus(agent?.id)
      .then((res) => setStatus(res))
      .catch(() => {
        // Keep the last known status; a failed background re-read is not evidence of a change.
      });
  }, [agent?.id]);
  const { reason, markOwnerRevoked } = useShareEndReason(status, refetch);

  useEffect(() => {
    if (!agent?.id) return undefined;
    let cancelled = false;
    setLoading(true);
    api
      .getAgentShareStatus(agent.id)
      .then((res) => {
        if (!cancelled) setStatus(res);
      })
      .catch(() => {
        if (!cancelled) setStatus(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [agent?.id]);

  async function createShare() {
    if (!agent || busy) return;
    setBusy(true);
    try {
      const res = await api.createAgentShare(agent.id);
      setStatus(res);
    } catch (err) {
      toast.error(errorMessage(err, "Could not create a share link."));
    } finally {
      setBusy(false);
    }
  }

  async function revokeShare() {
    if (!agent || busy) return;
    setBusy(true);
    try {
      await api.revokeAgentShare(agent.id);
      setStatus({ active: false, shareId: null, shareUrl: null, viewerCount: 0, reportCount: 0 });
      markOwnerRevoked();
      toast.success("Share link revoked.");
    } catch (err) {
      toast.error(errorMessage(err, "Could not revoke the share link."));
    } finally {
      setBusy(false);
    }
  }

  async function copyLink() {
    if (!status?.shareUrl) return;
    try {
      await navigator.clipboard.writeText(status.shareUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Could not copy the link.");
    }
  }

  if (!agent || loading) return null;

  return (
    <div className="flex flex-col gap-2 border-t border-border px-5 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          {/* Shared .state-dot instead of a bespoke emerald/grey circle. */}
          <span
            className="state-dot"
            style={
              {
                ["--state" as any]: status?.active ? "var(--state-success)" : "var(--state-idle)",
              } as any
            }
            aria-hidden="true"
          />
          {status?.active ? "Shared publicly" : "Not shared"}
        </div>
        {status?.active ? (
          <Button type="button" variant="outline" size="sm" onClick={revokeShare} disabled={busy}>
            {busy ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : null}
            {tHC("s4")}
          </Button>
        ) : (
          <Button type="button" variant="outline" size="sm" onClick={createShare} disabled={busy}>
            {busy ? (
              <Loader2 size={12} className="animate-spin" aria-hidden="true" />
            ) : (
              <Share2 size={12} aria-hidden="true" />
            )}
            Share
          </Button>
        )}
      </div>

      {!status?.active && <ShareEndNotice reason={reason} />}

      {status?.active && (
        <>
          {/* A printable QR for the share link (2026-10-14): a poster on a
          shop counter, a classroom wall or a clinic desk lets a phone open the
          agent with its camera - no typing, no account. */}
          {status.shareUrl && (
            <div className="flex justify-center rounded-lg border border-border bg-white p-2" data-testid="share-qr">
              <QrCode value={status.shareUrl} px={144} label={tHC("s3")} />
            </div>
          )}
          <div className="flex items-center gap-1.5 rounded-lg border border-border bg-secondary/30 px-2.5 py-2 text-xs">
            <span className="flex-1 truncate font-mono text-muted-foreground">{status.shareUrl}</span>
            <Button type="button" variant="ghost" size="icon" onClick={copyLink} aria-label={tHC("s2")}>
              {copied ? (
                <Check size={13} style={{ color: "var(--state-success)" }} aria-hidden="true" />
              ) : (
                <Copy size={13} aria-hidden="true" />
              )}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            {tHC("s1")}
          </p>
          <ShareLinkMeta status={status} />
        </>
      )}
    </div>
  );
}
