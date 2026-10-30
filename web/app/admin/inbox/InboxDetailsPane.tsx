"use client";

// Details & actions pane for the selected inbox conversation: reassign agent,
// escalate, approve / reject / unmap pairing, and the session facts. State
// and the act() wrapper live in InboxPanel.tsx.

import { Check, Flag, Unlink, X } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import Select from "../../components/Select";
import { inboxApi, type ConversationSummary } from "./inboxApi";
import { fullTime } from "./inboxFormat";
import { useTranslations } from "../../lib/i18n";

export interface AgentOption {
  id: string;
  name: string;
}

export function InboxDetailsPane({
  selected,
  agents,
  reassignId,
  onReassignIdChange,
  escalateReason,
  onEscalateReasonChange,
  confirmReject,
  onConfirmRejectChange,
  confirmUnpair,
  onConfirmUnpairChange,
  busy,
  mySubject,
  act,
  mergeSnapshot,
  onRejected,
}: {
  selected: ConversationSummary;
  agents: AgentOption[];
  reassignId: string;
  onReassignIdChange: (id: string) => void;
  escalateReason: string;
  onEscalateReasonChange: (reason: string) => void;
  confirmReject: boolean;
  onConfirmRejectChange: (v: boolean) => void;
  confirmUnpair: boolean;
  onConfirmUnpairChange: (v: boolean) => void;
  busy: boolean;
  mySubject: string | null | undefined;
  act: (fn: () => Promise<unknown>) => void;
  mergeSnapshot: (patch: Partial<ConversationSummary>) => void;
  /** The rejected sender's session is gone: close the thread. */
  onRejected: () => void;
}) {
  const tHC = useTranslations("hcAdminInboxInboxDetailsPane");
  return (
    <div className="space-y-4 rounded-xl border border-border bg-card p-4 text-sm">
      <div>
        <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{tHC("s13")}</p>
        <div className="flex items-center gap-2">
          <Select
            className="select-inline min-w-0 flex-1"
            value={reassignId}
            onChange={(e) => onReassignIdChange(e.target.value)}
            options={agents.map((a) => ({ value: a.id, label: a.name }))}
            placeholder={tHC("s12")}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy || !reassignId || reassignId === selected.agentId}
            onClick={() =>
              act(() =>
                inboxApi
                  .reassignAgent(selected.sessionId, reassignId)
                  .then(() => mergeSnapshot({ agentId: reassignId }))
              )
            }
          >
            Save
          </Button>
        </div>
        <p className="mt-1 text-[11px] text-muted-foreground">{tHC("s11")}</p>
      </div>

      {selected.paired && selected.handoffState === "AGENT" && (
        <div>
          <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Escalate</p>
          <Input
            value={escalateReason}
            onChange={(e) => onEscalateReasonChange(e.target.value)}
            placeholder={tHC("s10")}
            aria-label={tHC("s10")}
            className="mb-2"
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy || !escalateReason.trim()}
            onClick={() =>
              act(() =>
                inboxApi
                  .escalate(selected.sessionId, escalateReason.trim())
                  .then((s) => mergeSnapshot(s as Partial<ConversationSummary>))
              )
            }
          >
            <Flag size={13} /> {tHC("s9")}
          </Button>
        </div>
      )}

      <div>
        <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Pairing</p>
        {!selected.paired ? (
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">
              {tHC("s8")}{" "}
              <code className="rounded bg-muted px-1 py-0.5 text-foreground">{selected.pairingCode ?? "—"}</code>
            </p>
            <div className="flex items-center gap-2">
              <Button
                type="button"
                size="sm"
                disabled={busy}
                onClick={() =>
                  act(() =>
                    inboxApi.approve(selected.sessionId).then(() => mergeSnapshot({ paired: true, pairingCode: null }))
                  )
                }
              >
                <Check size={13} /> Approve
              </Button>
              {confirmReject ? (
                <>
                  <Button
                    type="button"
                    size="sm"
                    variant="destructive"
                    disabled={busy}
                    onClick={() => {
                      onConfirmRejectChange(false);
                      act(() => inboxApi.reject(selected.sessionId).then(onRejected));
                    }}
                  >
                    {tHC("s7")}
                  </Button>
                  <Button type="button" size="sm" variant="ghost" onClick={() => onConfirmRejectChange(false)}>
                    Cancel
                  </Button>
                </>
              ) : (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => onConfirmRejectChange(true)}
                >
                  <X size={13} /> Reject
                </Button>
              )}
            </div>
          </div>
        ) : (
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">{tHC("s6")}</p>
            {confirmUnpair ? (
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="destructive"
                  disabled={busy}
                  onClick={() => {
                    onConfirmUnpairChange(false);
                    act(() =>
                      inboxApi.unpair(selected.sessionId).then((r) =>
                        mergeSnapshot({
                          paired: false,
                          pairingCode: (r as { pairingCode?: string }).pairingCode ?? null,
                        })
                      )
                    );
                  }}
                >
                  {tHC("s5")}
                </Button>
                <Button type="button" size="sm" variant="ghost" onClick={() => onConfirmUnpairChange(false)}>
                  Cancel
                </Button>
              </div>
            ) : (
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => onConfirmUnpairChange(true)}
              >
                <Unlink size={13} /> {tHC("s4")}
              </Button>
            )}
          </div>
        )}
      </div>

      <div>
        <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Details</p>
        <dl className="space-y-1 text-xs text-muted-foreground">
          <div className="flex justify-between gap-2">
            <dt>Session</dt>
            <dd className="font-mono text-foreground">{selected.sessionId.slice(0, 8)}…</dd>
          </div>
          {selected.resolvedPhoneMasked && selected.resolvedPhoneMasked !== "***" && (
            <div className="flex justify-between gap-2">
              <dt>{tHC("s3")}</dt>
              <dd className="font-mono text-foreground">{selected.resolvedPhoneMasked}</dd>
            </div>
          )}
          <div className="flex justify-between gap-2">
            <dt>{tHC("s2")}</dt>
            <dd className="tabular-nums text-foreground">{fullTime(selected.createdAt)}</dd>
          </div>
          <div className="flex justify-between gap-2">
            <dt>{tHC("s1")}</dt>
            <dd className="tabular-nums text-foreground">{fullTime(selected.lastMessageAt)}</dd>
          </div>
          {selected.escalatedAt && (
            <div className="flex justify-between gap-2">
              <dt>Escalated</dt>
              <dd className="tabular-nums text-foreground">{fullTime(selected.escalatedAt)}</dd>
            </div>
          )}
          {selected.takenOverBy && (
            <div className="flex justify-between gap-2">
              <dt>Held by</dt>
              <dd className="truncate text-foreground">
                {mySubject && selected.takenOverBy === mySubject ? "you" : selected.takenOverBy}
              </dd>
            </div>
          )}
        </dl>
      </div>
    </div>
  );
}
