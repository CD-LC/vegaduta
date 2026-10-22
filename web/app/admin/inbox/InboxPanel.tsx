"use client";

// Operator inbox panel — rebuilt 2026-09-23 from a handoff-only queue into
// the full conversations operations page: EVERY channel session (not just
// AWAITING_HUMAN/HUMAN) listed from GET /api/handoff/conversations with
// channel/view/text filters + server-side paging, a stats strip from
// /api/handoff/stats, a WhatsApp-style two-way thread (inbound AND outbound
// — agent replies included now that the adapters write OUTBOUND
// channel_message_log rows), and a details/actions pane covering the whole
// lifecycle: approve/reject pairing, reassign agent, escalate, take over,
// reply, release, unpair. Same thin-page/fat-panel split as before —
// page.tsx owns the admin gate + header counts, this panel owns everything
// else.
//
// i18n note: the stats strip labels are translated (f2GapsAdmin); the remaining
// panel strings are still literal English and belong to a later i18n batch.
// (Original rationale for literals follows.)
// web/messages/en.json is outside this workstream's file ownership and is
// the highest-conflict-risk file in the frontend; the t()-miss behavior
// renders raw dotted keys, so referencing keys this workstream cannot add
// would ship a page of "adminInboxPage.xyz" literals.
//
// Data-state honesty (2026-07-29 UX-audit rule): "couldn't load" is a
// banner with retry, never an empty state — on an inbox, a failed fetch
// rendered as "all clear" reads as a missed customer. The shared api.ts
// client missing the handoff methods renders its own amber banner via
// InboxClientNotWiredError, distinct from "load failed" and "empty".

import { useTranslations } from "../../lib/i18n";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Headset,
  MessageSquare,
  RefreshCw,
  Search,
} from "lucide-react";
import { api } from "../../lib/api";
import { auth } from "../../lib/auth";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Badge } from "../../components/ui/badge";
import { StatusDot } from "../../components/ui/status-dot";
import { EmptyState } from "../../components/ax/page-kit";
import PaginationControls from "../../components/PaginationControls";
import Select from "../../components/Select";
import { cn } from "../../lib/utils";
import { inboxApi, InboxClientNotWiredError, type ConversationSummary, type ConversationView, type HandoffTranscriptMessage, type InboxStats } from "./inboxApi";
import { InboxDetailsPane, type AgentOption } from "./InboxDetailsPane";
import { InboxThread } from "./InboxThread";
import { ChannelBadge, offeredChannels, VIEW_TABS, dotStatus, relTime, stateLabel } from "./inboxFormat";

const POLL_MS = 15000;
const DEFAULT_PAGE_SIZE = 25;

export default function InboxPanel({
  onCountsChange,
}: {
  // null counts = "first load finished but produced no numbers" (failure /
  // client-not-wired) - the page still needs the signal to release the
  // route-transition cover, or a failed first fetch would hold it forever.
  onCountsChange?: (waiting: number | null, held: number | null) => void;
}) {
  const tg = useTranslations("f2GapsAdmin");
  const [stats, setStats] = useState<InboxStats | null>(null);
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [view, setView] = useState<ConversationView>("all");
  const [channel, setChannel] = useState<string>("");
  const [searchInput, setSearchInput] = useState("");
  const [appliedQ, setAppliedQ] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [notWired, setNotWired] = useState(false);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  // Snapshot fallback: a row can legitimately fall out of the current page
  // after an action (e.g. approving under the "pending" view removes it) -
  // keep the last-seen summary so the open thread doesn't blank out.
  const [snapshot, setSnapshot] = useState<ConversationSummary | null>(null);
  const [transcript, setTranscript] = useState<HandoffTranscriptMessage[]>([]);
  const [transcriptFailed, setTranscriptFailed] = useState(false);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [reassignId, setReassignId] = useState("");
  const [escalateReason, setEscalateReason] = useState("");
  const [confirmReject, setConfirmReject] = useState(false);
  const [confirmUnpair, setConfirmUnpair] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);

  const mySubject = auth.getUserId();
  const selected = items.find((s) => s.sessionId === selectedId) ??
    (snapshot?.sessionId === selectedId ? snapshot : null);

  const refreshStats = useCallback(() => {
    return inboxApi
      .stats()
      .then((s) => {
        setStats(s);
        onCountsChange?.(s.waitingHuman, s.held);
      })
      .catch((e: unknown) => {
        if (!(e instanceof InboxClientNotWiredError)) {
          onCountsChange?.(null, null);
        }
      });
  }, [onCountsChange]);

  const refreshList = useCallback(() => {
    return inboxApi
      .conversations({
        channel: channel || undefined,
        view,
        q: appliedQ || undefined,
        page,
        size: pageSize,
      })
      .then((p) => {
        setItems(p.items);
        setTotal(p.total);
        setPage(p.page);
        setTotalPages(Math.max(1, p.totalPages));
        setLoadFailed(false);
        setNotWired(false);
      })
      .catch((e: unknown) => {
        if (e instanceof InboxClientNotWiredError) setNotWired(true);
        else setLoadFailed(true);
        onCountsChange?.(null, null);
      })
      .finally(() => setLoaded(true));
  }, [channel, view, appliedQ, page, pageSize, onCountsChange]);

  const refreshTranscript = useCallback((sessionId: string) => {
    return inboxApi
      .transcript(sessionId)
      .then((rows) => {
        setTranscript(rows);
        setTranscriptFailed(false);
      })
      .catch((e: unknown) => {
        if (!(e instanceof InboxClientNotWiredError)) setTranscriptFailed(true);
      });
  }, []);

  // Agents list for the reassign picker - one fetch, rarely changes.
  useEffect(() => {
    api
      .listAgents()
      .then((rows: unknown) => {
        if (Array.isArray(rows)) {
          setAgents(
            rows
              .filter((a) => a && typeof a === "object" && "id" in a)
              .map((a) => ({ id: String((a as { id: unknown }).id), name: String((a as { name?: unknown }).name ?? "Agent") }))
          );
        }
      })
      .catch((): undefined => undefined);
  }, []);

  // Debounce the search box into appliedQ; typing resets to page 0.
  useEffect(() => {
    const t = setTimeout(() => {
      setAppliedQ(searchInput.trim());
      setPage(0);
    }, 350);
    return () => clearTimeout(t);
  }, [searchInput]);

  useEffect(() => {
    void refreshList();
    void refreshStats();
  }, [refreshList, refreshStats]);

  useEffect(() => {
    const timer = setInterval(() => {
      void refreshList();
      void refreshStats();
      if (selectedId) void refreshTranscript(selectedId);
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [refreshList, refreshStats, refreshTranscript, selectedId]);

  useEffect(() => {
    if (selectedId) {
      setTranscript([]);
      setTranscriptFailed(false);
      void refreshTranscript(selectedId);
    }
    setEscalateReason("");
    setConfirmReject(false);
    setConfirmUnpair(false);
  }, [selectedId, refreshTranscript]);

  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ block: "end" });
  }, [transcript.length]);

  // Merge a backend-returned session into the snapshot so the open thread
  // reflects the action even while the list refetch is in flight.
  const mergeSnapshot = (patch: Partial<ConversationSummary>): void => {
    setSnapshot((prev) => (prev && selectedId && prev.sessionId === selectedId ? { ...prev, ...patch } : prev));
  };

  const refreshAll = (): Promise<unknown> =>
    Promise.all([refreshList(), refreshStats(), selectedId ? refreshTranscript(selectedId) : Promise.resolve()]);

  const act = (fn: () => Promise<unknown>): void => {
    setBusy(true);
    fn()
      .then(refreshAll)
      // Errors already raised the shared client's global toast; state is refreshed either way.
      .catch((): undefined => undefined)
      .finally(() => setBusy(false));
  };

  const sendReply = (): void => {
    if (!selected || !draft.trim()) return;
    const body = draft.trim();
    setBusy(true);
    inboxApi
      .reply(selected.sessionId, body)
      .then(() => {
        setDraft("");
        return refreshTranscript(selected.sessionId);
      })
      .catch((): undefined => undefined)
      .finally(() => setBusy(false));
  };

  const selectConversation = (s: ConversationSummary): void => {
    setSelectedId(s.sessionId);
    setSnapshot(s);
    setReassignId(s.agentId);
    setShowDetails(false);
  };

  if (notWired) {
    return (
      <div className="rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-foreground">
        <p className="font-medium">The operator inbox backend is live, but the console client isn&apos;t wired yet.</p>
        <p className="mt-1 text-muted-foreground">
          The shared API client (<code>web/app/lib/api.ts</code>) is missing the handoff methods this page calls — see{" "}
          <code>web/app/admin/inbox/inboxApi.ts</code> for the exact list. This page goes fully live the moment they
          land.
        </p>
      </div>
    );
  }

  const detailsPane = selected ? (
    <InboxDetailsPane
      selected={selected}
      agents={agents}
      reassignId={reassignId}
      onReassignIdChange={setReassignId}
      escalateReason={escalateReason}
      onEscalateReasonChange={setEscalateReason}
      confirmReject={confirmReject}
      onConfirmRejectChange={setConfirmReject}
      confirmUnpair={confirmUnpair}
      onConfirmUnpairChange={setConfirmUnpair}
      busy={busy}
      mySubject={mySubject}
      act={act}
      mergeSnapshot={mergeSnapshot}
      onRejected={() => {
        setSelectedId(null);
        setSnapshot(null);
      }}
    />
  ) : null;

  return (
    <div className="flex flex-col gap-4">
      {/* Stats strip - each card jumps to the matching view. */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        {(
          [
            { key: "waiting" as const, label: tg("inbox_waiting"), value: stats?.waitingHuman },
            { key: "held" as const, label: tg("inbox_held"), value: stats?.held },
            { key: "pending" as const, label: tg("inbox_pending"), value: stats?.pendingPairing },
            { key: "agent" as const, label: tg("inbox_agent"), value: stats?.agentHandled },
            { key: "all" as const, label: tg("inbox_all"), value: stats?.total },
          ]
        ).map((c) => (
          <button
            key={c.key}
            type="button"
            aria-pressed={view === c.key}
            onClick={() => {
              setView(c.key);
              setPage(0);
            }}
            className={cn(
              "rounded-xl border border-border bg-card p-3 text-left transition-colors hover:bg-accent/40",
              view === c.key && "border-primary/60 bg-accent/40"
            )}
          >
            <p className="text-xl font-semibold tabular-nums text-foreground">{c.value ?? "—"}</p>
            <p className="text-[11px] text-muted-foreground">{c.label}</p>
          </button>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(280px,340px)_minmax(0,1fr)] xl:grid-cols-[minmax(280px,340px)_minmax(0,1fr)_minmax(240px,300px)]">
        {/* Conversation list column */}
        <div className={cn("min-w-0", selected && "hidden lg:block")}>
          <div className="mb-2 flex items-center gap-2">
            <div className="relative min-w-0 flex-1">
              <Search size={13} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Search sender, phone or message…"
                aria-label="Search sender, phone or message"
                className="pl-7"
              />
            </div>
            <Select
              className="w-[130px] shrink-0"
              value={channel}
              onChange={(e) => {
                setChannel(e.target.value);
                setPage(0);
              }}
              options={[{ value: "", label: "All channels" }, ...offeredChannels().map((c) => ({ value: c.value, label: c.label }))]}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="shrink-0 bg-transparent"
              onClick={() => refreshAll()}
              disabled={busy}
              title="Refresh"
              aria-label="Refresh"
            >
              <RefreshCw size={13} aria-hidden="true" />
            </Button>
          </div>

          <div className="mb-2 flex flex-wrap gap-1">
            {VIEW_TABS.map((t) => (
              <button
                key={t.value}
                type="button"
                aria-pressed={view === t.value}
                onClick={() => {
                  setView(t.value);
                  setPage(0);
                }}
                className={cn(
                  "rounded-full border border-border px-2.5 py-1 text-[11px] transition-colors",
                  view === t.value ? "border-primary/60 bg-accent/60 text-foreground" : "text-muted-foreground hover:bg-accent/40"
                )}
              >
                {t.label}
              </button>
            ))}
          </div>

          {loadFailed && (
            <div className="mb-3 flex items-center justify-between gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
              <span>Couldn&apos;t load conversations — this does not mean it&apos;s empty.</span>
              <Button type="button" size="sm" onClick={() => refreshAll()}>
                Retry
              </Button>
            </div>
          )}

          {loaded && !loadFailed && items.length === 0 ? (
            <EmptyState
              icon={Headset}
              title="No conversations match"
              description={
                view === "all" && !channel && !appliedQ
                  ? "Nothing has arrived on any channel yet — inbound messages across WhatsApp, Telegram, email and the rest land here."
                  : "Try widening the view, channel or search filters."
              }
            />
          ) : (
            <ul className="flex flex-col gap-2">
              {items.map((s) => (
                <li key={s.sessionId}>
                  <button
                    type="button"
                    onClick={() => selectConversation(s)}
                    className={cn(
                      "w-full rounded-lg border border-border bg-card p-3 text-left transition-colors hover:bg-accent/40",
                      s.sessionId === selectedId && "border-primary/60 bg-accent/40"
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <StatusDot status={dotStatus(s)} />
                      <ChannelBadge channel={s.channel} />
                      <span className="truncate text-xs text-muted-foreground">{s.externalIdMasked}</span>
                      <span className="ml-auto shrink-0 text-xs tabular-nums text-muted-foreground">
                        {relTime(s.lastMessageAt)}
                      </span>
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5">
                      <Badge variant="outline" className="text-xs">
                        {stateLabel(s)}
                      </Badge>
                      {!s.paired && s.pairingCode && (
                        <code className="rounded bg-muted px-1 py-0.5 text-xs text-foreground">{s.pairingCode}</code>
                      )}
                      {s.handoffState === "HUMAN" && s.takenOverBy && (
                        <span className="truncate text-[11px] text-muted-foreground">
                          {mySubject && s.takenOverBy === mySubject ? "held by you" : `held by ${s.takenOverBy}`}
                        </span>
                      )}
                    </div>
                    {s.escalationReason && (
                      <p className="mt-1 truncate text-xs text-muted-foreground" title={s.escalationReason}>
                        {s.escalationReason}
                      </p>
                    )}
                    {s.lastMessagePreview && (
                      <p className="mt-1 truncate text-xs text-foreground/80" title={s.lastMessagePreview}>
                        {s.lastMessagePreview}
                      </p>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <PaginationControls
            // Server-side paging - the shared control is 1-based while the
            // backend page param is 0-based; converting at this boundary
            // keeps both contracts intact.
            page={page + 1}
            totalPages={totalPages}
            totalItems={total}
            pageSize={pageSize}
            onPageChange={(p: number) => setPage(p - 1)}
            onPageSizeChange={(s: number) => {
              setPageSize(s);
              setPage(0);
            }}
            itemLabel="conversations"
          />
        </div>

        {/* Thread column */}
        <div className={cn("min-w-0", !selected && "hidden lg:block")}>
          {!selected ? (
            <EmptyState
              icon={MessageSquare}
              title="Pick a conversation"
              description="Select a conversation to read its full two-way transcript, take it over, and reply as a human."
            />
          ) : (
            <InboxThread
              selected={selected}
              busy={busy}
              act={act}
              mergeSnapshot={mergeSnapshot}
              transcript={transcript}
              transcriptFailed={transcriptFailed}
              onRetryTranscript={() => refreshTranscript(selected.sessionId)}
              transcriptEndRef={transcriptEndRef}
              draft={draft}
              onDraftChange={setDraft}
              sendReply={sendReply}
              showDetails={showDetails}
              onToggleDetails={() => setShowDetails((v) => !v)}
              onBack={() => setSelectedId(null)}
              detailsPane={detailsPane}
            />
          )}
        </div>

        {/* Details column (xl+) */}
        <div className="hidden min-w-0 xl:block">
          {detailsPane ?? (
            <div className="rounded-xl border border-dashed border-border p-4 text-xs text-muted-foreground">
              Select a conversation to see routing, pairing and escalation controls.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
