"use client";

// Pin state for one workflow: a module-level cache so reopening the canvas
// paints the last-known pins at once, optimistic writes, and rollback when the
// server refuses. Degrades quietly: a missing route (older core) or a network
// error leaves an empty map and `unsupported`/`loaded` flags for the UI.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  deletePin,
  listPins,
  optimisticPin,
  putPin,
  type PinResult,
  type PutPinBody,
  type WorkflowPin,
} from "../../lib/api/workflowPins";
import { pinLimitBreach, pinsAreMetadataOnly, type PinMap } from "./pinModel";

const EMPTY: PinMap = Object.freeze({});
const cache = new Map<string, PinMap>();

/** Test hook: the cache is module state. */
export function __resetPinCache(): void {
  cache.clear();
}

function toMap(list: readonly WorkflowPin[]): PinMap {
  const m: Record<string, WorkflowPin> = {};
  for (const p of list) m[p.nodeId] = p;
  return m;
}

export type UsePins = {
  pins: PinMap;
  /** First fetch settled (success or failure). */
  loaded: boolean;
  /** The pins route is missing on this core; hide every pin control. */
  unsupported: boolean;
  /** May create/edit/remove pins. False once the server answered 403 or withheld pin data. */
  canEdit: boolean;
  refresh: () => Promise<void>;
  pin: (nodeId: string, body: PutPinBody) => Promise<PinResult<WorkflowPin>>;
  unpin: (nodeId: string) => Promise<PinResult<null>>;
};

export function usePins(workflowId: string | null | undefined): UsePins {
  const id = workflowId ? String(workflowId) : "";
  const [pins, setPins] = useState<PinMap>(() => (id ? (cache.get(id) ?? EMPTY) : EMPTY));
  const [loaded, setLoaded] = useState(false);
  const [unsupported, setUnsupported] = useState(false);
  const [forbidden, setForbidden] = useState(false);
  const idRef = useRef(id);
  const pinsRef = useRef(pins);
  pinsRef.current = pins;

  // Adjust state on workflow change without an effect round-trip (no stale flash of the old graph's pins).
  const [forId, setForId] = useState(id);
  if (forId !== id) {
    setForId(id);
    setPins(id ? (cache.get(id) ?? EMPTY) : EMPTY);
    setLoaded(false);
    setUnsupported(false);
    setForbidden(false);
  }
  idRef.current = id;

  const commit = useCallback((forWorkflow: string, updater: (prev: PinMap) => PinMap) => {
    if (idRef.current !== forWorkflow) return; // a late answer for a workflow that is no longer open
    setPins((prev) => {
      const next = updater(prev);
      if (next !== prev) cache.set(forWorkflow, next);
      return next;
    });
  }, []);

  const refresh = useCallback(async () => {
    if (!id) return;
    const r = await listPins(id);
    if (idRef.current !== id) return;
    if (r.ok) {
      setUnsupported(false);
      commit(id, () => toMap(r.data));
    } else if (r.kind === "unsupported") {
      setUnsupported(true);
    } else if (r.kind === "forbidden") {
      setForbidden(true);
    }
    // Any other failure keeps the last-known pins; the next trigger retries.
    setLoaded(true);
  }, [id, commit]);

  useEffect(() => {
    if (!id) return undefined;
    void refresh();
    return undefined;
  }, [id, refresh]);

  const pin = useCallback(
    async (nodeId: string, body: PutPinBody): Promise<PinResult<WorkflowPin>> => {
      if (!id) return { ok: false, kind: "error", message: "", status: 0 };
      const breach = pinLimitBreach(pinsRef.current, nodeId, body.data.length);
      if (breach) {
        return { ok: false, kind: breach === "count" ? "too-many" : "total-size", message: "", status: 400 };
      }
      const before = pinsRef.current[nodeId];
      commit(id, (prev) => ({ ...prev, [nodeId]: optimisticPin(nodeId, body, before?.pinnedBy ?? "") }));
      const r = await putPin(id, nodeId, body);
      if (r.ok) {
        // A server reply that withholds data (permission) still leaves the author's own text visible to them.
        const merged = r.data.data == null ? { ...r.data, data: body.data } : r.data;
        commit(id, (prev) => ({ ...prev, [nodeId]: merged }));
      } else {
        commit(id, (prev) => {
          const next = { ...prev };
          if (before) next[nodeId] = before;
          else delete next[nodeId];
          return next;
        });
        if (r.kind === "forbidden") setForbidden(true);
        if (r.kind === "unsupported") setUnsupported(true);
      }
      return r;
    },
    [id, commit],
  );

  const unpin = useCallback(
    async (nodeId: string): Promise<PinResult<null>> => {
      if (!id) return { ok: false, kind: "error", message: "", status: 0 };
      const before = pinsRef.current[nodeId];
      if (!before) return { ok: true, data: null };
      commit(id, (prev) => {
        const next = { ...prev };
        delete next[nodeId];
        return next;
      });
      const r = await deletePin(id, nodeId);
      if (!r.ok) {
        commit(id, (prev) => ({ ...prev, [nodeId]: before }));
        if (r.kind === "forbidden") setForbidden(true);
      }
      return r;
    },
    [id, commit],
  );

  const canEdit = !unsupported && !forbidden && !pinsAreMetadataOnly(pins);

  return useMemo(
    () => ({ pins, loaded, unsupported, canEdit, refresh, pin, unpin }),
    [pins, loaded, unsupported, canEdit, refresh, pin, unpin],
  );
}
