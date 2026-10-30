"use client";

import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { AlertTriangle, CheckCircle2, Crosshair, Info, Loader2, X, XCircle } from "lucide-react";
import type { LintFix, LintFixKind, LintSeverity, UnifiedLintIssue } from "./lintMerge";
import "./lint-panel.css";
import { useTranslations } from "../../lib/i18n";

const FIX_LABEL: Record<LintFixKind, string> = {
  "delete-edge": "Remove edge",
  "add-node": "Add trigger",
  "add-error-route": "Add error path",
  "set-config": "Set value",
  "open-config": "Open settings",
  "open-url": "Open",
  "enable-approval-policy": "Require approval",
  "insert-approval": "Add approval step",
};

const GROUPS: { severity: LintSeverity; title: string; one: string; Icon: typeof XCircle }[] = [
  { severity: "error", title: "Errors", one: "Error", Icon: XCircle },
  { severity: "warning", title: "Warnings", one: "Warning", Icon: AlertTriangle },
  { severity: "info", title: "Suggestions", one: "Suggestion", Icon: Info },
];

type SetConfigFix = Extract<LintFix, { kind: "set-config" }>;

function needsValue(fix: LintFix | null): fix is SetConfigFix {
  return !!fix && fix.kind === "set-config" && (fix.value === undefined || fix.value === null || fix.value === "");
}

/** Returns the coerced value, or an error message for the inline field. */
function coerce(path: SetConfigFix["path"], raw: string): { value: unknown } | { error: string } {
  const v = raw.trim();
  if (path === "config.maxIterations") {
    const n = Number(v);
    return Number.isInteger(n) && n > 0 ? { value: n } : { error: "Enter a whole number above 0" };
  }
  return /^https?:\/\/\S+$/i.test(v) ? { value: v } : { error: "Enter a URL starting with http:// or https://" };
}

export type LintPanelProps = {
  issues: UnifiedLintIssue[];
  nodeLabel: (nodeId: string) => string;
  onFocusNode: (nodeId: string) => void;
  /** For set-config without a server value, the issue arrives with fix.value filled from the inline input. */
  onApplyFix: (issue: UnifiedLintIssue) => void;
  loading: boolean;
  serverAvailable: boolean;
  onClose: () => void;
};

// Side panel opened from the lint chip: grouped by severity, one-click fixes,
// arrow-key navigation between issues. Severity is always icon + word.
export default function LintPanel({
  issues,
  nodeLabel,
  onFocusNode,
  onApplyFix,
  loading,
  serverAvailable,
  onClose,
}: LintPanelProps) {
  const tHC = useTranslations("hcComponentsWorkflowLintPanel");
  const rootRef = useRef<HTMLDivElement | null>(null);
  const rowRefs = useRef<(HTMLLIElement | null)[]>([]);
  const [active, setActive] = useState(0);
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [draftError, setDraftError] = useState<string | null>(null);

  const grouped = useMemo(
    () =>
      GROUPS.map((g) => ({ ...g, items: issues.filter((i) => i.severity === g.severity) })).filter(
        (g) => g.items.length > 0
      ),
    [issues]
  );
  // Flat order used for arrow-key navigation (matches visual order).
  const flat = useMemo(() => grouped.flatMap((g) => g.items), [grouped]);
  const activeIdx = Math.min(active, Math.max(0, flat.length - 1));

  useEffect(() => {
    // Land focus inside the panel so Escape/arrows work immediately.
    (rowRefs.current[0] ?? rootRef.current)?.focus();
  }, []);

  useEffect(() => {
    function onDocKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !editing && !e.defaultPrevented) onClose();
    }
    document.addEventListener("keydown", onDocKey);
    return () => document.removeEventListener("keydown", onDocKey);
  }, [onClose, editing]);

  // A fix usually removes its issue, unmounting the focused row (now or when
  // the debounced server re-lint lands); put focus on the row that took its
  // place, or the panel once none remain.
  const refocusRef = useRef<number | null>(null);
  function applyFix(issue: UnifiedLintIssue, idx: number) {
    refocusRef.current = idx;
    onApplyFix(issue);
  }
  useEffect(() => {
    const idx = refocusRef.current;
    if (idx == null) return;
    const a = document.activeElement;
    const inPanel = !!a && !!rootRef.current?.contains(a);
    if (inPanel) return; // the row survived (so far) - keep watching
    refocusRef.current = null;
    if (a && a !== document.body) return; // focus moved on purpose (e.g. a settings drawer)
    const next = Math.min(idx, flat.length - 1);
    const row = next >= 0 ? rowRefs.current[next] : null;
    if (row) {
      setActive(next);
      row.focus();
    } else {
      rootRef.current?.focus();
    }
  }, [flat]);

  function moveTo(i: number) {
    const next = Math.max(0, Math.min(flat.length - 1, i));
    setActive(next);
    rowRefs.current[next]?.focus();
  }

  function onRowKey(e: ReactKeyboardEvent<HTMLLIElement>, idx: number, issue: UnifiedLintIssue) {
    if (e.target !== e.currentTarget) return; // let inner buttons/inputs keep their keys
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        moveTo(idx + 1);
        break;
      case "ArrowUp":
        e.preventDefault();
        moveTo(idx - 1);
        break;
      case "Home":
        e.preventDefault();
        moveTo(0);
        break;
      case "End":
        e.preventDefault();
        moveTo(flat.length - 1);
        break;
      case "Enter":
        if (issue.nodeId) {
          e.preventDefault();
          onFocusNode(issue.nodeId);
        }
        break;
    }
  }

  function startEdit(issue: UnifiedLintIssue) {
    setEditing(issue.id);
    setDraft("");
    setDraftError(null);
  }

  function commitEdit(issue: UnifiedLintIssue, idx: number) {
    if (!issue.fix || issue.fix.kind !== "set-config") return;
    const res = coerce(issue.fix.path, draft);
    if ("error" in res) {
      setDraftError(res.error);
      return;
    }
    applyFix({ ...issue, fix: { ...issue.fix, value: res.value } }, idx);
    setEditing(null);
    // The inline form unmounts even if the issue lingers until re-lint.
    rowRefs.current[idx]?.focus();
  }

  function where(issue: UnifiedLintIssue): string {
    if (issue.nodeId) return nodeLabel(issue.nodeId) || issue.nodeId;
    if (issue.edgeId) return "Connection";
    return "Workflow";
  }

  const counts = { error: 0, warning: 0, info: 0 };
  for (const i of issues) counts[i.severity]++;
  const summary = GROUPS.filter((g) => counts[g.severity] > 0)
    .map((g) => `${counts[g.severity]} ${counts[g.severity] === 1 ? g.one.toLowerCase() : g.title.toLowerCase()}`)
    .join(", ");

  let flatIdx = -1;

  return (
    <div className="wf-lintp" role="dialog" aria-label={tHC("s5")} ref={rootRef} tabIndex={-1}>
      <div className="wf-lintp-head">
        <div className="wf-lintp-title">
          <strong>{tHC("s5")}</strong>
          <span className="wf-lintp-sum">{summary || "No issues"}</span>
        </div>
        <button type="button" className="wf-lintp-icon" aria-label={tHC("s4")} onClick={onClose}>
          <X size={14} aria-hidden="true" />
        </button>
      </div>

      <div className="wf-lintp-status" aria-live="polite">
        {loading && (
          <span className="wf-lintp-note">
            <Loader2 size={12} className="wf-lintp-spin" aria-hidden="true" />
            {tHC("s3")}
          </span>
        )}
        {!loading && !serverAvailable && (
          <span className="wf-lintp-note">
            <Info size={12} aria-hidden="true" />
            {tHC("s2")}
          </span>
        )}
      </div>

      {flat.length === 0 ? (
        <p className="wf-lintp-empty">
          <CheckCircle2 size={14} aria-hidden="true" />
          {loading ? "No canvas issues so far." : "No issues found."}
        </p>
      ) : (
        <div className="wf-lintp-body">
          {grouped.map((g) => (
            <section
              key={g.severity}
              className="wf-lintp-group"
              data-severity={g.severity}
              aria-label={`${g.title}: ${g.items.length}`}
            >
              <h3 className="wf-lintp-gh">
                <g.Icon size={12} aria-hidden="true" />
                {g.title}
                <span className="wf-lintp-count">{g.items.length}</span>
              </h3>
              <ul className="wf-lintp-list">
                {g.items.map((issue) => {
                  flatIdx++;
                  const idx = flatIdx;
                  const fix = issue.fix;
                  const isEditing = editing === issue.id && fix?.kind === "set-config";
                  const inputId = `wf-lintp-in-${issue.id}`;
                  return (
                    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- row arrow-key navigation bubbles from the row's own buttons
                    <li
                      key={issue.id}
                      ref={(el) => {
                        rowRefs.current[idx] = el;
                      }}
                      className="wf-lintp-row"
                      data-severity={issue.severity}
                      tabIndex={idx === activeIdx ? 0 : -1}
                      aria-label={`${g.one}: ${where(issue)}. ${issue.message}`}
                      onFocus={() => setActive(idx)}
                      onKeyDown={(e) => onRowKey(e, idx, issue)}
                    >
                      <div className="wf-lintp-line">
                        <g.Icon size={13} aria-hidden="true" className="wf-lintp-sev-ic" />
                        <div className="wf-lintp-text">
                          <span className="wf-lintp-where">
                            <span className="wf-lintp-sr">{g.one}: </span>
                            {where(issue)}
                          </span>
                          <span className="wf-lintp-msg">{issue.message}</span>
                        </div>
                      </div>

                      {isEditing && fix?.kind === "set-config" ? (
                        <form
                          className="wf-lintp-edit"
                          onSubmit={(e) => {
                            e.preventDefault();
                            commitEdit(issue, idx);
                          }}
                        >
                          <label htmlFor={inputId} className="wf-lintp-sr">
                            {fix.path === "config.maxIterations" ? "Max iterations" : "URL"}
                          </label>
                          <input
                            id={inputId}
                            autoFocus
                            className="wf-lintp-input"
                            type={fix.path === "config.maxIterations" ? "number" : "url"}
                            inputMode={fix.path === "config.maxIterations" ? "numeric" : "url"}
                            min={fix.path === "config.maxIterations" ? 1 : undefined}
                            placeholder={fix.path === "config.maxIterations" ? "e.g. 100" : "https://"}
                            value={draft}
                            aria-invalid={draftError ? true : undefined}
                            aria-describedby={draftError ? `${inputId}-err` : undefined}
                            onChange={(e) => {
                              setDraft(e.target.value);
                              setDraftError(null);
                            }}
                            onKeyDown={(e) => {
                              if (e.key === "Escape") {
                                e.preventDefault();
                                setEditing(null);
                                rowRefs.current[idx]?.focus();
                              }
                            }}
                          />
                          <button type="submit" className="wf-lintp-btn" data-primary="true">
                            Apply
                          </button>
                          <button type="button" className="wf-lintp-btn" onClick={() => setEditing(null)}>
                            Cancel
                          </button>
                          {draftError && (
                            <span id={`${inputId}-err`} className="wf-lintp-err" role="alert">
                              {draftError}
                            </span>
                          )}
                        </form>
                      ) : (
                        (fix || issue.nodeId) && (
                          <div className="wf-lintp-actions">
                            {fix && (
                              <button
                                type="button"
                                className="wf-lintp-btn"
                                data-primary="true"
                                onClick={() => (needsValue(fix) ? startEdit(issue) : applyFix(issue, idx))}
                              >
                                {FIX_LABEL[fix.kind]}
                              </button>
                            )}
                            {issue.nodeId && (
                              <button
                                type="button"
                                className="wf-lintp-btn"
                                aria-label={`Show ${where(issue)} on canvas`}
                                onClick={() => onFocusNode(issue.nodeId as string)}
                              >
                                <Crosshair size={12} aria-hidden="true" />
                                {tHC("s1")}
                              </button>
                            )}
                          </div>
                        )
                      )}
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
