"use client";

// Data hooks shared by the coach drawer, the Solutions tab and the envoy
// detail view. Each swallows nothing: a failed load surfaces as `error` so
// the view can say so instead of showing an empty state that looks real.

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import type { BriefDetail, BriefLedger, BriefListItem } from "./types";
import { bindingEntityId } from "./coach";

export function useBriefLedger(id: string | null | undefined) {
  const [ledger, setLedger] = useState<BriefLedger | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);

  const reload = useCallback(async () => {
    if (!id) return;
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const l = await api.getBriefLedger(id);
      if (mine === seq.current) setLedger(l);
    } catch (e: any) {
      if (mine === seq.current) setError(e?.message || "load failed");
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    setLedger(null);
    void reload();
    return () => {
      // Invalidates any in-flight load (a plain counter, not a DOM ref).
      seq.current += 1;
    };
  }, [id, reload]);

  return { ledger, error, loading, reload };
}

const MAX_SCANNED = 12;

/**
 * The applied envoy a Workbench agent / Canvas workflow belongs to (matched by
 * its bindings), or every applied envoy when no entity is given (the /tools
 * mount). Loads only while `active`, so a closed drawer costs nothing.
 */
export function useEnvoyFor(active: boolean, entityId: string | null | undefined) {
  const [status, setStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [envoys, setEnvoys] = useState<{ item: BriefListItem; detail: BriefDetail | null }[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (!active) return undefined;
    let alive = true;
    setStatus("loading");
    void (async () => {
      try {
        // With an entityId the server answers "which envoy owns this" from brief_binding; without it
        // the applied list is scanned. Either way the selected envoy's detail lazy-loads below - the
        // old fan-out fetched every detail just to read its bindings and was the slow path.
        const list = (await api.listBriefs(entityId ?? undefined))
          .filter((b: BriefListItem) => b.status === "APPLIED")
          .slice(0, MAX_SCANNED);
        const rows = list.map((item: BriefListItem) => ({ item, detail: null as BriefDetail | null }));
        if (!alive) return;
        setEnvoys(rows);
        setSelectedId((cur) => (cur && rows.some((r) => r.item.id === cur) ? cur : rows[0]?.item.id ?? null));
        setStatus("ready");
      } catch {
        if (alive) setStatus("error");
      }
    })();
    return () => {
      alive = false;
    };
  }, [active, entityId]);

  const [loaded, setLoaded] = useState<BriefDetail | null>(null);
  const selected = envoys.find((r) => r.item.id === selectedId) ?? null;
  useEffect(() => {
    if (!selected) {
      setLoaded(null);
      return undefined;
    }
    if (selected.detail) {
      setLoaded(selected.detail);
      return undefined;
    }
    let alive = true;
    setLoaded(null);
    api
      .getBrief(selected.item.id)
      .then((d: BriefDetail) => alive && setLoaded(d))
      .catch(() => alive && setStatus("error"));
    return () => {
      alive = false;
    };
  }, [selected]);

  return { status, envoys, selectedId, select: setSelectedId, detail: loaded };
}

/** Entity id of the agent to open for an envoy: the eval target, else the first agent. */
export function primaryAgent(detail: Pick<BriefDetail, "brief" | "bindings">): { ref: string; name: string; entityId: string | null } | null {
  const ref = detail.brief.evalSet?.targetAgent ?? detail.brief.agents[0]?.ref;
  const agent = detail.brief.agents.find((a) => a.ref === ref);
  if (!agent) return null;
  return { ref: agent.ref, name: agent.name, entityId: bindingEntityId(detail.bindings, agent.ref) };
}
