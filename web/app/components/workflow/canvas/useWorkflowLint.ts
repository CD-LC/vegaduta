"use client";

import { useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { api } from "../../../lib/api";
import { useExpressionLint } from "../useExpressionLint";
import {
  dropSatisfiedErrorRoutes,
  mergeLint,
  parseLintResponse,
  sortLint,
  type ServerLintIssue,
  type UnifiedLintIssue,
} from "../lintMerge";
import type { lintGraph } from "../canvasOps";

/**
 * Lint: canvas checks + server /lint (Phase 4) + expression lint. The server
 * lints the UNSAVED canvas graph 1.5s after edits settle (its stats are cached
 * 60s, so this is cheap); a missing endpoint turns the server half off for the
 * session and the chip keeps the canvas checks.
 */
export function useWorkflowLint(opts: {
  workflowId: any;
  nodes: any[];
  nodesRef: MutableRefObject<any[]>;
  graphJson: string | null;
  lintIssues: ReturnType<typeof lintGraph>;
  observing: boolean;
  lintNonce: number;
}) {
  const { workflowId, nodes, nodesRef, graphJson, lintIssues, observing, lintNonce } = opts;
  const [serverLint, setServerLint] = useState<{ issues: ServerLintIssue[]; graphJson: string } | null>(null);
  const [lintServerAvailable, setLintServerAvailable] = useState(true);
  const [lintLoading, setLintLoading] = useState(false);
  const lintSeqRef = useRef(0);
  useEffect(() => {
    if (!lintServerAvailable || !graphJson || observing) return undefined;
    const json = graphJson;
    const timer = setTimeout(() => {
      let graphBody: { nodes: unknown[]; edges: unknown[] };
      try {
        graphBody = JSON.parse(json);
      } catch {
        return;
      }
      const seq = ++lintSeqRef.current;
      setLintLoading(true);
      void api.lintWorkflow(workflowId, graphBody).then((r) => {
        if (seq !== lintSeqRef.current) return;
        setLintLoading(false);
        if (r.ok) {
          const parsed = parseLintResponse(r.data);
          if (parsed) setServerLint({ issues: parsed.issues, graphJson: json });
          else {
            setLintServerAvailable(false);
            setServerLint(null);
          }
        } else if (r.unsupported) {
          setLintServerAvailable(false);
          setServerLint(null);
        }
      });
    }, 1500);
    return () => clearTimeout(timer);
  }, [graphJson, lintServerAvailable, workflowId, observing, lintNonce]);

  const [mergedLint, setMergedLint] = useState<UnifiedLintIssue[]>([]);
  useEffect(() => {
    let cancelled = false;
    const list = nodesRef.current;
    const clientIssues = dropSatisfiedErrorRoutes(lintIssues, list);
    // Server findings for a graph the canvas has since moved past: the
    // client is authoritative on the checks both sides run.
    const stale = !!serverLint && serverLint.graphJson !== graphJson;
    void mergeLint(clientIssues, serverLint?.issues ?? [], {
      nodeIds: new Set(list.map((n) => n.id)),
      clientAuthoritativeParity: stale,
    }).then((merged) => {
      if (!cancelled) setMergedLint(merged);
    });
    return () => {
      cancelled = true;
    };
  }, [lintIssues, serverLint, graphJson, nodesRef]);

  // Expression lint: client quick checks + debounced POST /expressions/lint, merged with the rows above.
  const exprLint = useExpressionLint(workflowId, nodes, graphJson, observing, lintNonce);
  const allLint = useMemo(
    () => (exprLint.issues.length ? sortLint([...mergedLint, ...exprLint.issues]) : mergedLint),
    [mergedLint, exprLint.issues]
  );

  return { allLint, lintLoading, lintServerAvailable, exprLint };
}
