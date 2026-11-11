"use client";

// Two-way thread for the selected inbox conversation: header with take over /
// hand back, the transcript, and the operator reply box. State and the act()
// wrapper live in InboxPanel.tsx.

import type { ReactNode, RefObject } from "react";
import { ArrowLeft, Headset, PanelRight, Send, ShieldCheck, Undo2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Textarea } from "../../components/ui/textarea";
import { Badge } from "../../components/ui/badge";
import { StatusDot } from "../../components/ui/status-dot";
import { channelOffered } from "../../lib/edition";
import { cn } from "../../lib/utils";
import { inboxApi, type ConversationSummary, type HandoffTranscriptMessage } from "./inboxApi";
import { ChannelBadge, authorLabel, dotStatus, stateLabel } from "./inboxFormat";
import { useTranslations } from "../../lib/i18n";

export function InboxThread({
  selected,
  busy,
  act,
  mergeSnapshot,
  transcript,
  transcriptFailed,
  onRetryTranscript,
  transcriptEndRef,
  draft,
  onDraftChange,
  sendReply,
  showDetails,
  onToggleDetails,
  onBack,
  detailsPane,
}: {
  selected: ConversationSummary;
  busy: boolean;
  act: (fn: () => Promise<unknown>) => void;
  mergeSnapshot: (patch: Partial<ConversationSummary>) => void;
  transcript: HandoffTranscriptMessage[];
  transcriptFailed: boolean;
  onRetryTranscript: () => void;
  transcriptEndRef: RefObject<HTMLDivElement | null>;
  draft: string;
  onDraftChange: (draft: string) => void;
  sendReply: () => void;
  showDetails: boolean;
  onToggleDetails: () => void;
  onBack: () => void;
  detailsPane: ReactNode;
}) {
  const tHC = useTranslations("hcAdminInboxInboxThread");
  return (
    <div className="flex h-full flex-col rounded-xl border border-border bg-card">
      <div className="flex flex-wrap items-center gap-2 border-b border-border p-3">
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="bg-transparent lg:hidden"
          onClick={onBack}
          title={tHC("s13")}
          aria-label={tHC("s13")}
        >
          <ArrowLeft size={15} aria-hidden="true" />
        </Button>
        <StatusDot status={dotStatus(selected)} />
        <ChannelBadge channel={selected.channel} />
        <span className="text-sm font-semibold text-foreground">{selected.externalIdMasked}</span>
        <Badge variant="outline" className="text-xs">
          {stateLabel(selected)}
        </Badge>
        {selected.channel === "SLACK" && channelOffered("slack") && (
          // Slack sessions are keyed by CHANNEL id, not by person
          // (SlackWebhookController's documented design) - a takeover
          // holds the whole channel/DM, worth saying out loud.
          <span className="text-[11px] text-muted-foreground">{tHC("s12")}</span>
        )}
        <div className="ms-auto flex items-center gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="bg-transparent xl:hidden"
            onClick={onToggleDetails}
            title={tHC("s11")}
          >
            <PanelRight size={14} />
          </Button>
          {selected.paired &&
            selected.channel !== "VOICE" &&
            (selected.handoffState !== "HUMAN" ? (
              <Button
                type="button"
                size="sm"
                disabled={busy}
                onClick={() =>
                  act(() =>
                    inboxApi.takeOver(selected.sessionId).then((s) => mergeSnapshot(s as Partial<ConversationSummary>))
                  )
                }
              >
                <Headset size={13} /> {tHC("s10")}
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() =>
                  act(() =>
                    inboxApi.release(selected.sessionId).then((s) => mergeSnapshot(s as Partial<ConversationSummary>))
                  )
                }
              >
                <Undo2 size={13} /> {tHC("s9")}
              </Button>
            ))}
        </div>
      </div>

      {selected.handoffState === "HUMAN" && (
        <div className="border-b border-border bg-accent/30 px-3 py-2 text-xs text-muted-foreground">{tHC("s8")}</div>
      )}
      {!selected.paired && (
        <div className="flex items-center gap-2 border-b border-border bg-amber-500/10 px-3 py-2 text-xs text-muted-foreground">
          <ShieldCheck size={13} className="shrink-0" />
          {tHC("s7")}
        </div>
      )}

      <div className="flex-1 space-y-2 overflow-y-auto p-3" style={{ minHeight: 240, maxHeight: 480 }}>
        {transcriptFailed && (
          <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
            <span>{tHC("s6")}</span>
            <Button type="button" size="sm" onClick={onRetryTranscript}>
              Retry
            </Button>
          </div>
        )}
        {!transcriptFailed && transcript.length === 0 && <p className="text-sm text-muted-foreground">{tHC("s5")}</p>}
        {transcript.map((m) => (
          <div key={m.id} className={cn("flex", m.direction === "OUTBOUND" ? "justify-end" : "justify-start")}>
            <div
              className={cn(
                "max-w-[80%] rounded-lg px-3 py-2 text-sm",
                m.direction === "OUTBOUND" ? "bg-primary/15 text-foreground" : "bg-muted text-foreground"
              )}
            >
              <p className="whitespace-pre-wrap break-words">{m.body}</p>
              <p className="mt-1 text-[10px] tabular-nums text-muted-foreground">
                {authorLabel(m)} · {new Date(m.createdAt).toLocaleString()}
              </p>
            </div>
          </div>
        ))}
        <p className="pt-1 text-[10px] text-muted-foreground">{tHC("s4")}</p>
        <div ref={transcriptEndRef} />
      </div>

      <div className="border-t border-border p-3">
        {selected.channel === "VOICE" ? (
          <p className="text-xs text-muted-foreground">{tHC("s3")}</p>
        ) : (
          <>
            <Textarea
              value={draft}
              onChange={(e) => onDraftChange(e.target.value)}
              placeholder={
                !selected.paired
                  ? "Approve the pairing first — replies can't reach an unapproved sender."
                  : selected.handoffState === "HUMAN"
                    ? "Reply as a human operator…"
                    : "Take the conversation over to reply — that's what silences the agent."
              }
              aria-label={tHC("s2")}
              disabled={!selected.paired || selected.handoffState !== "HUMAN" || busy}
              rows={3}
            />
            <div className="mt-2 flex justify-end">
              <Button
                type="button"
                size="sm"
                onClick={sendReply}
                disabled={!selected.paired || selected.handoffState !== "HUMAN" || busy || !draft.trim()}
              >
                <Send size={13} /> {tHC("s1")}
              </Button>
            </div>
          </>
        )}
      </div>

      {showDetails && <div className="border-t border-border p-3 xl:hidden">{detailsPane}</div>}
    </div>
  );
}
