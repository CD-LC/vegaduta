"use client";

import { memo, useEffect, useId, useMemo, useRef, useState } from "react";
import { Check, ChevronRight, Loader2, Maximize2, Wrench, X } from "lucide-react";
import "./node-live.css";

// Live "agent thinking" strip rendered inside an agent/supervisor node card.
// The parent re-renders on every node.token frame, so everything here is
// shallow-cheap: the text is tail-sliced, args are only serialised once a row
// is expanded, and rows are individually memo'd.

export type NodeLiveToolStatus = "running" | "ok" | "error";

export interface NodeLiveToolCall {
  id: string;
  tool: string;
  args?: unknown;
  preview?: string;
  error?: string;
  ms?: number;
  status: NodeLiveToolStatus;
}

export interface NodeLivePanelProps {
  text?: string;
  streaming: boolean;
  /** Server stopped forwarding tokens (node.token {capped:true}). */
  capped?: boolean;
  toolCalls?: NodeLiveToolCall[];
  /** Semantic zoom: below 0.5 the node is a pill, render only a chip. */
  compact: boolean;
  /** Opens the full step inspector for this node. */
  onOpen?: () => void;
}

export interface ToolCallSummary {
  running: number;
  ok: number;
  error: number;
  totalMs: number;
}

/** Pure tally for chips/headers; ignores missing or non-finite durations. */
export function toolCallSummary(calls: readonly Pick<NodeLiveToolCall, "status" | "ms">[] | null | undefined): ToolCallSummary {
  const out: ToolCallSummary = { running: 0, ok: 0, error: 0, totalMs: 0 };
  if (!calls) return out;
  for (const c of calls) {
    if (c.status === "running") out.running += 1;
    else if (c.status === "ok") out.ok += 1;
    else if (c.status === "error") out.error += 1;
    if (typeof c.ms === "number" && Number.isFinite(c.ms) && c.ms > 0) out.totalMs += c.ms;
  }
  return out;
}

/** "320 ms" / "1.4 s" / "2.1 min", locale-formatted. */
export function formatToolMs(ms: number | undefined): string {
  if (typeof ms !== "number" || !Number.isFinite(ms) || ms < 0) return "";
  if (ms < 1000) return `${MS_FMT.format(Math.round(ms))} ms`;
  if (ms < 60_000) return `${SEC_FMT.format(ms / 1000)} s`;
  return `${SEC_FMT.format(ms / 60_000)} min`;
}

const MS_FMT = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const SEC_FMT = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });

const MAX_VISIBLE_TOOLS = 4;
// Only the last few hundred characters can ever be visible in a 3-line,
// 232px-wide box; slicing keeps layout cost flat as the stream grows.
const TAIL_CHARS = 480;
const EXPANDED_ARGS_CAP = 8000;

const STATUS_TEXT: Record<NodeLiveToolStatus, string> = {
  running: "running",
  ok: "done",
  error: "failed",
};

function stringifyArgs(args: unknown): string {
  if (args === undefined || args === null) return "";
  if (typeof args === "string") return args;
  try {
    const s = JSON.stringify(args, null, 2);
    return s ?? String(args);
  } catch {
    return String(args);
  }
}

function capText(s: string, cap: number): string {
  return s.length > cap ? `${s.slice(0, cap)}\n… (${s.length - cap} more chars)` : s;
}

function StatusIcon({ status }: { status: NodeLiveToolStatus }) {
  if (status === "running") return <Loader2 size={11} className="nlp-spin" aria-hidden="true" />;
  if (status === "ok") return <Check size={11} aria-hidden="true" />;
  return <X size={11} aria-hidden="true" />;
}

const ExpandedDetail = memo(function ExpandedDetail({ call }: { call: NodeLiveToolCall }) {
  // Serialised only while expanded; memo keys on the args reference.
  const argsText = useMemo(() => capText(stringifyArgs(call.args), EXPANDED_ARGS_CAP), [call.args]);
  const result = call.error ?? call.preview ?? "";
  return (
    <div className="nlp-detail nowheel">
      {argsText && (
        <>
          <div className="nlp-detail-label">Arguments</div>
          <pre className="nlp-pre">{argsText}</pre>
        </>
      )}
      {result && (
        <>
          <div className="nlp-detail-label">{call.error ? "Error" : "Result preview"}</div>
          <pre className="nlp-pre" data-error={call.error ? "true" : undefined}>
            {capText(result, EXPANDED_ARGS_CAP)}
          </pre>
        </>
      )}
      {!argsText && !result && <div className="nlp-detail-empty">No arguments or output yet.</div>}
    </div>
  );
});

interface ToolRowProps {
  call: NodeLiveToolCall;
  expanded: boolean;
  onToggle: (id: string) => void;
}

const ToolRow = memo(function ToolRow({ call, expanded, onToggle }: ToolRowProps) {
  const detailId = useId();
  const line = call.error ?? call.preview ?? "";
  const ms = formatToolMs(call.ms);
  return (
    <li className="nlp-tool" data-status={call.status}>
      <button
        type="button"
        className="nlp-tool-btn nodrag"
        aria-expanded={expanded}
        aria-controls={detailId}
        onClick={(e) => {
          // Don't let the click select/open the node underneath.
          e.stopPropagation();
          onToggle(call.id);
        }}
      >
        <ChevronRight size={11} className="nlp-chev" aria-hidden="true" />
        <Wrench size={11} className="nlp-tool-icon" aria-hidden="true" />
        <span className="nlp-tool-name" title={call.tool}>{call.tool}</span>
        <span className="nlp-tool-status">
          <StatusIcon status={call.status} />
          <span>{STATUS_TEXT[call.status]}</span>
        </span>
        {ms && <span className="nlp-tool-ms">{ms}</span>}
      </button>
      {line && !expanded && (
        <div className="nlp-tool-line" data-error={call.error ? "true" : undefined} title={line.length > 200 ? line.slice(0, 200) : line}>
          {line.length > 160 ? line.slice(0, 160) : line}
        </div>
      )}
      <div id={detailId} hidden={!expanded}>
        {expanded && <ExpandedDetail call={call} />}
      </div>
    </li>
  );
});

function NodeLivePanelImpl({ text, streaming, capped, toolCalls, compact, onOpen }: NodeLivePanelProps) {
  const calls = toolCalls ?? [];
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [showAll, setShowAll] = useState(false);
  const [announce, setAnnounce] = useState("");
  const wasStreaming = useRef(streaming);

  // Announce completion once (the stream itself is aria-live="off").
  useEffect(() => {
    if (wasStreaming.current && !streaming) setAnnounce("Agent response complete.");
    else if (streaming) setAnnounce("");
    wasStreaming.current = streaming;
  }, [streaming]);

  const toggle = useMemo(
    () => (id: string) =>
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      }),
    [],
  );

  const summary = toolCallSummary(calls);

  if (compact) {
    if (calls.length === 0 && !streaming) return null;
    const label = calls.length === 1 ? "1 tool" : `${calls.length} tools`;
    return (
      <span
        className="nlp-chip"
        data-state={summary.error > 0 ? "error" : summary.running > 0 || streaming ? "running" : "ok"}
        aria-label={`${label}${summary.running ? `, ${summary.running} running` : ""}${summary.error ? `, ${summary.error} failed` : ""}`}
      >
        {summary.running > 0 || streaming ? <Loader2 size={9} className="nlp-spin" aria-hidden="true" /> : <Wrench size={9} aria-hidden="true" />}
        {calls.length > 0 ? label : "thinking"}
      </span>
    );
  }

  const tail = text ? (text.length > TAIL_CHARS ? text.slice(text.length - TAIL_CHARS) : text) : "";
  const hasText = tail.length > 0;
  if (!hasText && calls.length === 0 && !streaming) return null;

  const visible = showAll ? calls : calls.slice(0, MAX_VISIBLE_TOOLS);
  const hidden = calls.length - visible.length;

  return (
    <div className="nlp-root" data-streaming={streaming ? "true" : undefined}>
      <div className="nlp-head">
        <span className="nlp-head-label">
          {streaming ? (
            <>
              <span className="nlp-live-dot" aria-hidden="true" />
              Thinking
            </>
          ) : hasText ? (
            "Output"
          ) : (
            "Tool calls"
          )}
          {capped && <span className="nlp-capped" title="Live preview limit reached; the full output is in the step inspector">preview capped</span>}
        </span>
        {onOpen && (
          <button
            type="button"
            className="nlp-open nodrag"
            aria-label="Open step details"
            title="Open step details"
            onClick={(e) => {
              e.stopPropagation();
              onOpen();
            }}
          >
            <Maximize2 size={11} aria-hidden="true" />
          </button>
        )}
      </div>

      {(hasText || streaming) && (
        <div className="nlp-stream" aria-live="off" aria-label="Streamed agent output">
          <div className="nlp-stream-inner">
            <span className="nlp-text">{hasText ? tail : ""}</span>
            {streaming && !capped && <span className="nlp-caret" aria-hidden="true" />}
          </div>
        </div>
      )}

      {calls.length > 0 && (
        <ul className="nlp-tools" aria-label={`Tool calls: ${summary.ok} done, ${summary.running} running, ${summary.error} failed`}>
          {visible.map((c) => (
            <ToolRow key={c.id} call={c} expanded={expanded.has(c.id)} onToggle={toggle} />
          ))}
          {(hidden > 0 || showAll) && calls.length > MAX_VISIBLE_TOOLS && (
            <li>
              <button
                type="button"
                className="nlp-more nodrag"
                aria-expanded={showAll}
                onClick={(e) => {
                  e.stopPropagation();
                  setShowAll((v) => !v);
                }}
              >
                {showAll ? "Show fewer" : `+${hidden} more`}
              </button>
            </li>
          )}
        </ul>
      )}

      <span className="nlp-sr" role="status" aria-live="polite">{announce}</span>
    </div>
  );
}

export const NodeLivePanel = memo(NodeLivePanelImpl);
export default NodeLivePanel;
