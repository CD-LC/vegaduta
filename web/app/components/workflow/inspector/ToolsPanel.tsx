import { useState } from "react";
import { CheckCircle2, ChevronRight, Wrench, XCircle } from "lucide-react";
import { formatDuration, intFmt, isNum } from "./format";
import { normalizePayload } from "./payload";
import { PayloadSection } from "./PayloadSection";
import type { InspectorToolCall } from "./types";

export function ToolsPanel({ calls, copied, onCopy }: {
  calls: readonly InspectorToolCall[]; copied: string | null; onCopy: (id: string, text: string) => void;
}) {
  // One open row at a time keeps the fixed-height card scannable.
  const [open, setOpen] = useState<number | null>(calls.length === 1 ? 0 : null);
  const failed = calls.filter((c) => !!c.error).length;
  return (
    <div className="ri-tools">
      <p className="ri-tools-sum">
        {intFmt.format(calls.length)} tool call{calls.length === 1 ? "" : "s"}
        {failed ? `, ${intFmt.format(failed)} failed` : ""}
      </p>
      <ol className="ri-tools-list">
        {calls.map((c, i) => {
          const isOpen = open === i;
          const status = c.error ? "error" : "ok";
          const panelId = `ri-tool-${i}`;
          return (
            <li key={i} className="ri-tool" data-status={status}>
              <button
                type="button"
                className="ri-tool-row"
                aria-expanded={isOpen}
                aria-controls={panelId}
                onClick={() => setOpen(isOpen ? null : i)}
              >
                <ChevronRight size={12} aria-hidden="true" className="ri-chev" />
                <Wrench size={12} aria-hidden="true" />
                <span className="ri-tool-name" title={c.tool}>{c.tool}</span>
                <span className="ri-tool-state">
                  {c.error ? <XCircle size={12} aria-hidden="true" /> : <CheckCircle2 size={12} aria-hidden="true" />}
                  {c.error ? "Failed" : "Done"}
                </span>
                {isNum(c.durationMs) ? <span className="ri-num ri-tool-ms">{formatDuration(c.durationMs)}</span> : null}
              </button>
              {isOpen ? (
                <div id={panelId} className="ri-tool-body">
                  <PayloadSection id={`tool${i}-in`} title="Arguments" payload={normalizePayload(c.input)} truncated={false} copied={copied} onCopy={onCopy} />
                  {c.error ? (
                    <section className="ri-payload" aria-label="Error">
                      <div className="ri-payload-head"><h3>Error</h3></div>
                      <pre className="ri-raw ri-tool-err">{c.error}</pre>
                    </section>
                  ) : (
                    <PayloadSection id={`tool${i}-out`} title="Result" payload={normalizePayload(c.output)} truncated={false} copied={copied} onCopy={onCopy} />
                  )}
                </div>
              ) : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
