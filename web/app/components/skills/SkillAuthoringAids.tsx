"use client";

// Authoring and browsing aids for /skills, all computed client-side from lib/skillAuthoring:
// a live SKILL.md lint, a preview of how the skill appears in an agent's prompt, a starter-template
// picker, the list search/filter bar and a "which agents use this" list. Copy is inline English on
// purpose (same precedent as SkillStarters / SkillRegistryBrowser: en.json is fragile and shared).

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, CheckCircle2, Info, Search, XCircle } from "lucide-react";
import { api } from "../../lib/api";
import {
  SKILL_FILTERS,
  SKILL_TEMPLATES,
  agentsUsingSkill,
  estimateTokens,
  lintSkillMd,
  parseSkillMd,
  promptLine,
  type LintIssue,
  type SkillListFilter,
} from "../../lib/skillAuthoring";
import { cn } from "../../lib/utils";
import { Input } from "../ui/input";
import { ConfirmDialog } from "../ui/confirm-dialog";

const LEVEL_ICON = { error: XCircle, warning: AlertTriangle, info: Info } as const;
const LEVEL_TONE = {
  error: "text-[var(--state-error)]",
  warning: "text-[var(--state-warning)]",
  info: "text-muted-foreground",
} as const;

export function SkillLintPanel({
  draft,
  existingNames,
  editingName,
}: {
  draft: string;
  existingNames: string[];
  editingName: string | null;
}) {
  const issues: LintIssue[] = useMemo(() => lintSkillMd(draft, existingNames, editingName), [draft, existingNames, editingName]);
  const errors = issues.filter((i) => i.level === "error").length;
  const warnings = issues.filter((i) => i.level === "warning").length;
  return (
    <div className="space-y-1.5 rounded-md border border-border p-3" data-testid="skill-lint">
      <p className="flex items-center gap-1.5 text-xs font-medium text-foreground">
        {errors === 0 && warnings === 0 ? (
          <>
            <CheckCircle2 size={13} className="text-primary" aria-hidden="true" /> Checks passed
          </>
        ) : errors > 0 ? (
          <>
            <XCircle size={13} className="text-[var(--state-error)]" aria-hidden="true" /> {errors} problem{errors === 1 ? "" : "s"} the server will refuse
          </>
        ) : (
          <>
            <AlertTriangle size={13} className="text-[var(--state-warning)]" aria-hidden="true" /> Worth a look before you save
          </>
        )}
      </p>
      {issues.length > 0 && (
        <ul className="space-y-1">
          {issues.map((i) => {
            const Icon = LEVEL_ICON[i.level];
            return (
              <li key={i.code} className="flex items-start gap-1.5 text-xs leading-relaxed text-muted-foreground">
                <Icon size={12} className={cn("mt-0.5 shrink-0", LEVEL_TONE[i.level])} aria-hidden="true" />
                <span>{i.message}</span>
              </li>
            );
          })}
        </ul>
      )}
      <p className="text-[11px] text-muted-foreground">These checks mirror the server rules. The server has the final say when you save.</p>
    </div>
  );
}

export function SkillPromptPreview({ draft }: { draft: string }) {
  const p = useMemo(() => parseSkillMd(draft), [draft]);
  const ready = p.closed && p.name && p.description;
  return (
    <div className="space-y-1.5 rounded-md border border-border p-3" data-testid="skill-prompt-preview">
      <p className="text-xs font-medium text-foreground">How an agent sees it</p>
      {ready ? (
        <>
          <p className="text-[11px] text-muted-foreground">
            Every agent with this skill on carries this one line in its system prompt. The instructions load only when the agent calls
            load_skill.
          </p>
          <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-md border border-border/60 bg-muted/40 p-2 font-mono text-xs text-foreground">
            {promptLine(p.name, p.description)}
          </pre>
          <p className="text-[11px] text-muted-foreground">
            About {estimateTokens(promptLine(p.name, p.description))} tokens per turn in the list, about {estimateTokens(p.body).toLocaleString("en-US")}{" "}
            tokens when loaded.
          </p>
        </>
      ) : (
        <p className="text-[11px] text-muted-foreground">Fill in a name and description to see the line agents get.</p>
      )}
    </div>
  );
}

export function SkillTemplatePicker({ draft, onPick }: { draft: string; onPick: (skillMd: string) => void }) {
  const dirty = draft.trim() !== "" && !SKILL_TEMPLATES.some((t) => t.skillMd === draft);
  const [pending, setPending] = useState<string | null>(null);
  return (
    <div className="space-y-1.5">
      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(o) => { if (!o) setPending(null); }}
        title="Replace what you have typed with this template?"
        confirmLabel="Replace"
        onConfirm={() => { if (pending !== null) onPick(pending); }}
      />
      <p className="text-xs font-medium text-foreground">Start from a template</p>
      <div className="flex flex-wrap gap-1.5">
        {SKILL_TEMPLATES.map((t) => (
          <button
            key={t.id}
            type="button"
            title={t.blurb}
            aria-pressed={draft === t.skillMd}
            onClick={() => {
              if (dirty) {
                setPending(t.skillMd);
                return;
              }
              onPick(t.skillMd);
            }}
            className={cn(
              "rounded-full border px-2.5 py-1 text-xs shadow-none hover:bg-accent",
              draft === t.skillMd ? "border-primary bg-accent text-accent-foreground" : "border-border bg-transparent text-foreground"
            )}
          >
            {t.title}
          </button>
        ))}
      </div>
    </div>
  );
}

export function SkillFilterBar({
  query,
  onQuery,
  filter,
  onFilter,
  shown,
  total,
}: {
  query: string;
  onQuery: (q: string) => void;
  filter: SkillListFilter;
  onFilter: (f: SkillListFilter) => void;
  shown: number;
  total: number;
}) {
  return (
    <div className="space-y-2" role="search">
      <div className="relative">
        <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          placeholder="Search your skills by name or description"
          aria-label="Search skills"
          className="pl-8"
        />
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {SKILL_FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            aria-pressed={filter === f.id}
            onClick={() => onFilter(f.id)}
            className={cn(
              "rounded-full border px-2.5 py-1 text-xs shadow-none hover:bg-accent",
              filter === f.id ? "border-primary bg-accent text-accent-foreground" : "border-border bg-transparent text-muted-foreground"
            )}
          >
            {f.label}
          </button>
        ))}
        {(query || filter !== "all") && (
          <span className="ml-1 text-xs text-muted-foreground" aria-live="polite">
            {shown} of {total}
          </span>
        )}
      </div>
    </div>
  );
}

/** Agents whose own allow-list names this skill. Agents on "all skills" use every enabled skill and are not listed. */
export function SkillUsage({ attachedAgentIds }: { attachedAgentIds: string[] }) {
  const [agents, setAgents] = useState<{ id: string; name?: string | null }[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (attachedAgentIds.length === 0) return undefined;
    let cancelled = false;
    api
      .listAgents()
      .then((list: unknown) => {
        if (!cancelled) setAgents(Array.isArray(list) ? (list as { id: string; name?: string | null }[]) : []);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [attachedAgentIds.length]);
  const used = agents ? agentsUsingSkill(attachedAgentIds, agents) : [];
  return (
    <div className="space-y-1.5 rounded-md border border-border p-3" data-testid="skill-usage">
      <p className="text-sm font-medium text-foreground">Used by</p>
      {attachedAgentIds.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          No agent has picked this skill by hand. Agents set to use all skills still load it whenever it is switched on.
        </p>
      ) : failed ? (
        <p className="text-xs text-muted-foreground">
          {attachedAgentIds.length} agent{attachedAgentIds.length === 1 ? "" : "s"} picked this skill. Their names could not be loaded.
        </p>
      ) : agents === null ? (
        <p className="text-xs text-muted-foreground">Loading agents...</p>
      ) : used.length === 0 ? (
        <p className="text-xs text-muted-foreground">The agents that picked this skill are not visible to you.</p>
      ) : (
        <>
          <ul className="flex flex-wrap gap-1.5">
            {used.map((a) => (
              <li key={a.id}>
                <Link
                  href={`/agents?agentId=${encodeURIComponent(a.id)}`}
                  className="inline-block rounded-full border border-border px-2.5 py-0.5 text-xs text-foreground hover:bg-accent"
                >
                  {a.name}
                </Link>
              </li>
            ))}
          </ul>
          <p className="text-[11px] text-muted-foreground">Agents set to use all skills also load it whenever it is switched on.</p>
        </>
      )}
    </div>
  );
}
