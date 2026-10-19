"use client";

import { useEffect, useMemo, useState } from "react";
import { Loader2, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { checkNewTag, sameSpecialties, type AgentSpecialty, type TagProblem } from "../../lib/agentInsights";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";

// Crew handoff: marks this agent as a specialist for one or more role tags so
// other agents can hand sub-tasks to it (delegate_to_specialist). Self-contained
// like AgentGuardrailsCard: own fetch and state, `locked` comes from the parent
// because the edit-lock rule is a per-control obligation in AgentWorkbench.

const PROBLEM_KEY: Record<TagProblem, string> = {
  empty: "specialistsErrEmpty",
  format: "specialistsErrFormat",
  tooLong: "specialistsErrTooLong",
  duplicate: "specialistsErrDuplicate",
  tooMany: "specialistsErrTooMany",
};

export default function AgentSpecialistsCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("agentWorkbench");
  const [saved, setSaved] = useState<AgentSpecialty[]>([]);
  const [draft, setDraft] = useState<AgentSpecialty[]>([]);
  const [tagInput, setTagInput] = useState("");
  const [inputError, setInputError] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadFailed(false);
    setMessage(null);
    api
      .getAgentSpecialties(agentId)
      .then((r) => {
        if (cancelled) return;
        const list = r?.specialties ?? [];
        setSaved(list);
        setDraft(list);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    // Autocomplete roster is a nicety: failures are ignored.
    api
      .listAgentSpecialtyTags()
      .then((tags) => {
        if (!cancelled && Array.isArray(tags)) setSuggestions(tags);
      })
      .catch((): void => undefined);
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const draftTags = draft.map((s) => s.capabilityTag);
  const dirty = !sameSpecialties(saved, draft);
  const unusedSuggestions = useMemo(
    () => suggestions.filter((s) => !draftTags.includes(s)).slice(0, 8),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [suggestions, draft]
  );

  function addTag(raw: string) {
    const { tag, problem } = checkNewTag(raw, draftTags);
    if (problem) {
      setInputError(t(PROBLEM_KEY[problem]));
      return;
    }
    setInputError(null);
    setMessage(null);
    setDraft((d) => [...d, { capabilityTag: tag, description: null }]);
    setTagInput("");
  }

  function removeTag(tag: string) {
    setMessage(null);
    setDraft((d) => d.filter((s) => s.capabilityTag !== tag));
  }

  async function save() {
    if (locked || saving || !dirty) return;
    setSaving(true);
    setMessage(null);
    try {
      const r = await api.setAgentSpecialties(agentId, draft);
      const list = r?.specialties ?? draft;
      setSaved(list);
      setDraft(list);
      setMessage({ kind: "ok", text: t("specialistsSaved") });
    } catch (e: unknown) {
      setMessage({ kind: "error", text: e instanceof Error && e.message ? e.message : t("specialistsSaveError") });
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mb-4" data-testid="agent-specialists-card" title={locked ? editLockedTooltip : undefined}>
      <div className="mb-1 text-xs text-muted-foreground">{t("specialistsTitle")}</div>
      <p className="mb-2 text-xs text-muted-foreground">{t("specialistsHint")}</p>

      {loading && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {t("specialistsLoading")}
        </div>
      )}

      {!loading && loadFailed && <div className="text-xs text-muted-foreground">{t("specialistsLoadError")}</div>}

      {!loading && !loadFailed && (
        <>
          <div className="mb-2 flex flex-wrap items-center gap-1.5">
            {draft.length === 0 && <span className="text-xs text-muted-foreground">{t("specialistsEmpty")}</span>}
            {draft.map((s) => (
              <Badge key={s.capabilityTag} variant="secondary" className="gap-1 font-mono">
                {s.capabilityTag}
                <button
                  type="button"
                  className="rounded-sm hover:opacity-80 disabled:opacity-40"
                  aria-label={t("specialistsRemove", { tag: s.capabilityTag })}
                  disabled={locked || saving}
                  onClick={() => removeTag(s.capabilityTag)}
                >
                  <X className="size-3" aria-hidden="true" />
                </button>
              </Badge>
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="h-8 max-w-56"
              value={tagInput}
              disabled={locked || saving}
              placeholder={t("specialistsPlaceholder")}
              aria-label={t("specialistsPlaceholder")}
              onChange={(e) => {
                setTagInput(e.target.value);
                setInputError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  addTag(tagInput);
                }
              }}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={locked || saving}
              onClick={() => addTag(tagInput)}
            >
              {t("specialistsAdd")}
            </Button>
            <Button type="button" size="sm" disabled={locked || saving || !dirty} onClick={() => void save()}>
              {saving ? t("specialistsSaving") : t("specialistsSave")}
            </Button>
          </div>

          {inputError && <div className="mt-1 text-xs text-destructive">{inputError}</div>}

          {unusedSuggestions.length > 0 && !locked && (
            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              <span>{t("specialistsSuggested")}</span>
              {unusedSuggestions.map((s) => (
                <button
                  key={s}
                  type="button"
                  className="rounded-full border border-border px-2 py-0.5 font-mono hover:bg-accent"
                  disabled={saving}
                  onClick={() => addTag(s)}
                >
                  {s}
                </button>
              ))}
            </div>
          )}

          {message && (
            <div
              className={message.kind === "ok" ? "mt-1 text-xs text-muted-foreground" : "mt-1 text-xs text-destructive"}
              role="status"
            >
              {message.text}
            </div>
          )}
        </>
      )}
    </div>
  );
}
