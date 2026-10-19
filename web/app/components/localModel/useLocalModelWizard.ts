"use client";

import { useCallback, useEffect, useReducer, useRef } from "react";
import { api as defaultApi } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import type {
  LocalModelProbe,
  LocalModelPullState,
  LocalModelRecommendation,
  LocalModelUseResult,
} from "../../lib/api/localModels";
import { initialWizardState, selectedChoice, wizardReducer, type WizardState } from "./wizardMachine";

/** The slice of the API client the wizard needs; injectable so tests and the UI preview run without a backend. */
export interface LocalModelWizardApi {
  getLocalModelRecommendation: () => Promise<LocalModelRecommendation>;
  startLocalModelPull: (tag: string) => Promise<{ jobId: string }>;
  getLocalModelPull: (jobId: string) => Promise<LocalModelPullState>;
  probeLocalModel: (tag: string) => Promise<LocalModelProbe>;
  /** POST /api/local-models/use: points the pool row that serves this install's Ollama at the tag (creates it when missing). */
  useLocalModel: (tag: string) => Promise<LocalModelUseResult>;
  /** Optional: the provider + model a new agent should start on (GET /api/providers/default-model); the pool when the workspace is on it. */
  getDefaultAgentModel?: () => Promise<{ providerName: string | null; model: string | null; source?: string } | null>;
  createAgent: (payload: object) => Promise<{ id?: string; name?: string }>;
}

/**
 * The provider every agent routed through the pool carries (AgentService stores it; the pool picks the row per call).
 * The wizard used to create a per-agent OLLAMA credential instead, which made the same model read as "platform"
 * on one screen and "your own provider" on another.
 */
export const POOL_PROVIDER = "PLATFORM_HOSTED";
const POLL_MS = 1000;
/** Consecutive failed status reads tolerated before the pull is reported as failed (a core restart is not a failed download). */
const MAX_POLL_ERRORS = 5;

export interface UseLocalModelWizardOptions {
  api?: LocalModelWizardApi;
  pollMs?: number;
  /** Name and system prompt for the first agent; defaults are plain English, callers pass translated copy. */
  agentName?: (tag: string) => string;
  agentPrompt?: string;
}

export function useLocalModelWizard(options: UseLocalModelWizardOptions = {}) {
  const api = options.api ?? (defaultApi as unknown as LocalModelWizardApi);
  const pollMs = options.pollMs ?? POLL_MS;
  const agentName = options.agentName;
  const agentPrompt = options.agentPrompt ?? "You are a helpful assistant. Answer clearly and concisely.";
  const [state, dispatch] = useReducer(wizardReducer, initialWizardState);
  const { phase, selectedTag } = state;
  // Creating an agent is not idempotent: guard against a duplicate run (React StrictMode re-mounts effects in dev).
  const createdFor = useRef<string | null>(null);

  // detect
  useEffect(() => {
    if (phase !== "detecting") return undefined;
    let live = true;
    api
      .getLocalModelRecommendation()
      .then((recommendation) => live && dispatch({ type: "loaded", recommendation }))
      .catch((e) => live && dispatch({ type: "failed", step: "detect", message: errorMessage(e, "") }));
    return () => {
      live = false;
    };
  }, [phase, api]);

  // pull with polling
  useEffect(() => {
    if (phase !== "pulling" || !selectedTag) return undefined;
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let errors = 0;
    const poll = (jobId: string) => {
      api
        .getLocalModelPull(jobId)
        .then((s) => {
          if (!live) return;
          errors = 0;
          if (s.status === "done") {
            dispatch({ type: "pullDone" });
          } else if (s.status === "failed") {
            dispatch({ type: "failed", step: "pull", message: s.message || "" });
          } else {
            dispatch({ type: "pullProgress", percent: s.percent, message: s.message || "" });
            timer = setTimeout(() => poll(jobId), pollMs);
          }
        })
        .catch((e) => {
          if (!live) return;
          if (++errors >= MAX_POLL_ERRORS) {
            dispatch({ type: "failed", step: "pull", message: errorMessage(e, "") });
          } else {
            timer = setTimeout(() => poll(jobId), pollMs);
          }
        });
    };
    api
      .startLocalModelPull(selectedTag)
      .then(({ jobId }) => live && poll(jobId))
      .catch((e) => live && dispatch({ type: "failed", step: "pull", message: errorMessage(e, "") }));
    return () => {
      live = false;
      if (timer) clearTimeout(timer);
    };
  }, [phase, selectedTag, api, pollMs]);

  // probe (advisory: a failed probe does not stop setup)
  useEffect(() => {
    if (phase !== "probing" || !selectedTag) return undefined;
    let live = true;
    api
      .probeLocalModel(selectedTag)
      .then((probe) => live && dispatch({ type: "probed", probe }))
      .catch(() => live && dispatch({ type: "probeFailed" }));
    return () => {
      live = false;
    };
  }, [phase, selectedTag, api]);

  // point the pool row at the model (one source of truth, shared with /troubleshoot and the CLI), then create the
  // first agent on the pool like every other agent
  useEffect(() => {
    if (phase !== "creating" || !selectedTag) {
      createdFor.current = null;
      return;
    }
    if (createdFor.current === selectedTag) return;
    createdFor.current = selectedTag;
    void (async () => {
      try {
        await api.useLocalModel(selectedTag);
        // Ask what a new agent should start on. On a workspace routed through the pool this is the pool itself; if
        // the lookup is unavailable, fall back to the pool provider with the chosen tag as the model.
        let def: { providerName: string | null; model: string | null; source?: string } | null = null;
        try {
          def = (await api.getDefaultAgentModel?.()) ?? null;
        } catch {
          def = null;
        }
        const onPool = !def || def.source === "PLATFORM_POOL" || def.providerName === POOL_PROVIDER;
        const providerName = onPool ? POOL_PROVIDER : def?.providerName || POOL_PROVIDER;
        const model = onPool ? def?.model || selectedTag : def?.model || selectedTag;
        const name = agentName ? agentName(selectedTag) : `Local assistant (${selectedTag})`;
        const created = await api.createAgent({
          name,
          systemPrompt: agentPrompt,
          providerName,
          model,
          maxTokens: 1024,
          temperature: 0.7,
          topP: 1.0,
          knowledgeBaseEnabled: true,
          experienceMode: "SIMPLE",
          tuningMode: "MANUAL",
        });
        if (!created?.id) throw new Error("");
        dispatch({ type: "created", agentId: created.id, agentName: created.name || name });
      } catch (e) {
        dispatch({ type: "failed", step: "create", message: errorMessage(e, "") });
      }
    })();
    // Only the phase transition should create; agentName/agentPrompt are read once at that moment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, selectedTag, api]);

  const select = useCallback((tag: string) => dispatch({ type: "select", tag }), []);
  const acknowledge = useCallback((value: boolean) => dispatch({ type: "acknowledge", value }), []);
  const start = useCallback(() => dispatch({ type: "start" }), []);
  const recheck = useCallback(() => dispatch({ type: "detect" }), []);

  return { state: state as WizardState, choice: selectedChoice(state), select, acknowledge, start, recheck };
}
