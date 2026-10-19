"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import { errorMessage } from "../../lib/errors";
import { useAuth } from "../AuthProvider";
import { ensureAssistantAgent, type AssistantAgentResult } from "./assistantAgent";

export type AssistantAgentState =
  | { status: "loading" }
  | { status: "ready"; agent: Extract<AssistantAgentResult, { status: "ready" }>["agent"]; created: boolean }
  | { status: "no-model"; message: string | null }
  | { status: "error"; message: string };

/** Finds or creates the signed-in user's Assistant agent once per mount; `retry` runs it again. */
// One setup at a time per page: React StrictMode mounts effects twice in development, and two concurrent
// ensure calls on a fresh workspace would each create an "Assistant" agent. The in-flight promise is
// shared; it is dropped once settled so a retry runs a fresh setup.
let inflightSetup: Promise<AssistantAgentResult> | null = null;

function runSetup(profile: Parameters<typeof ensureAssistantAgent>[1]): Promise<AssistantAgentResult> {
  if (inflightSetup === null) {
    inflightSetup = ensureAssistantAgent(assistantApi, profile).finally(() => {
      inflightSetup = null;
    });
  }
  return inflightSetup;
}

export function useAssistantAgent(): AssistantAgentState & { retry: () => void } {
  const { firstName } = useAuth() as { firstName?: string | null };
  const [state, setState] = useState<AssistantAgentState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const live = useRef(true);

  useEffect(() => {
    live.current = true;
    setState({ status: "loading" });
    const profile = {
      name: firstName ?? null,
      locale: typeof navigator !== "undefined" ? navigator.language : null,
      timeZone: safeTimeZone(),
    };
    runSetup(profile)
      .then((result) => {
        if (live.current) setState(result);
      })
      .catch((err) => {
        if (live.current) setState({ status: "error", message: errorMessage(err) });
      });
    return () => {
      live.current = false;
    };
    // firstName only seeds the marker note; a late profile load must not re-run the setup.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { ...state, retry };
}

function safeTimeZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}
