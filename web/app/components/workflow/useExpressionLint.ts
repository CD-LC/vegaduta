"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { workflowExpressionsApi } from "../../lib/api/workflowExpressions";
import { buildLintRequest, collectExpressionItems, mergeExpressionLint, type ExprItem, type ServerExprLint } from "./expressionLint";
import type { UnifiedLintIssue } from "./lintMerge";
import type { ExpressionLintState } from "./ExpressionField";

// Expression lint for the canvas: client quick checks run synchronously on
// every edit; the server lint (POST /expressions/lint) is debounced and
// merged in. A missing endpoint turns the server half off for the session and
// the quick checks keep working.

const DEBOUNCE_MS = 1200;

type NodeLike = { id: string; type?: string; data?: unknown };

export function useExpressionLint(
  workflowId: string,
  nodes: readonly NodeLike[],
  /** Changes whenever the graph's shape does (labels, edges); the server answer depends on both. */
  graphKey: string | null,
  paused: boolean,
  /** Bumped after every successful save: core lints against the saved graph, so an earlier answer is stale. */
  savedVersion = 0,
): { issues: UnifiedLintIssue[]; state: ExpressionLintState } {
  // Dragging a node re-creates the nodes array but not the items: keep the previous
  // items (same identity) while their content is unchanged so the merge below is not redone per frame.
  const cache = useRef<{ key: string; items: ExprItem[] } | null>(null);
  const fresh = collectExpressionItems(nodes as never);
  const itemsKey = JSON.stringify(fresh.map((i) => [i.nodeId, i.field, i.template, i.nodeLabel]));
  if (cache.current?.key !== itemsKey) cache.current = { key: itemsKey, items: fresh };
  const items = cache.current.items;

  const [server, setServer] = useState<ServerExprLint | null>(null);
  const unsupported = useRef(false);
  const seq = useRef(0);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  useEffect(() => {
    if (paused || !workflowId || unsupported.current) return undefined;
    if (itemsRef.current.length === 0) {
      setServer(null);
      return undefined;
    }
    const timer = setTimeout(() => {
      const { body, sent } = buildLintRequest(itemsRef.current);
      const mine = ++seq.current;
      void workflowExpressionsApi.lintExpressions(workflowId, body).then((r) => {
        if (mine !== seq.current) return;
        if (r.ok && r.data && Array.isArray(r.data.diagnostics)) setServer({ diagnostics: r.data.diagnostics, sent });
        else if (r.unsupported) {
          unsupported.current = true;
          setServer(null);
        }
      });
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [workflowId, itemsKey, graphKey, paused, savedVersion]);

  const issues = useMemo(() => mergeExpressionLint(items, server), [items, server]);
  const state = useMemo<ExpressionLintState>(() => (server ? { diagnostics: server.diagnostics, sent: server.sent } : null), [server]);
  return { issues, state };
}
