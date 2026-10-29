"use client";

import { useCallback, useEffect, useState } from "react";
import { Share2, Copy, Check, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../ui/button";
import { toast } from "../../lib/toastBus";
import { errorMessage } from "../../lib/errors";
import { ShareEndNotice, ShareLinkMeta, useShareEndReason } from "../channels/ShareLinkMeta";
import { useTranslations } from "../../lib/i18n";

/**
 * Owner-side control for "even registered consumers should be able to share
 * their workflows to external world" - mint/inspect/revoke a public link for
 * THIS workflow. Workflow counterpart of ShareAgentPanel; unlike CallPanel
 * this needs no live connection, just a status fetch on mount and three
 * actions (create/copy/revoke) against WorkflowShareOwnerController.
 */
// WorkflowShareOwnerController's status/create response body - the same
// five fields the old inline shape comment on the state hook documented
// (api.ts's share trio returns untyped JSON, so the shape is pinned here,
// at its only consumer, matching how ShareAgentPanel treats its twin).
type WorkflowShareStatus = {
  active: boolean;
  shareId: string | null;
  shareUrl: string | null;
  viewerCount: number;
  reportCount: number;
  expiresAt?: string | null;
};

// workflowName is display-only chrome and the caller passes `workflow?.name`,
// which is undefined until the canvas's initial fetch lands - hence optional.
export default function ShareWorkflowPanel({ workflowId, workflowName }: { workflowId: string; workflowName?: string }) {
    const tHC = useTranslations("hcComponentsWorkflowShareWorkflowPanel");
  const [status, setStatus] = useState<WorkflowShareStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const refetch = useCallback(() => {
    if (!workflowId) return;
    api
      .getWorkflowShareStatus(workflowId)
      .then((res) => setStatus(res))
      .catch(() => {
        // Keep the last known status; a failed background re-read is not evidence of a change.
      });
  }, [workflowId]);
  const { reason, markOwnerRevoked } = useShareEndReason(status, refetch);

  useEffect(() => {
    if (!workflowId) return undefined;
    let cancelled = false;
    setLoading(true);
    api
      .getWorkflowShareStatus(workflowId)
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
  }, [workflowId]);

  async function createShare() {
    if (!workflowId || busy) return;
    setBusy(true);
    try {
      const res = await api.createWorkflowShare(workflowId);
      setStatus(res);
    } catch (err) {
      toast.error(errorMessage(err, "Could not create a share link."));
    } finally {
      setBusy(false);
    }
  }

  async function revokeShare() {
    if (!workflowId || busy) return;
    setBusy(true);
    try {
      await api.revokeWorkflowShare(workflowId);
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

  if (!workflowId || loading) return null;

  return (
    <div className="flex flex-col gap-2 border-t border-border px-5 py-3">
      {workflowName ? (
        <p className="truncate text-xs font-medium text-muted-foreground">{workflowName}</p>
      ) : null}
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
            {busy ? <Loader2 size={12} className="animate-spin" /> : null}
            {tHC("hcComponentsWorkflowShareWorkflowPanel.s3")}
          </Button>
        ) : (
          <Button type="button" variant="outline" size="sm" onClick={createShare} disabled={busy}>
            {busy ? <Loader2 size={12} className="animate-spin" /> : <Share2 size={12} />}
            Share
          </Button>
        )}
      </div>

      {!status?.active && <ShareEndNotice reason={reason} />}

      {status?.active && (
        <>
          <div className="flex items-center gap-1.5 rounded-lg border border-border bg-secondary/30 px-2.5 py-2 text-xs">
            <span className="flex-1 truncate font-mono text-muted-foreground">{status.shareUrl}</span>
            <Button type="button" variant="ghost" size="icon" onClick={copyLink} aria-label={tHC("hcComponentsWorkflowShareWorkflowPanel.s2")}>
              {copied ? <Check size={13} style={{ color: "var(--state-success)" }} /> : <Copy size={13} />}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            {tHC("hcComponentsWorkflowShareWorkflowPanel.s1")}
          </p>
          <ShareLinkMeta status={status} />
        </>
      )}
    </div>
  );
}
