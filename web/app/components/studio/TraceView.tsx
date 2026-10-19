"use client";

import { useMemo } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  Search,
  Globe,
  Code2,
  Brain,
  ShieldAlert,
  AlertTriangle,
  CheckCircle2,
  Wrench,
  Sparkles,
  Clock,
} from "lucide-react";
import type { StudioEvent } from "./studioClient";
import { toolLabel } from "./studioContent";

// The live trace: what an agent actually did, in the order it did it. This
// is the page's central argument - a chatbot has nothing to show here, an
// agent has a plan, tool calls with real arguments, real results with real
// latencies, and guardrail decisions. Every row is a server event
// (AgentStepEventEmitter in core), never a client-side animation.

export type TraceRow =
  | { kind: "plan"; key: string; text: string }
  | {
      kind: "tool";
      key: string;
      id: number;
      tool: string;
      args: string;
      preview?: string;
      ms?: number;
      error?: string;
      open: boolean;
    }
  | { kind: "guardrail"; key: string; tool: string; reason: string }
  | { kind: "error"; key: string; message: string }
  | { kind: "finished"; key: string; ms: number };

export function foldTrace(events: StudioEvent[]): TraceRow[] {
  const rows: TraceRow[] = [];
  const byId = new Map<number, TraceRow & { kind: "tool" }>();
  for (const e of events) {
    switch (e.type) {
      case "plan":
        rows.push({ kind: "plan", key: `plan-${rows.length}`, text: e.text });
        break;
      case "tool_call": {
        const row: TraceRow & { kind: "tool" } = {
          kind: "tool",
          key: `tool-${e.id}-${rows.length}`,
          id: e.id,
          tool: e.tool,
          args: e.args,
          open: true,
        };
        byId.set(e.id, row);
        rows.push(row);
        break;
      }
      case "tool_result": {
        const row = byId.get(e.id);
        if (row) {
          row.preview = e.preview;
          row.ms = e.ms;
          row.open = false;
        }
        break;
      }
      case "error": {
        if (e.id != null && byId.has(e.id)) {
          const row = byId.get(e.id)!;
          row.error = e.message;
          row.ms = e.ms;
          row.open = false;
        } else {
          rows.push({ kind: "error", key: `err-${rows.length}`, message: e.message });
        }
        break;
      }
      case "guardrail":
        rows.push({ kind: "guardrail", key: `guard-${rows.length}`, tool: e.tool, reason: e.reason });
        break;
      case "run_finished":
        if (!("status" in e)) rows.push({ kind: "finished", key: "finished", ms: e.ms });
        break;
      default:
        break;
    }
  }
  return rows;
}

function ToolIcon({ tool }: { tool: string }) {
  const cls = "h-4 w-4";
  if (tool === "web_search") return <Search aria-hidden="true" className={cls} />;
  if (tool === "fetch_web_page" || tool === "http_request") return <Globe aria-hidden="true" className={cls} />;
  if (tool === "run_code") return <Code2 aria-hidden="true" className={cls} />;
  if (tool.startsWith("remember") || tool.startsWith("recall") || tool.includes("knowledge"))
    return <Brain aria-hidden="true" className={cls} />;
  return <Wrench aria-hidden="true" className={cls} />;
}

function prettyArgs(args: string): string {
  try {
    const parsed = JSON.parse(args);
    if (parsed && typeof parsed === "object") {
      return Object.entries(parsed)
        .map(([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`)
        .join("\n");
    }
  } catch {
    /* not JSON - show raw */
  }
  return args;
}

export default function TraceView({
  events,
  running,
  emptyHint,
}: {
  events: StudioEvent[];
  running: boolean;
  emptyHint?: string;
}) {
  const rows = useMemo(() => foldTrace(events), [events]);
  const toolCalls = rows.filter((r) => r.kind === "tool").length;

  if (rows.length === 0 && !running) {
    return (
      <div className="rounded-xl border border-dashed border-border/70 p-6 text-sm text-muted-foreground">
        {emptyHint ?? "The agent's working - plan, tool calls, results - shows up here as it happens."}
      </div>
    );
  }

  return (
    <div className="space-y-2" aria-live="polite">
      {toolCalls > 0 && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Sparkles aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
          {toolCalls} tool call{toolCalls === 1 ? "" : "s"} so far - each one is a real request the agent decided to
          make.
        </div>
      )}
      <ol className="relative space-y-2 border-l border-border/60 pl-4">
        <AnimatePresence initial={false}>
          {rows.map((row) => (
            <motion.li
              key={row.key}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.18 }}
              className="relative"
            >
              <span className="absolute -left-[21px] top-2 h-2.5 w-2.5 rounded-full bg-primary/70" aria-hidden />
              {row.kind === "plan" && (
                <div className="rounded-lg bg-muted/50 p-3 text-sm">
                  <div className="mb-1 flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    <Brain aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Plan
                  </div>
                  <p className="whitespace-pre-wrap leading-relaxed">{row.text}</p>
                </div>
              )}
              {row.kind === "tool" && (
                <div
                  className={`rounded-lg border p-3 text-sm ${row.error ? "border-destructive/40 bg-destructive/5" : "border-border/60 bg-background"}`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="inline-flex items-center gap-1.5 font-medium">
                      <ToolIcon tool={row.tool} /> {toolLabel(row.tool)}
                    </span>
                    <code className="rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">{row.tool}</code>
                    {row.ms != null && (
                      <span className="ml-auto inline-flex items-center gap-1 text-xs text-muted-foreground">
                        <Clock aria-hidden="true" className="h-3 w-3 shrink-0" /> {row.ms} ms
                      </span>
                    )}
                    {row.ms == null && !row.error && (
                      <span className="ml-auto text-xs text-muted-foreground animate-pulse">running…</span>
                    )}
                  </div>
                  <pre className="mt-2 max-h-28 overflow-auto whitespace-pre-wrap rounded bg-muted/40 p-2 text-xs text-muted-foreground">
                    {prettyArgs(row.args)}
                  </pre>
                  {row.preview && (
                    <div className="mt-2 text-xs">
                      <div className="mb-1 font-medium text-muted-foreground">Result</div>
                      <pre className="max-h-32 overflow-auto whitespace-pre-wrap rounded bg-muted/40 p-2 text-muted-foreground">
                        {row.preview}
                      </pre>
                    </div>
                  )}
                  {row.error && (
                    <div className="mt-2 flex items-start gap-2 text-xs text-destructive">
                      <AlertTriangle aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0" /> {row.error}
                    </div>
                  )}
                </div>
              )}
              {row.kind === "guardrail" && (
                <div className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
                  <ShieldAlert aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                  <div>
                    <div className="font-medium">Guardrail stepped in</div>
                    <div className="text-xs text-muted-foreground">
                      {row.tool}: {row.reason}
                    </div>
                  </div>
                </div>
              )}
              {row.kind === "error" && (
                <div className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/5 p-3 text-sm">
                  <AlertTriangle aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
                  <div>{row.message}</div>
                </div>
              )}
              {row.kind === "finished" && (
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <CheckCircle2 aria-hidden="true" className="h-3.5 w-3.5 text-emerald-600 shrink-0" /> Finished in{" "}
                  {(row.ms / 1000).toFixed(1)} s
                </div>
              )}
            </motion.li>
          ))}
        </AnimatePresence>
        {running && rows.length === 0 && (
          <li className="text-sm text-muted-foreground animate-pulse">Reading the mission…</li>
        )}
      </ol>
    </div>
  );
}
