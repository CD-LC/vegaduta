"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import { branchEditPath, branchRegeneratePath } from "../lib/api/chatBranching";
import {
  canBranch,
  collectExtras,
  mergeServerPath,
  siblingIdAt,
  turnsForEdit,
  turnsForRegenerate,
  turnsFromPath,
  userTextBefore,
  type BranchTurn,
} from "../lib/chatBranching";
import { useTranslations } from "../lib/i18n";
import { normalizeServerStep, type ActivityStep } from "./agents/AgentActivityTrace";

// Chat branching state for ChatPanel: whether the server offers it, which message is being edited,
// and the edit / regenerate / switch actions. The tree itself lives on the server; this hook only
// ever holds the ACTIVE path, as the panel's ordinary `turns`, with each turn labelled by message
// id and version position once the server has numbered it.

/** How long to wait before asking again when the server has not recorded the last reply yet. */
const SYNC_RETRY_MS = 700;

interface Options {
  agentId: string | null;
  sessionId: string | null;
  turns: BranchTurn[];
  setTurns: (next: BranchTurn[] | ((current: BranchTurn[]) => BranchTurn[])) => void;
  sending: boolean;
  setSending: (value: boolean) => void;
  setError: (message: string | null) => void;
  setSessionId: (id: string) => void;
  setChatController: (controller: any) => void;
  onTurnComplete?: () => void;
}

export function useChatBranching(options: Options) {
  const { agentId, sessionId, turns, sending } = options;
  const t = useTranslations("chatBranching");
  const [enabled, setEnabled] = useState(false);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [switching, setSwitching] = useState(false);

  // Latest values for async callbacks, updated in effects (never during render).
  const optionsRef = useRef(options);
  const enabledRef = useRef(false);
  useEffect(() => {
    optionsRef.current = options;
  }, [options]);
  useEffect(() => {
    enabledRef.current = enabled;
  }, [enabled]);

  // What the panel knew about each message (activity trace, request snapshots, eval ref), by id, so
  // flipping to another version and back does not lose it.
  const extrasRef = useRef<Record<string, Partial<BranchTurn>>>({});
  useEffect(() => {
    if (sending) return;
    extrasRef.current = { ...extrasRef.current, ...collectExtras(turns) };
  }, [turns, sending]);

  const turnSeq = useRef(0);

  // Capabilities, once per agent. Any failure leaves the controls hidden.
  useEffect(() => {
    let cancelled = false;
    setEnabled(false);
    setEditingIndex(null);
    extrasRef.current = {};
    if (!agentId) return undefined;
    void Promise.resolve()
      .then(() => api.getChatBranchingCapabilities(agentId))
      .then((caps: { enabled?: boolean }) => {
        if (!cancelled) setEnabled(caps?.enabled === true);
      })
      .catch(() => {
        /* flag off, older core, or no run access: the controls simply stay hidden */
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  // A new conversation closes any open editor.
  useEffect(() => {
    if (!sessionId) setEditingIndex(null);
  }, [sessionId]);

  /**
   * Asks the server for the active path and puts its ids on the panel's turns. When the server has
   * not yet recorded the newest reply (it learns about turns lazily) it asks once more shortly
   * after, rather than relabelling the conversation from a path that is a turn behind.
   */
  const syncPath = useCallback(async (sid: string | null): Promise<void> => {
    const aid = optionsRef.current.agentId;
    if (!aid || !sid || !enabledRef.current) return;
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        const path = await api.getBranchPath(aid, sid);
        const current = optionsRef.current;
        if (current.sessionId && current.sessionId !== sid) return;
        // Behind the panel: the newest reply is not recorded yet, or a newer turn has started
        // since this call began. Applying it would drop local text, so wait once, then give up.
        if (path.messages.length < current.turns.length) {
          if (attempt === 0) {
            await new Promise((resolve) => setTimeout(resolve, SYNC_RETRY_MS));
            continue;
          }
          return;
        }
        current.setTurns((cur) => mergeServerPath(cur, path) ?? turnsFromPath(path, extrasRef.current));
        return;
      }
    } catch {
      /* branching labels are an enhancement; a failed read just leaves the controls off */
    }
  }, []);

  /** Streams one edit or regenerate through the ordinary chat transport and relabels the turns after. */
  const runBranchTurn = useCallback(
    async (kind: "edit" | "regenerate", index: number, text: string) => {
      const o = optionsRef.current;
      const sid = o.sessionId;
      const aid = o.agentId;
      const target = o.turns[index];
      if (!aid || !sid || !target?.messageId) return;
      const previousTurns = o.turns;
      const route =
        kind === "edit" ? branchEditPath(aid, sid, target.messageId) : branchRegeneratePath(aid, sid, target.messageId);
      const turnRef = `turn-${Date.now()}-b${turnSeq.current++}`;

      o.setError(null);
      setEditingIndex(null);
      o.setTurns(kind === "edit" ? turnsForEdit(previousTurns, index, text) : turnsForRegenerate(previousTurns, index));
      o.setSending(true);

      let assistantText = "";
      let turnSteps: ActivityStep[] = [];
      const patchLast = (patch: Partial<BranchTurn>) =>
        o.setTurns((cur) => {
          const copy = [...cur];
          copy[copy.length - 1] = { ...copy[copy.length - 1], role: "assistant", ...patch };
          return copy;
        });
      try {
        const newSessionId = await api.streamChat(
          aid,
          text,
          sid,
          (chunk: string) => {
            assistantText += chunk;
            patchLast({ content: assistantText });
          },
          undefined,
          turnRef,
          undefined,
          undefined,
          { onController: o.setChatController },
          {
            onEvent: (event) => {
              if (event?.type === "heartbeat") return;
              turnSteps = [...turnSteps, normalizeServerStep(event, turnSteps.length + 1)];
              patchLast({ steps: turnSteps });
            },
          },
          route
        );
        patchLast({ ref: turnRef });
        if (newSessionId) o.setSessionId(newSessionId);
        if (assistantText.trim()) o.onTurnComplete?.();
        o.setSending(false);
        await syncPath(newSessionId || sid);
      } catch (err: any) {
        o.setError(err?.message || t("switchFailed"));
        o.setSending(false);
        // The server leaves the conversation as it was when a turn is refused up front, and keeps
        // what it recorded otherwise; show whichever it holds, and fall back to the old view.
        try {
          const path = await api.getBranchPath(aid, sid);
          o.setTurns(turnsFromPath(path, extrasRef.current));
        } catch {
          o.setTurns(previousTurns);
        }
      }
    },
    [syncPath, t]
  );

  const startEdit = useCallback((index: number) => setEditingIndex(index), []);
  const cancelEdit = useCallback(() => setEditingIndex(null), []);

  const submitEdit = useCallback(
    (index: number, text: string) => {
      void runBranchTurn("edit", index, text);
    },
    [runBranchTurn]
  );

  const regenerate = useCallback(
    (index: number) => {
      const text = userTextBefore(optionsRef.current.turns, index);
      if (text == null) return;
      void runBranchTurn("regenerate", index, text);
    },
    [runBranchTurn]
  );

  /** Flip to the previous (-1) or next (+1) version of the message at `index`. */
  const switchSibling = useCallback(
    async (index: number, delta: number) => {
      const o = optionsRef.current;
      const sid = o.sessionId;
      const aid = o.agentId;
      const turn = o.turns[index];
      if (!aid || !sid || !turn?.messageId || o.sending) return;
      setSwitching(true);
      setEditingIndex(null);
      try {
        const siblings = await api.getBranchSiblings(aid, sid, turn.messageId);
        const targetId = siblingIdAt(siblings, delta);
        if (!targetId) return;
        const path = await api.setBranchActiveLeaf(aid, sid, targetId);
        o.setError(null);
        o.setTurns(turnsFromPath(path, extrasRef.current));
      } catch {
        o.setError(t("switchFailed"));
      } finally {
        setSwitching(false);
      }
    },
    [t]
  );

  return {
    /** The server offers branching (app.chat.branching.enabled). */
    enabled,
    /** Controls may show: offered, a server session exists, and no reply came from on-device. */
    available: enabled && !!sessionId && canBranch(turns),
    editingIndex,
    switching,
    startEdit,
    cancelEdit,
    submitEdit,
    regenerate,
    switchSibling,
    /** Call after an ordinary turn completes so its messages get ids. */
    syncAfterTurn: syncPath,
  };
}
