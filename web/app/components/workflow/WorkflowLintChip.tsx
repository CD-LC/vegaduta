"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, Info, XCircle } from "lucide-react";
import LintPanel from "./LintPanel";
import type { UnifiedLintIssue } from "./lintMerge";
import "./workflow-lint.css";
import { useTranslations } from "../../lib/i18n";

// Compact "N warnings" chip pinned to the canvas corner. Renders nothing when
// the graph is clean; otherwise opens the Workflow checks panel (canvas lint
// merged with the server's /lint findings), where each issue can jump to its
// node or apply a one-click fix.
export default function WorkflowLintChip({
  issues,
  onSelectNode,
  nodeLabel,
  onApplyFix,
  loading = false,
  serverAvailable = true,
}: {
  issues: UnifiedLintIssue[];
  onSelectNode: (nodeId: string) => void;
  nodeLabel: (nodeId: string) => string;
  onApplyFix: (issue: UnifiedLintIssue) => void;
  loading?: boolean;
  serverAvailable?: boolean;
}) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowLintChip");
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const chipRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    function onDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  // After the last fix the chip is gone (zero issues) while the panel is
  // open; closing then parks focus on the stable wrapper instead of <body>.
  const close = useCallback(() => {
    setOpen(false);
    (chipRef.current ?? rootRef.current)?.focus();
  }, []);
  const focusNode = useCallback(
    (nodeId: string) => {
      onSelectNode(nodeId);
      setOpen(false);
      // The panel (and the focused row) unmounts: land on the node itself,
      // unless selecting it already moved focus somewhere (e.g. a drawer).
      requestAnimationFrame(() => {
        const a = document.activeElement;
        if (a && a !== document.body) return;
        const esc = typeof CSS !== "undefined" && typeof CSS.escape === "function" ? CSS.escape : (s: string) => s;
        const el = document.querySelector<HTMLElement>(`.react-flow__node[data-id="${esc(nodeId)}"]`);
        if (el) {
          if (!el.hasAttribute("tabindex")) el.tabIndex = -1;
          el.focus();
        } else {
          (chipRef.current ?? rootRef.current)?.focus();
        }
      });
    },
    [onSelectNode],
  );

  // The panel stays open at zero issues (after the last fix) until closed.
  // A clean graph keeps only the empty, invisible wrapper mounted so close()
  // has somewhere to put focus.
  if (issues.length === 0 && !open) {
    return <div className="wf-lint" ref={rootRef} tabIndex={-1} aria-label={tHC("hcComponentsWorkflowWorkflowLintChip.s2")} role="group" style={{ outline: "none" }} />;
  }
  const errors = issues.filter((i) => i.severity === "error").length;
  const warnings = issues.filter((i) => i.severity === "warning").length;
  const severity = errors > 0 ? "error" : warnings > 0 ? "warn" : "info";
  const count = issues.length;
  const noun =
    severity === "error"
      ? count === 1 ? "issue" : "issues"
      : severity === "warn"
        ? count === 1 ? "warning" : "warnings"
        : count === 1 ? "suggestion" : "suggestions";
  const Icon = severity === "error" ? XCircle : severity === "warn" ? AlertTriangle : Info;

  return (
    <div className="wf-lint" ref={rootRef} tabIndex={-1} aria-label={tHC("hcComponentsWorkflowWorkflowLintChip.s1")} role="group" style={{ outline: "none" }}>
      {issues.length > 0 && (
        <button
          ref={chipRef}
          type="button"
          className="wf-lint-chip"
          data-severity={severity}
          aria-expanded={open}
          aria-haspopup="dialog"
          aria-label={`${count} workflow ${noun}. Show details`}
          onClick={() => setOpen((v) => !v)}
        >
          <Icon size={13} aria-hidden="true" />
          {count} {noun}
        </button>
      )}
      {open && (
        <LintPanel
          issues={issues}
          nodeLabel={nodeLabel}
          onFocusNode={focusNode}
          onApplyFix={onApplyFix}
          loading={loading}
          serverAvailable={serverAvailable}
          onClose={close}
        />
      )}
    </div>
  );
}
