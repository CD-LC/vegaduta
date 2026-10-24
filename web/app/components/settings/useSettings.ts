"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  toFieldError,
  type EffectiveSetting,
  type SettingCatalogEntry,
  type SettingScope,
  type SettingsClient,
  type SettingsFieldError,
} from "../../lib/api/settings";
import { overrideSource } from "./settingsModel";

export type LoadStatus = "loading" | "ready" | "error";

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err ?? "");
}

// ---------------------------------------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------------------------------------

export interface CatalogState {
  status: LoadStatus;
  entries: SettingCatalogEntry[];
  error: string | null;
  reload: () => void;
}

/** Loads the settings catalog once (and again on reload). Stale responses from a previous client are dropped. */
export function useSettingsCatalog(client: SettingsClient): CatalogState {
  const [state, setState] = useState<{ status: LoadStatus; entries: SettingCatalogEntry[]; error: string | null }>({
    status: "loading",
    entries: [],
    error: null,
  });
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setState((s) => ({ ...s, status: "loading", error: null }));
    client
      .getCatalog()
      .then((c) => {
        if (!cancelled) setState({ status: "ready", entries: c.entries, error: null });
      })
      .catch((err: unknown) => {
        if (!cancelled) setState({ status: "error", entries: [], error: errorText(err) });
      });
    return () => {
      cancelled = true;
    };
  }, [client, nonce]);
  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { ...state, reload };
}

// ---------------------------------------------------------------------------------------------------------
// Effective values
// ---------------------------------------------------------------------------------------------------------

export interface EffectiveState {
  status: LoadStatus;
  values: Record<string, EffectiveSetting>;
  /** Agent scope only: the tenant-level values an agent falls back to when its own override is reset. */
  inherited: Record<string, EffectiveSetting>;
  error: string | null;
  /** quiet: refresh in place without flipping back to the loading state. */
  reload: (opts?: { quiet?: boolean }) => Promise<void>;
  /** Replace one key locally (optimistic update); returns what it was so the caller can roll back. */
  setLocal: (key: string, next: EffectiveSetting | undefined) => EffectiveSetting | undefined;
}

/** Loads the resolved value + source of every setting for the tenant, or for one agent. */
export function useEffectiveSettings(client: SettingsClient, scope: SettingScope, agentId?: string): EffectiveState {
  const [status, setStatus] = useState<LoadStatus>("loading");
  const [values, setValues] = useState<Record<string, EffectiveSetting>>({});
  const [inherited, setInherited] = useState<Record<string, EffectiveSetting>>({});
  const [error, setError] = useState<string | null>(null);
  const valuesRef = useRef(values);
  valuesRef.current = values;
  // Each load gets a ticket; only the newest one may write state (switching agent mid-flight, quiet refreshes).
  const ticket = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const needsAgent = scope === "agent";
  const load = useCallback(
    async (quiet: boolean) => {
      const mine = ++ticket.current;
      if (!quiet) {
        setStatus("loading");
        setError(null);
      }
      try {
        if (needsAgent && !agentId) throw new Error("agentId is required for agent-scoped settings");
        const [own, base] = await Promise.all([
          client.getEffective(needsAgent ? agentId : undefined),
          needsAgent ? client.getEffective() : Promise.resolve(null),
        ]);
        if (!mounted.current || mine !== ticket.current) return;
        setValues(own.values);
        setInherited(base ? base.values : {});
        setStatus("ready");
        setError(null);
      } catch (err: unknown) {
        if (!mounted.current || mine !== ticket.current) return;
        // A failed QUIET refresh keeps what is on screen; only a first/explicit load turns into the error state.
        if (!quiet) {
          setStatus("error");
          setError(errorText(err));
        }
      }
    },
    [client, needsAgent, agentId]
  );

  useEffect(() => {
    void load(false);
    return () => {
      // The ticket is a counter, not a DOM node: bumping it on cleanup is what invalidates an in-flight load.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      ticket.current++;
    };
  }, [load]);

  const reload = useCallback((opts?: { quiet?: boolean }) => load(opts?.quiet === true), [load]);

  const setLocal = useCallback((key: string, next: EffectiveSetting | undefined) => {
    const previous = valuesRef.current[key];
    const apply = (cur: Record<string, EffectiveSetting>) => {
      const copy = { ...cur };
      if (next) copy[key] = next;
      else delete copy[key];
      return copy;
    };
    setValues(apply);
    // Keep the ref current immediately so two changes in the same tick each see the other's write.
    valuesRef.current = apply(valuesRef.current);
    return previous;
  }, []);

  return { status, values, inherited, error, reload, setLocal };
}

// ---------------------------------------------------------------------------------------------------------
// Writes: optimistic with rollback
// ---------------------------------------------------------------------------------------------------------

export interface SettingChange {
  key: string;
  value: unknown;
  /** Required by the server for risk high/security; the UI collects it in RiskyChangeDialog. */
  reason?: string;
}

export interface WriterState {
  /** Keys with a write or reset in flight. */
  pending: ReadonlySet<string>;
  /** Last failure per key; cleared when the key is edited again or saved successfully. */
  errors: Record<string, SettingsFieldError>;
  clearError: (key: string) => void;
  /** Writes every change in parallel; resolves with the keys that were saved. Failed keys are rolled back. */
  commit: (changes: SettingChange[]) => Promise<string[]>;
  /** Drop the override at this scope. Resolves true when it worked. A high or security key needs the typed reason. */
  reset: (key: string, reason?: string) => Promise<boolean>;
}

export function useSettingsWriter(opts: {
  client: SettingsClient;
  scope: SettingScope;
  agentId?: string;
  entries: SettingCatalogEntry[];
  effective: EffectiveState;
  onSaved?: (key: string) => void;
}): WriterState {
  const { client, scope, agentId, entries, effective, onSaved } = opts;
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  const [errors, setErrors] = useState<Record<string, SettingsFieldError>>({});
  const live = useRef({ entries, effective, onSaved });
  live.current = { entries, effective, onSaved };

  const mark = useCallback((keys: string[], on: boolean) => {
    setPending((cur) => {
      const next = new Set(cur);
      for (const k of keys) {
        if (on) next.add(k);
        else next.delete(k);
      }
      return next;
    });
  }, []);

  const setError = useCallback((key: string, err: SettingsFieldError | null) => {
    setErrors((cur) => {
      if (!err) {
        if (!(key in cur)) return cur;
        const copy = { ...cur };
        delete copy[key];
        return copy;
      }
      return { ...cur, [key]: err };
    });
  }, []);

  const commit = useCallback(
    async (changes: SettingChange[]): Promise<string[]> => {
      const keys = changes.map((c) => c.key);
      mark(keys, true);
      const saved: string[] = [];
      await Promise.all(
        changes.map(async (c) => {
          const previous = live.current.effective.setLocal(c.key, {
            value: c.value,
            source: overrideSource(scope),
            locked: false,
          });
          setError(c.key, null);
          try {
            await client.put(scope, c.key, c.reason ? { value: c.value, reason: c.reason } : { value: c.value }, agentId);
            saved.push(c.key);
            live.current.onSaved?.(c.key);
          } catch (err: unknown) {
            live.current.effective.setLocal(c.key, previous);
            setError(c.key, toFieldError(err));
          }
        })
      );
      mark(keys, false);
      return saved;
    },
    [client, scope, agentId, mark, setError]
  );

  const reset = useCallback(
    async (key: string, reason?: string): Promise<boolean> => {
      mark([key], true);
      setError(key, null);
      const entry = live.current.entries.find((e) => e.key === key);
      // Optimistically show what the setting falls back to: the tenant value for an agent, the default for a tenant.
      // The quiet reload below replaces this guess with the server's answer (e.g. a platform value).
      const fallback: EffectiveSetting | undefined =
        scope === "agent" && live.current.effective.inherited[key]
          ? live.current.effective.inherited[key]
          : entry
            ? { value: entry.default, source: "default", locked: false }
            : undefined;
      const previous = live.current.effective.setLocal(key, fallback);
      try {
        // The reason is passed only when there is one, so a plain reset is the same call it always was.
        if (reason) await client.reset(scope, key, agentId, reason);
        else await client.reset(scope, key, agentId);
        void live.current.effective.reload({ quiet: true });
        live.current.onSaved?.(key);
        return true;
      } catch (err: unknown) {
        live.current.effective.setLocal(key, previous);
        setError(key, toFieldError(err));
        return false;
      } finally {
        mark([key], false);
      }
    },
    [client, scope, agentId, mark, setError]
  );

  const clearError = useCallback((key: string) => setError(key, null), [setError]);
  return { pending, errors, clearError, commit, reset };
}
