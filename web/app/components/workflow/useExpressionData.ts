"use client";

import { useCallback, useEffect, useState } from "react";
import {
  workflowExpressionsApi,
  type ExpressionFunctionDoc,
  type UpstreamSchemaNode,
} from "../../lib/api/workflowExpressions";

// Shared, cached reads for the expression editor. Every ExpressionField in an
// open drawer needs the same function registry and the same upstream schema,
// so they share one in-flight request and one short-lived cache instead of
// each fetching. Failures degrade to "no suggestions": the field still works
// as plain text.

const SCHEMA_TTL_MS = 20_000;
const SCHEMA_FAIL_TTL_MS = 5_000;

// ---------------------------------------------------------------- functions

let functionsCache: ExpressionFunctionDoc[] | null = null;
let functionsPromise: Promise<ExpressionFunctionDoc[]> | null = null;
let functionsFailedAt = 0;
let functionsUnsupported = false;

export function loadExpressionFunctions(): Promise<ExpressionFunctionDoc[]> {
  if (functionsCache) return Promise.resolve(functionsCache);
  if (functionsUnsupported || Date.now() - functionsFailedAt < SCHEMA_FAIL_TTL_MS) return Promise.resolve([]);
  if (functionsPromise === null) {
    functionsPromise = workflowExpressionsApi
      .getExpressionFunctions()
      .then((r) => {
        if (r.ok && Array.isArray(r.data)) {
          functionsCache = r.data.filter((f) => f && typeof f.name === "string");
          return functionsCache;
        }
        if (r.unsupported) functionsUnsupported = true;
        else functionsFailedAt = Date.now();
        return [];
      })
      .finally(() => {
        functionsPromise = null;
      });
  }
  return functionsPromise;
}

export function useExpressionFunctions(): ExpressionFunctionDoc[] {
  const [fns, setFns] = useState<ExpressionFunctionDoc[]>(functionsCache ?? []);
  useEffect(() => {
    let live = true;
    void loadExpressionFunctions().then((f) => {
      if (live) setFns(f);
    });
    return () => {
      live = false;
    };
  }, []);
  return fns;
}

// ------------------------------------------------------------------ schema

type SchemaEntry = { at: number; ttl: number; nodes: UpstreamSchemaNode[]; promise?: Promise<UpstreamSchemaNode[]> };
const schemaCache = new Map<string, SchemaEntry>();
let schemaUnsupported = false;

const keyOf = (workflowId: string, nodeId: string) => `${workflowId}\u0000${nodeId}`;

export function invalidateUpstreamSchema(workflowId?: string): void {
  if (!workflowId) schemaCache.clear();
  else for (const k of [...schemaCache.keys()]) if (k.startsWith(`${workflowId}\u0000`)) schemaCache.delete(k);
}

export function loadUpstreamSchema(workflowId: string, nodeId: string, force = false): Promise<UpstreamSchemaNode[]> {
  if (!workflowId || !nodeId || schemaUnsupported) return Promise.resolve([]);
  const key = keyOf(workflowId, nodeId);
  const hit = schemaCache.get(key);
  const inflight = hit?.promise;
  if (inflight !== undefined) return inflight;
  if (hit && !force && Date.now() - hit.at < hit.ttl) return Promise.resolve(hit.nodes);
  const promise = workflowExpressionsApi.getUpstreamSchema(workflowId, nodeId).then((r) => {
    if (r.ok && r.data && Array.isArray(r.data.nodes)) {
      const nodes = r.data.nodes
        .filter((n) => n && typeof n.nodeId === "string")
        .map((n) => ({ ...n, label: n.label ?? "", paths: Array.isArray(n.paths) ? n.paths : [] }));
      schemaCache.set(key, { at: Date.now(), ttl: SCHEMA_TTL_MS, nodes });
      return nodes;
    }
    if (r.unsupported) schemaUnsupported = true;
    schemaCache.set(key, { at: Date.now(), ttl: SCHEMA_FAIL_TTL_MS, nodes: hit?.nodes ?? [] });
    return hit?.nodes ?? [];
  });
  schemaCache.set(key, { at: hit?.at ?? 0, ttl: hit?.ttl ?? 0, nodes: hit?.nodes ?? [], promise });
  return promise.finally(() => {
    const cur = schemaCache.get(key);
    if (cur?.promise === promise) delete cur.promise;
  });
}

export function useUpstreamSchema(workflowId: string | undefined, nodeId: string | undefined) {
  const [nodes, setNodes] = useState<UpstreamSchemaNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!workflowId || !nodeId) {
      setNodes([]);
      return undefined;
    }
    let live = true;
    setLoading(true);
    void loadUpstreamSchema(workflowId, nodeId, tick > 0).then((n) => {
      if (!live) return;
      setNodes(n);
      setLoading(false);
    });
    return () => {
      live = false;
    };
  }, [workflowId, nodeId, tick]);

  return { nodes, loading, refresh };
}
