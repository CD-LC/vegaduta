"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  AddonApiError,
  addonsApi,
  type AddonChange,
  type AddonList,
  type AddonsApi,
} from "../../lib/addonsApi";
import { pollIntervalMs } from "../../lib/addonsModel";

// Loads the add-on list, keeps it fresh while something is in flight, and runs enable/disable.
// The list is read-mostly: after a change the response's own add-on and `apply` are shown at once and a
// full re-read follows, because a change can touch several add-ons (requirements, dependents).

export interface ActionFailure {
  /** The add-on the person acted on. */
  id: string;
  status: number | undefined;
  code: string | null;
  /** Core's English sentence; the page prefers its own translation of `code`. */
  message: string;
}

export type ChangeResult =
  | { ok: true; change: AddonChange }
  | { ok: false; failure: ActionFailure | null; blockedBy: string[] };

export interface UseAddons {
  data: AddonList | null;
  loaded: boolean;
  /** The list could not be read and there is nothing to show. */
  loadError: string | null;
  /** A background refresh failed; the last known state is still shown. */
  stale: boolean;
  busy: ReadonlySet<string>;
  failure: ActionFailure | null;
  notice: AddonChange | null;
  refresh: () => Promise<void>;
  enable: (id: string) => Promise<ChangeResult>;
  disable: (id: string, cascade?: boolean) => Promise<ChangeResult>;
  clearFailure: () => void;
  clearNotice: () => void;
}

function toFailure(id: string, e: unknown): ActionFailure {
  if (e instanceof AddonApiError) return { id, status: e.status, code: e.code, message: e.message };
  return { id, status: undefined, code: null, message: e instanceof Error ? e.message : "" };
}

export function useAddons(api: AddonsApi = addonsApi): UseAddons {
  const [data, setData] = useState<AddonList | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set());
  const [failure, setFailure] = useState<ActionFailure | null>(null);
  const [notice, setNotice] = useState<AddonChange | null>(null);
  // Bumped after every read attempt so the polling effect re-arms even when a read failed.
  const [tick, setTick] = useState(0);
  const inflight = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    if (inflight.current) return;
    inflight.current = true;
    try {
      const next = await api.list();
      if (!alive.current) return;
      setData(next);
      setLoadError(null);
      setStale(false);
    } catch (e) {
      if (!alive.current) return;
      // Keep showing what we know; only an empty page needs the error state.
      setStale(true);
      setLoadError(e instanceof Error && e.message ? e.message : "");
    } finally {
      inflight.current = false;
      if (alive.current) {
        setLoaded(true);
        setTick((n) => n + 1);
      }
    }
  }, [api]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Poll only while something is in flight: 2 s for a download, 5 s for a host step. A hidden tab waits
  // and reads once when it becomes visible again.
  useEffect(() => {
    const ms = data ? pollIntervalMs(data.addons) : null;
    if (ms === null) return undefined;
    const timer = setTimeout(() => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      void refresh();
    }, ms);
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
    // `tick` re-arms the timer after each attempt, including a failed one.
  }, [data, tick, refresh]);

  const run = useCallback(
    async (id: string, call: () => Promise<AddonChange>, cascade: boolean): Promise<ChangeResult> => {
      setBusy((s) => new Set(s).add(id));
      setFailure(null);
      try {
        const change = await call();
        if (!alive.current) return { ok: true, change };
        setNotice(change);
        setData((prev) =>
          prev
            ? { ...prev, apply: change.apply, addons: prev.addons.map((a) => (a.id === change.addon.id ? change.addon : a)) }
            : prev
        );
        void refresh();
        return { ok: true, change };
      } catch (e) {
        const f = toFailure(id, e);
        if (alive.current) {
          // A disable that other add-ons depend on is a question, not an error: the page asks "turn these off too?".
          if (f.code === "required_by" && !cascade && e instanceof AddonApiError) {
            return { ok: false, failure: null, blockedBy: e.dependents };
          }
          setFailure(f);
          // A raced write or a stale view: show what is true now.
          if (f.code === "conflict" || f.code === "unknown_addon") void refresh();
        }
        return { ok: false, failure: f, blockedBy: [] };
      } finally {
        if (alive.current) {
          setBusy((s) => {
            const n = new Set(s);
            n.delete(id);
            return n;
          });
        }
      }
    },
    [refresh]
  );

  const enable = useCallback((id: string) => run(id, () => api.enable(id), false), [api, run]);
  const disable = useCallback(
    (id: string, cascade = false) => run(id, () => api.disable(id, cascade), cascade),
    [api, run]
  );

  return {
    data,
    loaded,
    loadError: data ? null : loadError,
    stale,
    busy,
    failure,
    notice,
    refresh,
    enable,
    disable,
    clearFailure: () => setFailure(null),
    clearNotice: () => setNotice(null),
  };
}
