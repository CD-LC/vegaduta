"use client";

// "Run this step" - test ONE node of the saved graph with typed input or
// pinned (optionally mocked) upstream outputs, and see status, timing,
// tokens/cost, output and tool calls. Nothing it does becomes a workflow run.
// Feature-detected: when the server lacks the endpoint the panel reports it
// once via onUnavailable and renders nothing from then on.

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { formatCostUsd, formatDuration } from "./runModel";
import {
  buildTestBody,
  loadMocks,
  setMock,
  stepStatusKey,
  testWorkflowNode,
  toNumberOrNull,
  untestableReason,
  type MockMap,
  type StepTestEntry,
  type StepTestResult,
  type StepToolCall,
} from "./stepTest";
import PinOutputButton from "./PinOutputButton";
import "./step-test.css";
import { useTranslations } from "../../lib/i18n";

export interface StepTestNode {
  id: string;
  type: string;
  label?: string | null;
  config?: Record<string, unknown> | null;
}

export interface StepTestUpstream {
  id: string;
  label: string;
  /** That node's output from the last run, used to prefill its pinned input. */
  lastOutput?: string;
}

export interface StepTestPanelProps {
  workflowId: string;
  node: StepTestNode;
  /** Direct upstream nodes in edge order (see upstreamIds) - the first filled one feeds the test. */
  upstream: StepTestUpstream[];
  /** Canvas has unsaved edits; the test runs the SAVED graph. */
  dirty: boolean;
  /** This node's input from the last run, to prefill the input box. */
  lastInput?: string;
  onResult?: (entry: StepTestEntry) => void;
  /** Server has no test endpoint (older core) - the host should hide its entry point. */
  onUnavailable?: () => void;
  onClose?: () => void;
}

const intFmt = new Intl.NumberFormat();

const STATUS_TEXT: Record<ReturnType<typeof stepStatusKey>, { label: string; glyph: string }> = {
  ok: { label: "Succeeded", glyph: "✓" },
  error: { label: "Failed", glyph: "✕" },
  skipped: { label: "Skipped", glyph: "–" },
  approval: { label: "Waiting", glyph: "‖" },
  other: { label: "Finished", glyph: "•" },
};

function isMac(): boolean {
  return typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");
}

function prettyText(s: string): string {
  const t = s.trim();
  if (!(t.startsWith("{") || t.startsWith("["))) return s;
  try {
    return JSON.stringify(JSON.parse(t), null, 2);
  } catch {
    return s;
  }
}

function initialPinned(upstream: StepTestUpstream[], mocks: MockMap): Record<string, string> {
  const out: Record<string, string> = {};
  for (const u of upstream) out[u.id] = mocks[u.id] ?? u.lastOutput ?? "";
  return out;
}

export default function StepTestPanel({
  workflowId,
  node,
  upstream,
  dirty,
  lastInput,
  onResult,
  onUnavailable,
  onClose,
}: StepTestPanelProps) {
  const tHC = useTranslations("hcComponentsWorkflowStepTestPanel");
  const uid = useId();
  const reason = useMemo(() => untestableReason(node.type, node.config), [node.type, node.config]);

  const [mocks, setMocks] = useState<MockMap>(() => loadMocks(workflowId));
  const [input, setInput] = useState(lastInput ?? "");
  const [pinned, setPinned] = useState<Record<string, string>>(() => initialPinned(upstream, mocks));
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<StepTestResult | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [copied, setCopied] = useState(false);
  const [announce, setAnnounce] = useState("");

  // Reset everything when the selected node changes (React's "adjust state
  // on prop change" pattern - no effect round-trip, no stale flash).
  const [forNode, setForNode] = useState(`${workflowId}/${node.id}`);
  const nodeKey = `${workflowId}/${node.id}`;
  if (forNode !== nodeKey) {
    const m = loadMocks(workflowId);
    setForNode(nodeKey);
    setMocks(m);
    setInput(lastInput ?? "");
    setPinned(initialPinned(upstream, m));
    setResult(null);
    setRunning(false);
    setCopied(false);
    setAnnounce("");
  }

  // New upstream nodes (edge added while open) get a prefilled slot; edited slots are kept.
  useEffect(() => {
    setPinned((prev) => {
      let changed = false;
      const next = { ...prev };
      for (const u of upstream) {
        if (!(u.id in next)) {
          next[u.id] = mocks[u.id] ?? u.lastOutput ?? "";
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [upstream, mocks]);

  const abortRef = useRef<AbortController | null>(null);
  // Cleanup runs on node switch and on unmount: a late answer for the old node must not land.
  useEffect(() => () => abortRef.current?.abort(), [nodeKey]);

  const run = useCallback(async () => {
    if (running || reason) return;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setRunning(true);
    setResult(null);
    setCopied(false);
    setAnnounce("Running this step…");
    const r = await testWorkflowNode(workflowId, node.id, buildTestBody(input, pinned), ctrl.signal);
    if (ctrl.signal.aborted) return;
    setRunning(false);
    if (!r.ok && r.kind === "unsupported") {
      setUnavailable(true);
      onUnavailable?.();
      return;
    }
    setResult(r);
    if (r.ok) {
      const key = stepStatusKey(r.entry.status);
      const d = toNumberOrNull(r.entry.durationMs);
      setAnnounce(`Step test ${STATUS_TEXT[key].label.toLowerCase()}${d != null ? ` in ${formatDuration(d)}` : ""}.`);
      onResult?.(r.entry);
    } else {
      setAnnounce(`Step test could not run: ${r.message}`);
    }
  }, [running, reason, workflowId, node.id, input, pinned, onResult, onUnavailable]);

  const onKeyDown = useCallback(
    (e: ReactKeyboardEvent<HTMLElement>) => {
      if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        void run();
      } else if (e.key === "Escape" && onClose) {
        e.stopPropagation();
        onClose();
      }
    },
    [run, onClose]
  );

  const saveMock = useCallback(
    (id: string) => {
      setMocks(setMock(workflowId, id, pinned[id] ?? ""));
    },
    [workflowId, pinned]
  );

  const forgetMock = useCallback(
    (id: string) => {
      const next = setMock(workflowId, id, null);
      setMocks(next);
      const u = upstream.find((x) => x.id === id);
      setPinned((p) => ({ ...p, [id]: u?.lastOutput ?? "" }));
    },
    [workflowId, upstream]
  );

  const copyOutput = useCallback((text: string) => {
    const done = () => {
      setCopied(true);
      setAnnounce("Output copied to clipboard.");
    };
    try {
      void navigator.clipboard?.writeText(text).then(done, () => setCopied(false));
    } catch {
      setCopied(false);
    }
  }, []);

  if (unavailable) return null;

  const title = node.label?.trim() || node.type;
  const shortcut = isMac() ? "⌘↵" : "Ctrl+Enter";
  const inputWins = input.trim() !== "";
  const firstFilled = upstream.find((u) => (pinned[u.id] ?? "").trim() !== "")?.id;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- Ctrl+Enter / Escape shortcuts bubble from the panel's focusable fields
    <section className="wfx-st" aria-labelledby={`${uid}-title`} onKeyDown={onKeyDown}>
      <header className="st-head">
        <div className="st-titlerow">
          <span className="st-kicker">{tHC("s11")}</span>
          {onClose ? (
            <button type="button" className="st-iconbtn" onClick={onClose} aria-label={tHC("s10")}>
              <span aria-hidden="true">×</span>
            </button>
          ) : null}
        </div>
        <h2 id={`${uid}-title`} className="st-title" title={title}>
          {title} <span className="st-type">{node.type}</span>
        </h2>
      </header>

      {reason ? (
        <div className="st-blocked" role="note">
          <span className="st-blocked-ico" aria-hidden="true">
            ⊘
          </span>
          <div>
            <p className="st-blocked-title">{tHC("s9")}</p>
            <p className="st-blocked-text">{reason}</p>
          </div>
        </div>
      ) : (
        <div className="st-body">
          {dirty ? (
            <p className="st-warn" role="note">
              <span aria-hidden="true">!</span> {tHC("s8")} <strong>saved</strong> {tHC("s7")}
            </p>
          ) : null}

          <div className="st-field">
            <label htmlFor={`${uid}-input`} className="st-label">
              Input
              <span className="st-sub">
                {upstream.length ? "optional - overrides pinned inputs" : "what this step receives"}
              </span>
            </label>
            <textarea
              id={`${uid}-input`}
              className="st-textarea"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              rows={4}
              spellCheck={false}
              placeholder={
                upstream.length ? "Leave empty to use the pinned inputs below" : "Type the input for this step"
              }
            />
          </div>

          {upstream.length ? (
            <fieldset className="st-pins" aria-describedby={`${uid}-pinhint`}>
              <legend className="st-label">{tHC("s6")}</legend>
              <p id={`${uid}-pinhint`} className="st-hint">
                {inputWins
                  ? "Ignored while the Input box has text."
                  : "The first filled one (top to bottom) is used. Mocks stay in this browser and are never sent on real runs."}
              </p>
              {upstream.map((u) => {
                const value = pinned[u.id] ?? "";
                const saved = mocks[u.id];
                const isMock = saved != null && saved === value;
                const edited = saved != null ? saved !== value : value !== (u.lastOutput ?? "");
                const used = !inputWins && firstFilled === u.id;
                const fid = `${uid}-pin-${u.id}`;
                return (
                  <div key={u.id} className="st-pin" data-used={used ? "true" : undefined}>
                    <div className="st-pin-head">
                      <label htmlFor={fid} className="st-pin-label" title={u.label}>
                        {u.label}
                      </label>
                      {isMock ? <span className="st-badge st-badge-mock">MOCK</span> : null}
                      {!isMock && !edited && u.lastOutput ? <span className="st-badge">{tHC("s5")}</span> : null}
                      {edited ? <span className="st-badge">edited</span> : null}
                      {used ? <span className="st-badge st-badge-used">used</span> : null}
                    </div>
                    <textarea
                      id={fid}
                      className="st-textarea st-mono"
                      value={value}
                      onChange={(e) => setPinned((p) => ({ ...p, [u.id]: e.target.value }))}
                      rows={3}
                      spellCheck={false}
                      placeholder={tHC("s4")}
                    />
                    <div className="st-pin-actions">
                      <button
                        type="button"
                        className="st-link"
                        onClick={() => saveMock(u.id)}
                        disabled={value.trim() === "" || isMock}
                      >
                        {saved != null && !isMock ? "Update mock" : "Save as mock"}
                      </button>
                      {saved != null ? (
                        <button type="button" className="st-link" onClick={() => forgetMock(u.id)}>
                          {tHC("s3")}
                        </button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </fieldset>
          ) : null}

          <div className="st-runrow">
            <button
              type="button"
              className="st-run"
              onClick={() => void run()}
              disabled={running}
              aria-busy={running || undefined}
              aria-keyshortcuts="Control+Enter Meta+Enter"
            >
              {running ? <span className="st-spinner" aria-hidden="true" /> : <span aria-hidden="true">▶</span>}
              {running ? "Running…" : "Run this step"}
            </button>
            <kbd className="st-kbd" aria-hidden="true">
              {shortcut}
            </kbd>
          </div>

          {result ? <ResultView nodeId={node.id} result={result} copied={copied} onCopy={copyOutput} /> : null}
        </div>
      )}

      <div className="st-sr" role="status" aria-live="polite">
        {announce}
      </div>
    </section>
  );
}

function ResultView({
  nodeId,
  result,
  copied,
  onCopy,
}: {
  nodeId: string;
  result: StepTestResult;
  copied: boolean;
  onCopy: (text: string) => void;
}) {
  const tHC = useTranslations("hcComponentsWorkflowStepTestPanel");
  if (!result.ok) {
    return (
      <div className="st-result" data-status="error">
        <p className="st-pill" data-status={result.kind === "blocked" ? "blocked" : "error"}>
          <span aria-hidden="true">{result.kind === "blocked" ? "⊘" : "✕"}</span>
          {result.kind === "blocked" ? "Not testable" : result.kind === "not-found" ? "Not saved yet" : "Couldn't run"}
        </p>
        <p className="st-msg">{result.message}</p>
      </div>
    );
  }
  const e = result.entry;
  const key = stepStatusKey(e.status);
  const st = STATUS_TEXT[key];
  const duration = toNumberOrNull(e.durationMs);
  const tIn = toNumberOrNull(e.tokensIn);
  const tOut = toNumberOrNull(e.tokensOut);
  const cost = toNumberOrNull(e.costUsd);
  const output = e.outputJson ?? "";
  const tools = (e.toolCalls ?? []).filter(Boolean);

  return (
    <div className="st-result" data-status={key}>
      <div className="st-result-head">
        <p className="st-pill" data-status={key}>
          <span aria-hidden="true">{st.glyph}</span>
          {st.label}
        </p>
        <dl className="st-readout">
          <div>
            <dt>Time</dt>
            <dd className="st-num">{formatDuration(duration)}</dd>
          </div>
          {tIn != null || tOut != null ? (
            <div>
              <dt>Tokens</dt>
              <dd className="st-num">
                {tIn != null ? intFmt.format(tIn) : "—"} in / {tOut != null ? intFmt.format(tOut) : "—"} out
              </dd>
            </div>
          ) : null}
          {cost != null ? (
            <div>
              <dt>Cost</dt>
              <dd className="st-num">{formatCostUsd(cost)}</dd>
            </div>
          ) : null}
        </dl>
      </div>

      {e.summary ? <p className="st-msg">{e.summary}</p> : null}

      <section className="st-out" aria-label="Output">
        <div className="st-out-head">
          <span className="st-label">Output</span>
          {output ? (
            <button type="button" className="st-link" onClick={() => onCopy(output)}>
              {copied ? "Copied" : "Copy"}
            </button>
          ) : null}
          {/* Only a step that succeeded has an output worth freezing. */}
          {key === "ok" ? (
            <PinOutputButton nodeId={nodeId} data={output} source="test" truncated={e.outputTruncated} />
          ) : null}
        </div>
        {output ? (
          <pre className="st-pre" tabIndex={0}>
            {prettyText(output)}
          </pre>
        ) : (
          <p className="st-hint">
            {e.outputJson == null ? "Output not captured by this server." : "This step produced no output."}
          </p>
        )}
        {e.outputTruncated ? (
          <p className="st-hint" role="note">
            {tHC("s2")}
          </p>
        ) : null}
      </section>

      {tools.length ? (
        <section className="st-tools" aria-label={`Tool calls (${tools.length})`}>
          <span className="st-label">
            {tHC("s1")} <span className="st-num">{intFmt.format(tools.length)}</span>
          </span>
          <ol className="st-toollist">
            {tools.map((t, i) => (
              <ToolRow key={i} call={t} />
            ))}
          </ol>
        </section>
      ) : null}

      {e.logLines?.length ? (
        <details className="st-logs">
          <summary>
            Log <span className="st-num">({intFmt.format(e.logLines.length)})</span>
          </summary>
          <ol className="st-loglist">
            {e.logLines.map((l, i) => (
              <li key={i}>{l}</li>
            ))}
          </ol>
        </details>
      ) : null}
    </div>
  );
}

function ToolRow({ call }: { call: StepToolCall }) {
  const failed = !!call.error;
  const d = toNumberOrNull(call.durationMs);
  return (
    <li className="st-tool" data-status={failed ? "error" : "ok"}>
      <details>
        <summary>
          <span className="st-tool-ico" aria-hidden="true">
            {failed ? "✕" : "✓"}
          </span>
          <span className="st-tool-name">{call.toolName || "tool"}</span>
          <span className="st-sr">{failed ? "failed" : "succeeded"}</span>
          <span className="st-num st-tool-ms">{formatDuration(d)}</span>
        </summary>
        {call.input ? (
          <>
            <span className="st-sublabel">Input</span>
            <pre className="st-pre st-pre-sm">{prettyText(call.input)}</pre>
          </>
        ) : null}
        {failed ? (
          <>
            <span className="st-sublabel">Error</span>
            <pre className="st-pre st-pre-sm" data-status="error">
              {call.error}
            </pre>
          </>
        ) : call.output ? (
          <>
            <span className="st-sublabel">Output</span>
            <pre className="st-pre st-pre-sm">{prettyText(call.output)}</pre>
          </>
        ) : null}
      </details>
    </li>
  );
}
