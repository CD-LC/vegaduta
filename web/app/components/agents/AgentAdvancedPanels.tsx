"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Check, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";

// Five per-agent opt-ins that already had backend endpoints but no UI:
//   response caching            PATCH /api/agents/{id}/response-caching           {enabled}
//   semantic response caching   PATCH /api/agents/{id}/semantic-response-caching  {enabled}
//   share memory                PATCH /api/agents/{id}/share-memory               {enabled}
//   knowledge base              PATCH /api/agents/{id}/knowledge-base             {enabled}
//   collaborators               PATCH /api/agents/{id}/collaborators              {collaboratorAgentIds}
// Each returns the updated AgentResponse, handed to onSaved.

type SaveState = "idle" | "saving" | "saved" | "error";

interface AgentLike {
  id: string;
  responseCachingEnabled?: boolean;
  semanticResponseCachingEnabled?: boolean;
  shareMemoryWithTenant?: boolean;
  knowledgeBaseEnabled?: boolean;
  collaboratorAgentIds?: string[] | null;
}

export interface AgentAdvancedPanelsProps {
  agent: AgentLike;
  /** Shows the controls but makes them non-interactive. */
  disabled?: boolean;
  /** Line shown when disabled, e.g. the edit-lock explanation. */
  disabledReason?: string;
  /** Called with the updated agent returned by the server after each save. */
  onSaved?: (updated: any) => void;
}

interface ToggleSpec {
  key: string;
  title: string;
  help: string;
  checked: boolean;
  save: (enabled: boolean) => Promise<any>;
}

export default function AgentAdvancedPanels({ agent, disabled, disabledReason, onSaved }: AgentAdvancedPanelsProps) {
    const tHC = useTranslations("hcComponentsAgentsAgentAdvancedPanels");
  const uid = useId();
  const [state, setState] = useState<Record<string, SaveState>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [others, setOthers] = useState<{ id: string; name: string }[] | null>(null);
  const [othersError, setOthersError] = useState<string | null>(null);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    const t = timers.current;
    return () => t.forEach((id) => window.clearTimeout(id));
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .listAgents()
      .then((list: any) => {
        if (cancelled) return;
        const arr = Array.isArray(list) ? list : list?.content || [];
        setOthers(
          arr
            .filter((a: any) => a && a.id !== agent.id)
            .map((a: any) => ({ id: String(a.id), name: String(a.name || a.id) }))
        );
      })
      .catch((e: unknown) => {
        if (!cancelled) setOthersError(e instanceof Error ? e.message : "Could not load your other agents.");
      });
    return () => {
      cancelled = true;
    };
  }, [agent.id]);

  async function run(key: string, call: () => Promise<any>) {
    if (disabled) return;
    setState((s) => ({ ...s, [key]: "saving" }));
    setErrors((e) => ({ ...e, [key]: "" }));
    try {
      const updated = await call();
      setState((s) => ({ ...s, [key]: "saved" }));
      timers.current.push(window.setTimeout(() => setState((s) => ({ ...s, [key]: "idle" })), 2000));
      onSaved?.(updated);
    } catch (e: unknown) {
      setState((s) => ({ ...s, [key]: "error" }));
      setErrors((er) => ({ ...er, [key]: e instanceof Error ? e.message : "Could not save that setting." }));
    }
  }

  const toggles: ToggleSpec[] = [
    {
      key: "responseCaching",
      title: "Response caching",
      help: "If someone asks exactly the same thing again, reuse the earlier answer instead of paying for a new one. Faster and cheaper, but the answer will not reflect anything that changed since.",
      checked: !!agent.responseCachingEnabled,
      save: (v) => api.setAgentResponseCaching(agent.id, v),
    },
    {
      key: "semanticCaching",
      title: "Semantic response caching",
      help: "Also reuse answers for questions that mean nearly the same thing, even when worded differently. Saves more, with a small chance of reusing an answer that does not quite fit. Stronger than the option above.",
      checked: !!agent.semanticResponseCachingEnabled,
      save: (v) => api.setAgentSemanticResponseCaching(agent.id, v),
    },
    {
      key: "shareMemory",
      title: "Share memory with your workspace",
      help: "This agent adds what it learns to a memory pool shared by your workspace's agents, and recalls from it. Its own private memory is kept either way.",
      checked: !!agent.shareMemoryWithTenant,
      save: (v) => api.setAgentShareMemory(agent.id, v),
    },
    {
      key: "knowledgeBase",
      title: "Use the knowledge base",
      help: "Let this agent search your uploaded documents and cite them when it answers.",
      checked: !!agent.knowledgeBaseEnabled,
      save: (v) => api.setAgentKnowledgeBase(agent.id, v),
    },
  ];

  const status = (key: string) => {
    const s = state[key] ?? "idle";
    return (
      <span className="inline-flex h-4 items-center text-[11px] text-muted-foreground" role="status" aria-live="polite">
        {s === "saving" && (
          <>
            <Loader2 className="me-1 size-3 animate-spin" aria-hidden="true" />
            Saving…
          </>
        )}
        {s === "saved" && (
          <>
            <Check className="me-1 size-3" aria-hidden="true" />
            Saved
          </>
        )}
        {s === "error" && <span className="text-destructive">{tHC("hcComponentsAgentsAgentAdvancedPanels.s6")}</span>}
      </span>
    );
  };

  const selected = new Set((agent.collaboratorAgentIds || []).map(String));
  const busyCollab = state.collaborators === "saving";

  function toggleCollaborator(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    void run("collaborators", () => api.setAgentCollaborators(agent.id, Array.from(next)));
  }

  return (
    <section
      aria-label={tHC("hcComponentsAgentsAgentAdvancedPanels.s5")}
      data-testid="agent-advanced-panels"
      className={disabled ? "opacity-70" : ""}
    >
      <p className="text-[12.5px] text-muted-foreground">{tHC("hcComponentsAgentsAgentAdvancedPanels.s4")}</p>
      {disabled && disabledReason && <p className="mt-2 text-[12.5px] text-muted-foreground">{disabledReason}</p>}

      <div className="mt-3 flex flex-col gap-2">
        {toggles.map((t) => {
          const id = `${uid}-${t.key}`;
          const isBusy = state[t.key] === "saving";
          return (
            <div key={t.key} className="rounded-lg border border-border bg-background px-3 py-2">
              <label htmlFor={id} className={`flex items-start gap-3 ${disabled ? "" : "cursor-pointer"}`}>
                <input
                  id={id}
                  type="checkbox"
                  role="switch"
                  className="mt-1 size-4 shrink-0"
                  checked={t.checked}
                  disabled={disabled || isBusy}
                  aria-describedby={`${id}-help`}
                  onChange={(e) => void run(t.key, () => t.save(e.target.checked))}
                />
                <span className="min-w-0 flex-1 text-sm font-medium text-foreground">{t.title}</span>
              </label>
              <div className="ms-7 mt-0.5 flex items-start justify-between gap-2">
                <p id={`${id}-help`} className="text-[12.5px] text-muted-foreground">
                  {t.help}
                </p>
                {status(t.key)}
              </div>
              {errors[t.key] && (
                <div role="alert" className="ax-kb-error mt-2">
                  {errors[t.key]}
                </div>
              )}
            </div>
          );
        })}

        <fieldset className="m-0 min-w-0 rounded-lg border border-border bg-background px-3 py-2" disabled={disabled}>
          <legend className="px-1 text-sm font-medium text-foreground">Collaborators</legend>
          <div className="flex items-start justify-between gap-2">
            <p className="text-[12.5px] text-muted-foreground">
              {tHC("hcComponentsAgentsAgentAdvancedPanels.s3")}
            </p>
            {status("collaborators")}
          </div>
          {others === null && !othersError && (
            <div className="mt-2 flex items-center gap-2 text-[12.5px] text-muted-foreground">
              <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
              {tHC("hcComponentsAgentsAgentAdvancedPanels.s2")}
            </div>
          )}
          {othersError && (
            <div role="alert" className="ax-kb-error mt-2">
              {othersError}
            </div>
          )}
          {others && others.length === 0 && (
            <p className="mt-2 text-[12.5px] text-muted-foreground">
              {tHC("hcComponentsAgentsAgentAdvancedPanels.s1")}
            </p>
          )}
          {others && others.length > 0 && (
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {others.map((o) => (
                <label
                  key={o.id}
                  className={`flex min-w-0 items-center gap-2 rounded-md border px-2 py-1.5 text-sm ${
                    selected.has(o.id) ? "border-primary bg-primary/10" : "border-border bg-card"
                  } ${disabled || busyCollab ? "opacity-60" : "cursor-pointer hover:bg-accent"}`}
                >
                  <input
                    type="checkbox"
                    checked={selected.has(o.id)}
                    disabled={disabled || busyCollab}
                    onChange={() => toggleCollaborator(o.id)}
                  />
                  <span className="truncate">{o.name}</span>
                </label>
              ))}
            </div>
          )}
          {errors.collaborators && (
            <div role="alert" className="ax-kb-error mt-2">
              {errors.collaborators}
            </div>
          )}
        </fieldset>
      </div>
    </section>
  );
}
