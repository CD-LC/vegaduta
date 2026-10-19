"use client";

// Agent Lab - the details of one run-graph node, plus Previous/Next buttons
// that walk the nodes in array order. The buttons are the keyboard path: the
// canvas selects by pointer only, so every node has to be reachable from here.
import type { ReactElement, ReactNode } from "react";
import {
  AlertTriangle,
  ArrowRightLeft,
  Ban,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleDot,
  CircleSlash,
  GitFork,
  ListChecks,
  Loader,
  MessageSquare,
  PenLine,
  Shield,
  Smartphone,
  User,
  Wrench,
} from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { isBillingEnabled } from "../../../lib/billing-flag";
import { cn } from "../../../lib/utils";
import { Button } from "../../ui/button";
import type { RunGraph, RunGraphNode, RunGraphNodeKind, RunGraphNodeStatus } from "./runGraphModel";
import type { LabTurnMetrics } from "./types";

/** Translated names for a node's kind and status. Explicit branches, one
 * literal key each, so labI18nKeys.test.ts can see every key. */
export function useStepLabels(): {
  kindLabel: (kind: RunGraphNodeKind) => string;
  statusLabel: (status: RunGraphNodeStatus) => string;
} {
  const t = useTranslations("agentLab");
  const kindLabel = (kind: RunGraphNodeKind): string => {
    switch (kind) {
      case "user":
        return t("graphNodeUser");
      case "plan":
        return t("graphNodePlan");
      case "tool":
        return t("graphNodeTool");
      case "guardrail":
        return t("graphNodeGuardrail");
      case "handoff":
        return t("graphNodeHandoff");
      case "reply":
        return t("graphNodeReply");
      default:
        return t("graphNodeEvent");
    }
  };
  const statusLabel = (status: RunGraphNodeStatus): string => {
    switch (status) {
      case "running":
        return t("graphStatusRunning");
      case "error":
        return t("graphStatusError");
      case "streaming":
        return t("graphStatusStreaming");
      case "interrupted":
        return t("graphStatusInterrupted");
      case "blocked":
        return t("graphStatusBlocked");
      default:
        return t("graphStatusOk");
    }
  };
  return { kindLabel, statusLabel };
}

export function StepKindIcon({ kind, className }: { kind: RunGraphNodeKind; className?: string }): ReactElement {
  const props = { className: cn("size-3.5 shrink-0", className), "aria-hidden": true } as const;
  switch (kind) {
    case "user":
      return <User {...props} aria-hidden="true" />;
    case "plan":
      return <ListChecks {...props} aria-hidden="true" />;
    case "tool":
      return <Wrench {...props} aria-hidden="true" />;
    case "guardrail":
      return <Shield {...props} aria-hidden="true" />;
    case "handoff":
      return <ArrowRightLeft {...props} aria-hidden="true" />;
    case "reply":
      return <MessageSquare {...props} aria-hidden="true" />;
    default:
      return <CircleDot {...props} aria-hidden="true" />;
  }
}

/** Status glyph. Static on purpose (no spinner): node motion is limited to
 * opacity and box-shadow, see run-graph.css. */
export function StepStatusIcon({
  status,
  className,
}: {
  status: RunGraphNodeStatus;
  className?: string;
}): ReactElement {
  const props = { className: cn("size-3 shrink-0", className), "aria-hidden": true } as const;
  switch (status) {
    case "running":
      return <Loader {...props} aria-hidden="true" />;
    case "error":
      return <AlertTriangle {...props} aria-hidden="true" />;
    case "streaming":
      return <PenLine {...props} aria-hidden="true" />;
    case "interrupted":
      return <CircleSlash {...props} aria-hidden="true" />;
    case "blocked":
      return <Ban {...props} aria-hidden="true" />;
    default:
      return <Check {...props} aria-hidden="true" />;
  }
}

export function statusTone(status: RunGraphNodeStatus): string {
  if (status === "error") return "text-destructive";
  if (status === "blocked" || status === "interrupted") return "text-[var(--warning-text)]";
  if (status === "running" || status === "streaming") return "text-primary";
  return "text-muted-foreground";
}

export function formatMs(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/** A turn's measured totals. Tokens and cost appear only when the turn
 * reported them (on-device and cached turns do not). Shared by the graph
 * view and both columns of the compare view. */
export function TurnMetricsRow({ metrics }: { metrics: LabTurnMetrics | undefined }): ReactElement | null {
  const t = useTranslations("agentLab");
  if (!metrics) return null;
  const billing = isBillingEnabled();
  const tokens = metrics.totalTokens ?? null;
  const cost = metrics.estimatedCostUsd ?? null;
  const item = (label: string, value: string) => (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="m-0 font-medium tabular-nums text-foreground">{value}</dd>
    </div>
  );
  return (
    <dl data-testid="lab-graph-metrics" className="m-0 flex flex-wrap gap-x-5 gap-y-1 text-xs">
      {item(t("graphMetricElapsed"), formatMs(metrics.elapsedMs))}
      {item(t("graphMetricTools"), String(metrics.toolCalls))}
      {tokens != null && item(t("graphMetricTokens"), String(tokens))}
      {billing && cost != null && cost > 0 && item(t("graphMetricCost"), `$${cost.toFixed(cost < 0.01 ? 4 : 2)}`)}
    </dl>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0 space-y-1">
      <dt className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="m-0 text-xs text-foreground">{children}</dd>
    </div>
  );
}

function Block({ text, tone }: { text: string; tone?: string }) {
  return (
    <pre
      className={cn(
        "m-0 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/40 px-2 py-1.5 font-mono text-[11px] leading-relaxed",
        tone
      )}
    >
      {text}
    </pre>
  );
}

export default function RunGraphInspector({
  graph,
  selectedId,
  onSelect,
  fork,
}: {
  graph: RunGraph;
  selectedId: string | null;
  onSelect: (id: string) => void;
  /** The fork control shown on the user node. Left out when the exchange has
   * no user message to fork from. `blockedReason` disables it and says why. */
  fork?: { onFork: () => void; blockedReason: string | null };
}): ReactElement {
  const t = useTranslations("agentLab");
  const { kindLabel, statusLabel } = useStepLabels();
  const { nodes } = graph;
  const index = selectedId == null ? -1 : nodes.findIndex((n) => n.id === selectedId);
  const node: RunGraphNode | null = index >= 0 ? nodes[index] : null;

  // Wraps at both ends, so neither button ever goes dead under the focus.
  const step = (delta: 1 | -1) => {
    if (nodes.length === 0) return;
    const next = index < 0 ? (delta === 1 ? 0 : nodes.length - 1) : (index + delta + nodes.length) % nodes.length;
    onSelect(nodes[next].id);
  };

  return (
    <section
      aria-label={t("graphInspectTitle")}
      data-testid="lab-graph-inspector"
      className="flex min-w-0 flex-col gap-3 rounded-xl border border-border bg-card p-3"
    >
      <div className="flex items-center justify-between gap-2">
        <h3 className="m-0 text-sm font-semibold text-foreground">{t("graphInspectTitle")}</h3>
        <div className="flex items-center gap-1.5">
          {node && (
            <span className="text-[11px] tabular-nums text-muted-foreground">
              {index + 1} / {nodes.length}
            </span>
          )}
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-8"
            onClick={() => step(-1)}
            disabled={nodes.length === 0}
            aria-label={t("graphInspectPrev")}
            title={t("graphInspectPrev")}
          >
            <ChevronLeft aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-8"
            onClick={() => step(1)}
            disabled={nodes.length === 0}
            aria-label={t("graphInspectNext")}
            title={t("graphInspectNext")}
          >
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </div>

      {/* Announces WHICH step is selected, and nothing else. The details
          below are not a live region: a streaming reply's text changes on
          every token and would be re-read from the top each time. */}
      <p role="status" className="sr-only" data-testid="lab-graph-inspector-status">
        {node
          ? [
              kindLabel(node.kind),
              node.kind !== "user" ? statusLabel(node.status) : null,
              `${index + 1} / ${nodes.length}`,
            ]
              .filter(Boolean)
              .join(", ")
          : ""}
      </p>
      <div className="min-w-0 space-y-3">
        {!node ? (
          <p className="m-0 text-xs text-muted-foreground">{t("graphInspectHint")}</p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
              <span className="inline-flex items-center gap-1.5 font-medium text-foreground">
                <StepKindIcon kind={node.kind} className="text-muted-foreground" />
                {kindLabel(node.kind)}
              </span>
              {node.kind !== "user" && (
                <span className={cn("inline-flex items-center gap-1", statusTone(node.status))}>
                  <StepStatusIcon status={node.status} />
                  {statusLabel(node.status)}
                </span>
              )}
              {node.onDevice && (
                <span className="inline-flex items-center gap-1 rounded-full border border-border px-1.5 py-px text-[11px] text-muted-foreground">
                  <Smartphone className="size-3" aria-hidden="true" />
                  {t("graphOnDeviceBadge")}
                </span>
              )}
            </div>
            {node.handoff && (
              <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                <ArrowRightLeft className="size-3.5 shrink-0" aria-hidden="true" />
                {t("graphHandoffBadge")}
              </p>
            )}
            <dl className="m-0 space-y-3">
              {node.kind === "event" && <Field label={t("graphNodeEvent")}>{node.title}</Field>}
              {node.tool && (
                <Field label={t("graphInspectTool")}>
                  <span className="break-all font-mono">{node.tool}</span>
                </Field>
              )}
              {node.args && (
                <Field label={t("graphInspectArgs")}>
                  <Block text={node.args} />
                </Field>
              )}
              {node.preview && (
                <Field label={t("graphInspectResult")}>
                  <Block text={node.preview} />
                </Field>
              )}
              {node.message && node.status === "error" && (
                <Field label={t("graphInspectError")}>
                  <Block text={node.message} tone="text-destructive" />
                </Field>
              )}
              {node.message && node.status !== "error" && node.kind === "guardrail" && (
                <Field label={t("graphInspectReason")}>
                  <Block text={node.message} />
                </Field>
              )}
              {node.message && node.status !== "error" && node.kind !== "guardrail" && (
                <Field label={t("graphInspectText")}>
                  <Block text={node.message} />
                </Field>
              )}
              {typeof node.ms === "number" && <Field label={t("graphInspectDuration")}>{formatMs(node.ms)}</Field>}
            </dl>
            {node.kind === "tool" && (
              <p className="m-0 text-[11px] leading-relaxed text-muted-foreground">{t("graphPreviewNote")}</p>
            )}
            {node.kind === "user" && fork && (
              <div className="space-y-1.5">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={fork.onFork}
                  disabled={fork.blockedReason != null}
                >
                  <GitFork aria-hidden="true" />
                  {t("graphFork")}
                </Button>
                {fork.blockedReason != null && (
                  <p className="m-0 text-[11px] leading-relaxed text-muted-foreground">{fork.blockedReason}</p>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </section>
  );
}
