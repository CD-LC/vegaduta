"use client";

// Agent Lab - Run Graph. Draws one playground exchange as a staged graph
// (user message -> plan / tools / guardrails -> reply), live while the turn
// streams, with a step inspector beside it. From the user node a turn can be
// forked: the server session is rewound to just before that message and the
// message is sent again through the playground's own send path. The pre-fork
// tail is kept as a browser snapshot and can be compared with the branch.
//
// Mounted by AgentLab directly inside its scrolling body, with no padding of
// its own around it, and only while the graph tab is showing.
import { useId, useMemo, useState } from "react";
import { GitCompare, Square } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Select } from "../../ui/select";
import BranchCompare from "./BranchCompare";
import ForkDialog from "./ForkDialog";
import { precheckFork } from "./forkMath";
import { deleteSnapshot, loadSnapshots, pruneSnapshots, type ForkSnapshot } from "./forkSnapshots";
import { clampText, listExchanges } from "./labTurns";
import RunGraphCanvas from "./RunGraphCanvas";
import RunGraphInspector, { TurnMetricsRow } from "./RunGraphInspector";
import { graphOfExchange } from "./runGraphModel";
import type { RunGraphPanelProps } from "./types";

export default function RunGraphPanel({
  agent,
  sessionId,
  turns,
  busy,
  onForkRerun,
  onCancelTurn,
}: RunGraphPanelProps) {
  const t = useTranslations("agentLab");
  const turnSelectId = useId();
  const followId = useId();

  const exchanges = useMemo(() => listExchanges(turns), [turns]);
  // null = follow the latest exchange as the conversation grows.
  const [pickedN, setPickedN] = useState<number | null>(null);
  const latest = exchanges.length > 0 ? exchanges[exchanges.length - 1] : null;
  const current = (pickedN != null ? exchanges.find((ex) => ex.n === pickedN) : null) ?? latest;

  // `turns` is a new array on every streamed token but the turn objects of
  // untouched exchanges keep their identity, so the graph is only rebuilt
  // when the exchange on screen actually changed.
  const currentUser = current?.user ?? null;
  const currentAssistant = current?.assistant ?? null;
  const graph = useMemo(() => graphOfExchange(currentUser, currentAssistant), [currentUser, currentAssistant]);

  // The selection belongs to one exchange; node ids are positional, so an id
  // carried over to another exchange would point at an unrelated step.
  const [selection, setSelection] = useState<{ n: number; id: string } | null>(null);
  const selectedId =
    selection && current && selection.n === current.n && graph.nodes.some((n) => n.id === selection.id)
      ? selection.id
      : null;

  const [view, setView] = useState<"graph" | "compare">("graph");
  const [forkUserIndex, setForkUserIndex] = useState<number | null>(null);
  // The transcript index of the message the last fork re-sent. If that
  // exchange ends up with no reply, the re-send failed - and the playground's
  // own error line is underneath this overlay, where nobody can see it.
  const [resentIndex, setResentIndex] = useState<number | null>(null);
  const [snapshots, setSnapshots] = useState<ForkSnapshot[]>(() => loadSnapshots(agent.id));

  // Why the fork button is off, or null when a fork can be attempted. The
  // server count is still checked on confirm (ForkDialog); this is only the
  // part the transcript alone can answer, so the refusal is visible up front.
  const forkBlockedReason = (userIndex: number): string | null => {
    if (busy) return t("graphForkBusy");
    if (!sessionId) return t("graphForkNoSession");
    const pre = precheckFork(turns, userIndex);
    if (pre.ok !== false) return null;
    if (pre.reason === "on-device") return t("graphForkOnDevice");
    if (pre.reason === "prefix-unclean") return t("graphForkPrefixUnclean");
    return t("graphForkTailUnclean");
  };

  // Only while a turn is actually running: `busy` is also true during an
  // attachment upload, which has no turn to cancel.
  const turnInFlight = busy && exchanges.some((ex) => ex.pending);
  const cancelTurn = turnInFlight && (
    <Button type="button" variant="outline" size="sm" onClick={onCancelTurn}>
      <Square aria-hidden="true" />
      {t("graphCancelTurn")}
    </Button>
  );

  const forkDialog = forkUserIndex != null && sessionId && turns[forkUserIndex]?.role === "user" && (
    <ForkDialog
      agent={agent}
      sessionId={sessionId}
      turns={turns}
      userIndex={forkUserIndex}
      busy={busy}
      focusFallback={() => document.getElementById(turnSelectId)?.focus()}
      onClose={() => setForkUserIndex(null)}
      onSnapshotSaved={(snapshot) =>
        setSnapshots((prev) => pruneSnapshots([snapshot, ...prev.filter((s) => s.id !== snapshot.id)]))
      }
      onSnapshotDropped={(id) => setSnapshots((prev) => prev.filter((s) => s.id !== id))}
      onForkRerun={(keepTurnCount, message) => {
        // The branch becomes the latest exchange: go back to following it.
        setPickedN(null);
        setSelection(null);
        setResentIndex(keepTurnCount);
        onForkRerun(keepTurnCount, message);
      }}
    />
  );

  if (view === "compare") {
    return (
      <div className="flex flex-col gap-4 p-4 sm:p-5" data-testid="lab-graph-panel">
        {cancelTurn && <div className="flex justify-end">{cancelTurn}</div>}
        <BranchCompare
          sessionId={sessionId}
          turns={turns}
          snapshots={snapshots}
          onDiscard={(id) => {
            deleteSnapshot(agent.id, id);
            setSnapshots((prev) => prev.filter((s) => s.id !== id));
          }}
          onBack={() => setView("graph")}
        />
      </div>
    );
  }

  if (!current) {
    return (
      <div className="p-4 sm:p-5" data-testid="lab-graph-panel">
        <p className="m-0 text-sm text-muted-foreground">{t("graphEmpty")}</p>
      </div>
    );
  }

  // A user message that never got a reply (the send failed before the first
  // token, or was cancelled). Not "answered directly": it was not answered.
  const unanswered = !busy && current.user != null && current.assistant == null;
  const resendFailed = unanswered && resentIndex != null && current.userIndex === resentIndex;
  const answered = current.assistant != null && !current.pending && current.assistant.content !== "";
  const noSteps = answered && graph.nodes.every((n) => n.kind === "user" || n.kind === "reply");
  const forkFrom = current.userIndex;

  return (
    <div className="flex flex-col gap-4 p-4 sm:p-5" data-testid="lab-graph-panel">
      <div className="flex flex-wrap items-end gap-x-4 gap-y-3">
        <div className="min-w-0 space-y-1">
          <label htmlFor={turnSelectId} className="block text-xs font-medium text-muted-foreground">
            {t("graphTurnLabel")}
          </label>
          <Select
            id={turnSelectId}
            value={String(current.n)}
            onValueChange={(v) => setPickedN(Number(v))}
            options={exchanges.map((ex) => ({
              value: String(ex.n),
              label: t("graphTurnOption", {
                n: ex.n,
                preview: clampText((ex.user?.content ?? ex.assistant?.content ?? "").replace(/\s+/g, " ").trim(), 60),
              }),
            }))}
          />
        </div>
        <label htmlFor={followId} className="flex h-9 cursor-pointer items-center gap-2 text-xs text-foreground">
          <input
            id={followId}
            type="checkbox"
            checked={pickedN == null}
            onChange={(e) => setPickedN(e.target.checked ? null : current.n)}
          />
          {t("graphFollowLatest")}
        </label>
        {current.pending && (
          <span role="status" className="inline-flex h-9 items-center gap-1.5 text-xs font-medium text-primary">
            <span className="size-1.5 rounded-full bg-primary motion-safe:animate-pulse" aria-hidden="true" />
            {t("graphLive")}
          </span>
        )}
        <div className="ms-auto flex flex-wrap items-center gap-2">
          {cancelTurn}
          <Button type="button" variant="outline" size="sm" onClick={() => setView("compare")}>
            <GitCompare aria-hidden="true" />
            {t("graphCompareOpen")}
          </Button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="min-w-0 space-y-3">
          <div className="elev-sunken h-[52vh] min-h-[280px] overflow-hidden rounded-xl">
            <RunGraphCanvas
              graph={graph}
              selectedId={selectedId}
              onSelect={(id) => setSelection({ n: current.n, id })}
              ariaLabel={t("graphCanvasLabel")}
            />
          </div>
          {noSteps && <p className="m-0 text-xs text-muted-foreground">{t("graphNoSteps")}</p>}
          {resendFailed ? (
            <p role="alert" className="m-0 text-xs leading-relaxed text-destructive">
              {t("graphForkResendFailed")}
            </p>
          ) : (
            unanswered && <p className="m-0 text-xs text-muted-foreground">{t("graphNoReply")}</p>
          )}
          <TurnMetricsRow metrics={current.assistant?.metrics} />
        </div>
        <RunGraphInspector
          graph={graph}
          selectedId={selectedId}
          onSelect={(id) => setSelection({ n: current.n, id })}
          fork={
            forkFrom >= 0
              ? { onFork: () => setForkUserIndex(forkFrom), blockedReason: forkBlockedReason(forkFrom) }
              : undefined
          }
        />
      </div>

      {forkDialog}
    </div>
  );
}
