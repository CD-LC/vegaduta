"use client";

// Agent Lab - the original of a forked turn next to its branch. The original
// is a browser snapshot (forkSnapshots); the branch is whatever the live
// transcript holds at the fork point now. There is no server-side branch: a
// fork rewinds the one session in place, so once the conversation moves past
// the fork point in some other way only the original can be shown.
import { useId, useMemo, useState, type ReactElement } from "react";
import { ArrowLeft, Trash2 } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Select } from "../../ui/select";
import type { ForkSnapshot } from "./forkSnapshots";
import { clampText, listExchanges } from "./labTurns";
import RunGraphCanvas from "./RunGraphCanvas";
import RunGraphInspector, { TurnMetricsRow } from "./RunGraphInspector";
import { graphOfExchange, toolNamesOf, type RunGraph } from "./runGraphModel";
import type { LabTurn } from "./types";

const stepCount = (graph: RunGraph) => graph.nodes.filter((n) => n.kind !== "user" && n.kind !== "reply").length;

function Column({ heading, graph, assistant }: { heading: string; graph: RunGraph; assistant: LabTurn | null }) {
  const t = useTranslations("agentLab");
  // Each column inspects its own graph; the two selections are independent.
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = selectedId != null && graph.nodes.some((n) => n.id === selectedId) ? selectedId : null;
  return (
    <section aria-label={heading} className="flex min-w-0 flex-col gap-3">
      <h4 className="m-0 text-sm font-semibold text-foreground">{heading}</h4>
      <div className="elev-sunken h-[36vh] min-h-[240px] overflow-hidden rounded-xl">
        <RunGraphCanvas graph={graph} selectedId={selected} onSelect={setSelectedId} ariaLabel={`${t("graphCanvasLabel")}: ${heading}`} />
      </div>
      <TurnMetricsRow metrics={assistant?.metrics} />
      {assistant && assistant.content !== "" && (
        <div className="space-y-1">
          <p className="m-0 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{t("graphNodeReply")}</p>
          <p className="m-0 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/40 px-3 py-2 text-xs leading-relaxed text-foreground">
            {assistant.content}
          </p>
        </div>
      )}
      <RunGraphInspector graph={graph} selectedId={selected} onSelect={setSelectedId} />
    </section>
  );
}

export interface BranchCompareProps {
  sessionId: string | null;
  turns: readonly LabTurn[];
  /** Every snapshot kept for this agent, newest first. */
  snapshots: readonly ForkSnapshot[];
  onDiscard: (id: string) => void;
  onBack: () => void;
}

export default function BranchCompare({ sessionId, turns, snapshots, onDiscard, onBack }: BranchCompareProps): ReactElement {
  const t = useTranslations("agentLab");
  const selectId = useId();
  // A snapshot only means something against the session it was cut from.
  const own = useMemo(() => snapshots.filter((s) => sessionId != null && s.sessionId === sessionId), [snapshots, sessionId]);
  const [pickedId, setPickedId] = useState<string | null>(null);
  const snapshot = own.find((s) => s.id === pickedId) ?? own[0] ?? null;

  const back = (
    <Button type="button" variant="outline" size="sm" onClick={onBack}>
      <ArrowLeft />
      {t("graphCompareBack")}
    </Button>
  );

  if (!snapshot) {
    return (
      <div className="space-y-3" data-testid="lab-graph-compare">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="m-0 text-sm font-semibold text-foreground">{t("graphCompareTitle")}</h3>
          {back}
        </div>
        <p className="m-0 text-sm text-muted-foreground">{t("graphCompareNone")}</p>
        <p className="m-0 text-xs text-muted-foreground">{t("graphSnapshotNote")}</p>
      </div>
    );
  }

  const tail = snapshot.originalTail;
  const originalUser = tail[0]?.role === "user" ? tail[0] : null;
  const originalAssistant = tail[1]?.role === "assistant" ? tail[1] : null;
  const originalGraph = graphOfExchange(originalUser, originalAssistant);

  // The branch is whatever now sits at the fork point - valid only while
  // that is still the message this fork sent.
  const branchUser = turns[snapshot.forkUserIndex];
  const attached = branchUser?.role === "user" && branchUser.content === snapshot.sentMessage;
  const next = turns[snapshot.forkUserIndex + 1];
  const branchAssistant = attached && next?.role === "assistant" ? next : null;
  const branchGraph = attached ? graphOfExchange(branchUser, branchAssistant) : null;

  const removedLater = Math.max(0, listExchanges(tail).length - 1);
  const originalTools = toolNamesOf(originalGraph);
  const branchTools = branchGraph ? toolNamesOf(branchGraph) : [];
  const onlyOriginal = originalTools.filter((name) => !branchTools.includes(name));
  const onlyBranch = branchTools.filter((name) => !originalTools.includes(name));

  return (
    <div className="space-y-4" data-testid="lab-graph-compare">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0 space-y-1.5">
          <h3 className="m-0 text-sm font-semibold text-foreground">{t("graphCompareTitle")}</h3>
          <label htmlFor={selectId} className="sr-only">
            {t("graphCompareTitle")}
          </label>
          <Select
            id={selectId}
            value={snapshot.id}
            onValueChange={setPickedId}
            className="max-w-full"
            options={own.map((s) => ({
              value: s.id,
              label: t("graphCompareSnapshotOption", {
                time: new Date(s.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
                preview: clampText(s.originalMessage.replace(/\s+/g, " ").trim(), 60),
              }),
            }))}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => onDiscard(snapshot.id)}>
            <Trash2 />
            {t("graphCompareDiscard")}
          </Button>
          {back}
        </div>
      </div>

      {!attached && (
        <p role="status" className="m-0 rounded-lg border border-border bg-secondary/40 px-3 py-2 text-xs leading-relaxed text-muted-foreground">
          {t("graphCompareDetached")}
        </p>
      )}

      <div className={branchGraph ? "grid gap-5 lg:grid-cols-2" : "grid gap-5"}>
        <Column key={"o:" + snapshot.id} heading={t("graphCompareOriginal")} graph={originalGraph} assistant={originalAssistant} />
        {branchGraph && <Column key={"b:" + snapshot.id} heading={t("graphCompareBranch")} graph={branchGraph} assistant={branchAssistant} />}
      </div>

      <div className="space-y-1 text-xs leading-relaxed text-muted-foreground">
        {branchGraph && (
          <>
            {originalTools.length === 0 && branchTools.length === 0 ? (
              <p className="m-0">{t("graphCompareNoTools")}</p>
            ) : onlyOriginal.length === 0 && onlyBranch.length === 0 ? (
              <p className="m-0">{t("graphCompareSameTools")}</p>
            ) : (
              <>
                {onlyOriginal.length > 0 && <p className="m-0">{t("graphCompareToolsOnlyOriginal", { tools: onlyOriginal.join(", ") })}</p>}
                {onlyBranch.length > 0 && <p className="m-0">{t("graphCompareToolsOnlyBranch", { tools: onlyBranch.join(", ") })}</p>}
              </>
            )}
            <p className="m-0">
              {t("graphCompareSteps")}: {t("graphCompareOriginal")} {stepCount(originalGraph)} · {t("graphCompareBranch")} {stepCount(branchGraph)}
            </p>
          </>
        )}
        {removedLater === 1 && <p className="m-0">{t("graphCompareRemovedTurnsOne")}</p>}
        {removedLater > 1 && <p className="m-0">{t("graphCompareRemovedTurns", { count: removedLater })}</p>}
        <p className="m-0">{t("graphSnapshotNote")}</p>
      </div>
    </div>
  );
}
