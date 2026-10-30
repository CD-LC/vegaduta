"use client";

import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import {
  Braces,
  ChevronLeft,
  ChevronRight,
  Coins,
  ExternalLink,
  Gauge,
  ScrollText,
  Send,
  Wrench,
  X,
  type LucideIcon,
} from "lucide-react";
import "./runInspectorStyles";
import "./run-inspector-tools.css";
import "./pins.css";
import PinOutputButton from "./PinOutputButton";
import { classifyLine, intFmt, isNum } from "./inspector/format";
import { normalizePayload } from "./inspector/payload";
import { STATUS_META, statusKey } from "./inspector/status";
import type { LevelFilter, RunStepInspectorProps, TabKey } from "./inspector/types";
import { useCopy } from "./inspector/useCopy";
import { useInspectorAnchor } from "./inspector/useInspectorAnchor";
import { FlightStrip, VisitRibbon } from "./inspector/Timeline";
import { SummaryPanel } from "./inspector/SummaryPanel";
import { LogsPanel } from "./inspector/LogsPanel";
import { ToolsPanel } from "./inspector/ToolsPanel";
import { DataPanel } from "./inspector/DataPanel";
import { CostPanel } from "./inspector/CostPanel";
import { SentPanel } from "./inspector/SentPanel";
import { useTranslations } from "../../lib/i18n";

// The "black box" card for one node visit during a run or replay. It reads a
// single trace visit and never fetches (except the "Sent" tab): the canvas owns
// run polling and visit selection, this card only renders what it is handed.
// Parts live in ./inspector/: timeline (visit ribbon + flight strip), detail
// panels (summary / logs / tools / cost / sent) and the payload tree (data).

export type { InspectorVisit, InspectorToolCall, InspectorAnchor, RunStepInspectorProps } from "./inspector/types";

export default function RunStepInspector({
  visit,
  visitCount,
  onPrevVisit,
  onNextVisit,
  onClose,
  onOpenFullRun,
  anchor,
  runSpan,
  pinRunId,
  workflowId,
}: RunStepInspectorProps) {
    const tHC = useTranslations("hcComponentsWorkflowRunStepInspector");
  const uid = useId();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [copied, copy] = useCopy();

  // Captured once so focus goes back to whatever opened the card (usually the node).
  const [returnFocusTo] = useState<Element | null>(() =>
    typeof document === "undefined" ? null : document.activeElement
  );
  useEffect(() => {
    const el = rootRef.current;
    el?.focus({ preventScroll: true });
    return () => {
      // Only hand focus back if it was still ours; a click elsewhere (e.g. on
      // another node) that closed the card must keep its own focus.
      const active = document.activeElement;
      const ours = !active || active === document.body || (el?.contains(active) ?? false);
      if (ours && returnFocusTo instanceof HTMLElement && returnFocusTo.isConnected) {
        returnFocusTo.focus({ preventScroll: true });
      }
    };
  }, [returnFocusTo]);

  const sKey = statusKey(visit.status);
  const meta = STATUS_META[sKey];
  const isLive = sKey === "running" && !isNum(visit.endMs);

  // Live stopwatch only while this visit is still open.
  const [now, setNow] = useState(() => Date.now());
  const runOpen = isLive || (runSpan != null && !isNum(runSpan.endMs));
  useEffect(() => {
    if (!runOpen) return undefined;
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, [runOpen]);

  const durationMs = isNum(visit.durationMs)
    ? visit.durationMs
    : isNum(visit.startMs) && isNum(visit.endMs)
      ? visit.endMs - visit.startMs
      : isLive && isNum(visit.startMs)
        ? now - visit.startMs
        : null;

  const logs = useMemo(
    () => (visit.logLines ?? []).map((text, i) => ({ n: i + 1, text, level: classifyLine(text) })),
    [visit.logLines]
  );
  const logCounts = useMemo(() => {
    const c = { error: 0, warn: 0, info: 0 };
    for (const l of logs) c[l.level] += 1;
    return c;
  }, [logs]);

  const input = useMemo(() => normalizePayload(visit.inputJson), [visit.inputJson]);
  const output = useMemo(() => normalizePayload(visit.outputJson), [visit.outputJson]);
  const hasCost = isNum(visit.tokensIn) || isNum(visit.tokensOut) || isNum(visit.costUsd);

  // Only agent-like nodes call a model through the advisor chain the snapshots
  // come from; the tab needs the workflow + run ids to know where to ask.
  const hasSent = !!workflowId && !!pinRunId && /agent|supervisor|llm/i.test(visit.nodeType ?? "");
  const toolCalls = visit.toolCalls ?? null;
  const hasTools = !!toolCalls && toolCalls.length > 0;
  const tabs = useMemo<TabKey[]>(() => {
    const out: TabKey[] = ["summary", "logs"];
    if (hasTools) out.push("tools");
    out.push("data");
    if (hasCost) out.push("cost");
    if (hasSent) out.push("sent");
    return out;
  }, [hasCost, hasTools, hasSent]);
  const [tabState, setTab] = useState<TabKey>("summary");
  const tab: TabKey = tabs.includes(tabState) ? tabState : "summary";
  const [levelFilter, setLevelFilter] = useState<LevelFilter>("all");
  const [query, setQuery] = useState("");

  /* ---------- keyboard: Esc closes, [ and ] step through visits */
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      e.preventDefault();
      onClose();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const canPrev = visit.visitIndex > 0;
  const canNext = visit.visitIndex < visitCount - 1;
  function onCardKey(e: ReactKeyboardEvent<HTMLDivElement>) {
    const t = e.target as HTMLElement;
    if (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === "[" && canPrev) {
      e.preventDefault();
      onPrevVisit();
    } else if (e.key === "]" && canNext) {
      e.preventDefault();
      onNextVisit();
    }
  }

  /* ---------- node anchoring (desktop only; CSS turns it into a sheet on phones) */
  const pos = useInspectorAnchor(rootRef, anchor);

  /* ---------- tabs: roving arrows per WAI-ARIA tabs pattern */
  const tabRefs = useRef<Partial<Record<TabKey, HTMLButtonElement | null>>>({});
  function onTabKey(e: ReactKeyboardEvent<HTMLDivElement>) {
    const i = tabs.indexOf(tab);
    let next: number | null = null;
    if (e.key === "ArrowRight") next = (i + 1) % tabs.length;
    else if (e.key === "ArrowLeft") next = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = tabs.length - 1;
    if (next === null) return;
    e.preventDefault();
    const k = tabs[next];
    setTab(k);
    tabRefs.current[k]?.focus();
  }

  const jumpToLogs = (f: LevelFilter) => {
    setLevelFilter(f);
    setTab("logs");
  };

  const title = visit.label?.trim() || visit.nodeType || visit.nodeId;
  const visitLabel = `Visit ${visit.visitIndex + 1} of ${Math.max(visitCount, 1)}`;
  const tabMeta: Record<TabKey, { label: string; Icon: LucideIcon; badge?: string }> = {
    summary: { label: "Summary", Icon: Gauge },
    logs: { label: "Logs", Icon: ScrollText, badge: logs.length ? intFmt.format(logs.length) : undefined },
    tools: { label: "Tools", Icon: Wrench, badge: toolCalls?.length ? intFmt.format(toolCalls.length) : undefined },
    data: { label: "Data", Icon: Braces },
    cost: { label: "Cost", Icon: Coins },
    sent: { label: "Sent", Icon: Send },
  };

  const style = pos
    ? ({
        "--ri-left": `${pos.left}px`,
        "--ri-top": `${pos.top}px`,
        "--ri-notch-y": `${pos.notch}px`,
      } as CSSProperties)
    : undefined;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- non-modal dialog: Escape handling bubbles from focusable children
    <div
      ref={rootRef}
      className="wfx-ri"
      role="dialog"
      aria-modal="false"
      aria-labelledby={`${uid}-title`}
      aria-describedby={`${uid}-status`}
      tabIndex={-1}
      data-status={sKey}
      data-anchored={pos ? "true" : undefined}
      data-side={pos?.side}
      style={style}
      onKeyDown={onCardKey}
    >
      <span className="ri-notch" aria-hidden="true" />
      <span className="ri-stripe" aria-hidden="true" />
      <span className="ri-grip" aria-hidden="true" />

      <header className="ri-head">
        <div className="ri-titlerow">
          <span className="ri-status-ico" aria-hidden="true">
            <meta.Icon size={16} className={sKey === "running" ? "ri-spin" : undefined} />
          </span>
          <h2 id={`${uid}-title`} className="ri-title" title={title}>
            {title}
          </h2>
          <button type="button" className="ri-iconbtn" onClick={onClose} aria-label={tHC("s7")}>
            <X size={15} aria-hidden="true" />
          </button>
        </div>
        <div className="ri-subrow">
          <span id={`${uid}-status`} className="ri-pill" data-status={sKey}>
            {meta.label}
          </span>
          {visit.nodeType ? <span className="ri-type">{visit.nodeType}</span> : null}
          {visitCount > 1 ? (
            <div className="ri-visits" role="group" aria-label={tHC("s6")}>
              <button
                type="button"
                className="ri-iconbtn ri-sm"
                onClick={onPrevVisit}
                disabled={!canPrev}
                aria-label={tHC("s5")}
              >
                <ChevronLeft size={14} aria-hidden="true" />
              </button>
              <VisitRibbon index={visit.visitIndex} count={visitCount} />
              <span className="ri-visit-text">
                {visit.visitIndex + 1}/{visitCount}
              </span>
              <button
                type="button"
                className="ri-iconbtn ri-sm"
                onClick={onNextVisit}
                disabled={!canNext}
                aria-label={tHC("s4")}
              >
                <ChevronRight size={14} aria-hidden="true" />
              </button>
            </div>
          ) : null}
        </div>
        <FlightStrip visit={visit} runSpan={runSpan} now={now} durationMs={durationMs} />
      </header>

      {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the tabs; arrow keys bubble to the tablist */}
      <div className="ri-tabs" role="tablist" aria-label={tHC("s3")} onKeyDown={onTabKey}>
        {tabs.map((k) => {
          const t = tabMeta[k];
          return (
            <button
              key={k}
              ref={(n) => {
                tabRefs.current[k] = n;
              }}
              type="button"
              role="tab"
              id={`${uid}-tab-${k}`}
              aria-selected={tab === k}
              aria-controls={`${uid}-panel`}
              tabIndex={tab === k ? 0 : -1}
              className="ri-tab"
              onClick={() => setTab(k)}
            >
              <t.Icon size={13} aria-hidden="true" />
              <span>{t.label}</span>
              {t.badge ? <span className="ri-badge">{t.badge}</span> : null}
            </button>
          );
        })}
      </div>

      <div
        key={`${visit.nodeId}:${visit.visitIndex}:${tab}`}
        id={`${uid}-panel`}
        role="tabpanel"
        aria-labelledby={`${uid}-tab-${tab}`}
        className="ri-body"
        tabIndex={0}
      >
        {tab === "summary" ? (
          <SummaryPanel
            visit={visit}
            sKey={sKey}
            durationMs={durationMs}
            isLive={isLive}
            visitLabel={visitLabel}
            logCounts={logCounts}
            onJump={jumpToLogs}
          />
        ) : null}
        {tab === "logs" ? (
          <LogsPanel
            logs={logs}
            counts={logCounts}
            level={levelFilter}
            onLevel={setLevelFilter}
            query={query}
            onQuery={setQuery}
            live={isLive}
            copied={copied}
            onCopy={copy}
          />
        ) : null}
        {tab === "tools" && toolCalls && toolCalls.length ? (
          <ToolsPanel calls={toolCalls} copied={copied} onCopy={copy} />
        ) : null}
        {tab === "data" ? (
          <DataPanel
            input={input}
            output={output}
            inputTruncated={!!visit.inputTruncated}
            outputTruncated={!!visit.outputTruncated}
            copied={copied}
            onCopy={copy}
            pinControl={
              <PinOutputButton
                nodeId={visit.nodeId}
                data={
                  typeof visit.outputJson === "string" ? visit.outputJson : output.kind === "none" ? null : output.text
                }
                source="run"
                sourceRunId={pinRunId}
                truncated={visit.outputTruncated}
                label="this"
              />
            }
          />
        ) : null}
        {tab === "cost" ? <CostPanel visit={visit} durationMs={durationMs} /> : null}
        {tab === "sent" && hasSent ? (
          <SentPanel
            workflowId={workflowId as string}
            runId={pinRunId as string}
            startMs={visit.startMs}
            endMs={visit.endMs}
          />
        ) : null}
      </div>

      <footer className="ri-foot">
        <span className="ri-keys" aria-hidden="true">
          {visitCount > 1 ? (
            <>
              <kbd>[</kbd>
              <kbd>]</kbd> {tHC("s2")}{" "}
            </>
          ) : null}
          <kbd>Esc</kbd> close
        </span>
        {onOpenFullRun ? (
          <button type="button" className="ri-btn" onClick={onOpenFullRun}>
            {tHC("s1")}
            <ExternalLink size={13} aria-hidden="true" />
          </button>
        ) : null}
      </footer>

      <div className="ri-sr" aria-live="polite">
        {`${title}, ${visitLabel}, ${meta.label}`}
        {copied ? ". Copied to clipboard" : ""}
      </div>
    </div>
  );
}
