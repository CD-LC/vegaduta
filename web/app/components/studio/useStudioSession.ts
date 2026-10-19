"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createSession, fetchSession, readStoredToken, clearStoredToken, type DemoSession } from "../try/demoClient";
import { studio, type StudioAgent, type StudioPalette, type StudioStatus, type StudioWorkflow } from "./studioClient";

// The /try studio's sandbox lifecycle, split out of TryStudio: boot (resume a
// stored sandbox or open a blank one), the agent/workflow/palette/status loads,
// and the debounced fair-use refresh after a run. TryStudio keeps the UI state.

export type Phase = "booting" | "ready" | "unavailable";

const BOOT_LINES = [
  "Opening a clean space in the demo tenant…",
  "Wiring up the tools, documents and connectors…",
  "Setting the table for your first agent…",
];

export function useStudioSession() {
  const [phase, setPhase] = useState<Phase>("booting");
  const [bootLine, setBootLine] = useState(0);
  const [unavailable, setUnavailable] = useState("");
  const [token, setToken] = useState<string | null>(null);
  const [session, setSession] = useState<DemoSession | null>(null);
  const [agents, setAgents] = useState<StudioAgent[]>([]);
  const [workflows, setWorkflows] = useState<StudioWorkflow[]>([]);
  const [palette, setPalette] = useState<StudioPalette | null>(null);
  const [workflowsMax, setWorkflowsMax] = useState(3);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [fresh, setFresh] = useState(false);
  const [status, setStatus] = useState<StudioStatus | null>(null);
  const statusTimer = useRef<number | null>(null);

  useEffect(() => {
    if (phase !== "booting") return undefined;
    const id = window.setInterval(() => setBootLine((n) => Math.min(n + 1, BOOT_LINES.length - 1)), 1400);
    return () => window.clearInterval(id);
  }, [phase]);

  const refreshAgents = useCallback(async (tok: string, keepSelection = true) => {
    const a = await studio.listAgents(tok);
    if (!a.ok) return null;
    // Only ever this sandbox's own agents. The server enforces the same; this keeps an older backend from showing anyone else's.
    const own = a.value.filter((x) => x.mine);
    setAgents(own);
    setSelectedId((cur) =>
      keepSelection && cur && own.some((x) => x.id === cur) ? cur : ((own.find((x) => x.live) ?? own[0])?.id ?? null)
    );
    return own;
  }, []);

  const loadStudio = useCallback(
    async (tok: string, sess: DemoSession) => {
      const [w, st] = await Promise.all([studio.listWorkflows(tok), studio.status(tok)]);
      const list = await refreshAgents(tok);
      if (w.ok) setWorkflows(w.value.filter((x) => x.mine));
      if (st.ok) {
        setStatus(st.value);
        setWorkflowsMax(st.value.workflowsMax);
        setSession({
          ...sess,
          messagesUsed: st.value.messagesUsed,
          messageLimit: st.value.messageLimit,
          readOnly: st.value.readOnly,
        });
      }
      const probe = (list && (list.find((x) => x.mine) ?? list[0])?.id) || sess.agentId;
      // Not awaited: the palette probes which tools resolve in this deployment, which can be slow when a tool
      // backend is slow. The studio must render regardless - tools show a "loading the palette" state meanwhile.
      void studio.palette(tok, probe).then((p) => {
        if (p.ok) setPalette(p.value);
      });
      return list;
    },
    [refreshAgents]
  );

  /** A brand-new sandbox is seeded with one generic agent. It is nobody's topic, so it goes - the launcher is the first thing they see. */
  const clearSeeded = useCallback(async (tok: string) => {
    const list = await studio.listAgents(tok);
    if (!list.ok) return;
    await Promise.all(list.value.filter((a) => a.mine).map((a) => studio.deleteAgent(tok, a.id)));
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const stored = readStoredToken();
      if (stored) {
        const found = await fetchSession(stored);
        if (cancelled) return;
        if (found.session && !found.session.expired) {
          setToken(stored);
          setSession(found.session);
          await loadStudio(stored, found.session);
          if (!cancelled) setPhase("ready");
          return;
        }
        if (found.stale || found.session?.expired) clearStoredToken();
        else if (!found.session) {
          setUnavailable("We couldn't reach the studio just now. Please try again in a moment.");
          setPhase("unavailable");
          return;
        }
      }
      const created = await createSession({ blank: true });
      if (cancelled) return;
      if (!created.ok || !created.session?.demoToken) {
        setUnavailable(created.message ?? "We couldn't start a studio just now.");
        setPhase("unavailable");
        return;
      }
      setToken(created.session.demoToken);
      setSession(created.session);
      await clearSeeded(created.session.demoToken);
      setFresh(true);
      await loadStudio(created.session.demoToken, created.session);
      if (!cancelled) setPhase("ready");
    })();
    return () => {
      cancelled = true;
    };
  }, [loadStudio, clearSeeded]);

  const onBudget = useCallback((used: number, limit: number) => {
    setSession((s) => (s ? { ...s, messagesUsed: used, messageLimit: limit } : s));
    // Refresh the fair-use meters shortly after a run starts; debounced so a burst is one request.
    if (statusTimer.current) window.clearTimeout(statusTimer.current);
    statusTimer.current = window.setTimeout(() => {
      const tok = readStoredToken();
      if (tok) void studio.status(tok).then((r) => r.ok && setStatus(r.value));
    }, 2500);
  }, []);

  return {
    phase,
    bootText: BOOT_LINES[bootLine],
    unavailable,
    token,
    setToken,
    session,
    setSession,
    agents,
    setAgents,
    workflows,
    setWorkflows,
    palette,
    setPalette,
    workflowsMax,
    selectedId,
    setSelectedId,
    fresh,
    setFresh,
    status,
    refreshAgents,
    loadStudio,
    clearSeeded,
    onBudget,
  };
}
