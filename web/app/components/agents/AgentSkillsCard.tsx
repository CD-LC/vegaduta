"use client";

// Per-agent skill scope (core /api/agents/{id}/skills). Defaults are "on, every
// enabled workspace skill" with no row stored server-side, so an agent that was
// never touched here already has skills - this card only narrows that.
//
// `locked` comes from the parent because the FREE-tier edit-lock rule is a
// per-control obligation (see AgentGuardrailsCard): a locked agent gets a
// disabled control and a tooltip, not a PUT that the server rejects.

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Loader2, Sparkles } from "lucide-react";
import { api, type AgentSkills, type DistillPattern, type SkillSummary } from "../../lib/api";
import type { SkillSecurityReport, SkillSecuritySummary } from "../../lib/api/skillSecurity";
import { confirmBeforeAttach, isFlagged, riskLabelKey, summaryByName } from "../../lib/skillSecurity";
import SkillRiskBadge from "../skills/SkillRiskBadge";
import { useAuth } from "../AuthProvider";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import BrandText from "../BrandText";

export default function AgentSkillsCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("agentSkillsCard");
  const { hasPermission } = useAuth();
  const [settings, setSettings] = useState<AgentSkills | null>(null);
  const [catalog, setCatalog] = useState<SkillSummary[]>([]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  // Static supply-chain scan per skill (core /api/skill-security). Empty when scanning is off
  // or unavailable to this user - the card then looks exactly as it did before.
  const [securityByName, setSecurityByName] = useState<Map<string, SkillSecuritySummary>>(new Map());
  // A custom skill the user just ticked that is flagged, changed, or not yet approved (the first attach
  // pins its content): shown the scan result and asked to confirm.
  const [attachPrompt, setAttachPrompt] = useState<{
    name: string;
    summary: SkillSecuritySummary;
    report: SkillSecurityReport | null;
  } | null>(null);
  // Skill distillation (V395): scan this agent's successful runs for repeated
  // tool-call sequences, preview the patterns, then stage them as DISABLED
  // draft skills - nothing reaches a prompt until an admin enables it.
  const canDistill = hasPermission("MANAGE_AGENTS");
  const [distillOpen, setDistillOpen] = useState(false);
  const [distillPatterns, setDistillPatterns] = useState<DistillPattern[] | null>(null);
  const [distillBusy, setDistillBusy] = useState(false);
  const [distillNotice, setDistillNotice] = useState<string | null>(null);
  const [distillSkipped, setDistillSkipped] = useState<Record<string, string> | null>(null);

  const load = useCallback(() => {
    setFailed(false);
    Promise.all([api.getAgentSkills(agentId), api.listSkills(), api.getSkillSecurityOverview()])
      .then(([s, all, overview]) => {
        setSettings(s);
        setCatalog(all.filter((k) => k.enabled));
        setSecurityByName(summaryByName(overview));
      })
      .catch(() => setFailed(true));
  }, [agentId]);

  useEffect(() => {
    setSettings(null);
    load();
  }, [load]);

  const save = async (skillsEnabled: boolean, allowedSkillNames: string[]) => {
    if (locked || busy) return;
    setBusy(true);
    try {
      setSettings(await api.updateAgentSkills(agentId, { skillsEnabled, allowedSkillNames }));
    } catch {
      // api.ts already raised the toast; re-read so the card shows server truth.
      load();
    } finally {
      setBusy(false);
    }
  };

  const flaggedCount = catalog.filter((k) => isFlagged(securityByName.get(k.name))).length;
  const restricted = (settings?.allowedSkillNames.length ?? 0) > 0;
  const disabled = locked || busy;

  // Ticking a custom skill that is flagged, changed since approval, or not approved yet shows the
  // scan result first (the first attach is what approves its content); unticking, built-ins and
  // already-approved clean skills go straight through.
  const toggleSkill = (name: string) => {
    if (!settings) return;
    if (!settings.allowedSkillNames.includes(name)) {
      const summary = securityByName.get(name);
      if (summary && confirmBeforeAttach(summary)) {
        void askBeforeAttach(name, summary);
        return;
      }
    }
    applyToggle(name);
  };

  const askBeforeAttach = async (name: string, summary: SkillSecuritySummary) => {
    const report = await api.getSkillSecurityReport(name);
    setAttachPrompt({ name, summary, report });
  };

  const applyToggle = (name: string) => {
    if (!settings) return;
    const current = new Set(settings.allowedSkillNames);
    if (current.has(name)) current.delete(name);
    else current.add(name);
    // An empty allow-list means "unrestricted" server-side, so unticking the last
    // skill must not silently flip this agent to ALL skills - what the user asked
    // for is none, which is the master switch.
    if (current.size === 0) void save(false, []);
    else void save(settings.skillsEnabled, [...current]);
  };

  // Read-only preview first (GET) so the admin sees WHICH patterns would be
  // staged before anything is written; the dialog's confirm runs the POST.
  const scanDistill = async () => {
    if (distillBusy || locked) return;
    setDistillBusy(true);
    try {
      setDistillPatterns(await api.previewSkillDistillation(agentId));
      setDistillSkipped(null);
      setDistillNotice(null);
      setDistillOpen(true);
    } catch {
      // api.ts already raised the toast (403 on non-admin included).
    } finally {
      setDistillBusy(false);
    }
  };

  const stageDistill = async () => {
    setDistillBusy(true);
    try {
      const result = await api.distillSkills(agentId);
      setDistillOpen(false);
      setDistillNotice(
        result.proposedSkillNames.length > 0
          ? t("distillStagedNotice", { count: result.proposedSkillNames.length })
          : null
      );
      setDistillSkipped(Object.keys(result.skipped).length > 0 ? result.skipped : null);
      load();
    } catch {
      /* toast already shown */
    } finally {
      setDistillBusy(false);
    }
  };

  return (
    <div className="mt-6 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground">
            <BrandText text={t("title")} />
          </h3>
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
            <BrandText text={t("subtitle")} />{" "}
            <Link href="/skills" className="text-primary underline-offset-2 hover:underline">
              {t("manageLink")}
            </Link>
          </p>
        </div>
        {settings ? (
          <button
            type="button"
            role="switch"
            aria-checked={settings.skillsEnabled}
            aria-label={t("title")}
            disabled={disabled}
            title={locked ? editLockedTooltip : undefined}
            className={cn(
              "ax-toggle mt-0.5 shrink-0",
              settings.skillsEnabled && "ax-toggle-on",
              locked && "cursor-not-allowed opacity-40"
            )}
            onClick={() => save(!settings.skillsEnabled, settings.allowedSkillNames)}
          />
        ) : (
          !failed && <Loader2 size={16} className="mt-1 shrink-0 animate-spin text-muted-foreground" />
        )}
      </div>

      {failed && <p className="mt-3 text-xs text-muted-foreground">{t("loadFailed")}</p>}

      {settings?.skillsEnabled && (
        <div className="mt-4 space-y-3">
          <div className="flex flex-wrap gap-2" aria-label={t("scopeAria")}>
            {([false, true] as const).map((wantRestricted) => (
              <button
                key={String(wantRestricted)}
                type="button"
                aria-pressed={restricted === wantRestricted}
                disabled={disabled || (wantRestricted && catalog.length === 0)}
                title={locked ? editLockedTooltip : undefined}
                className={cn(
                  "rounded-full px-3 py-1 text-xs",
                  restricted === wantRestricted
                    ? "border-primary bg-accent text-accent-foreground"
                    : "text-muted-foreground"
                )}
                onClick={() => {
                  if (restricted === wantRestricted) return;
                  // Switching to "selected" starts from what the agent uses today, so the
                  // change is a no-op until the user unticks something.
                  void save(true, wantRestricted ? settings.activeSkills.map((s) => s.name) : []);
                }}
              >
                {wantRestricted ? t("scopeSelected") : t("scopeAll")}
              </button>
            ))}
          </div>

          {restricted ? (
            <ul className="grid gap-1.5 sm:grid-cols-2">
              {catalog.map((skill) => {
                const sec = securityByName.get(skill.name);
                return (
                  <li key={skill.name}>
                    <label
                      className={cn(
                        "flex items-start gap-2 text-xs",
                        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer"
                      )}
                      title={skill.description}
                    >
                      <input
                        type="checkbox"
                        className="mt-0.5"
                        checked={settings.allowedSkillNames.includes(skill.name)}
                        disabled={disabled}
                        onChange={() => toggleSkill(skill.name)}
                      />
                      <span className="min-w-0">
                        <span className="font-mono text-foreground">{skill.name}</span>
                        <span className="ms-1.5 text-muted-foreground">
                          {skill.source === "BUILT_IN" ? t("builtIn") : t("custom")}
                        </span>
                        {isFlagged(sec) && (
                          <span className="ms-1.5 inline-flex flex-wrap gap-1 align-middle">
                            {sec.status === "NEEDS_REVIEW" && (
                              <SkillRiskBadge level="medium" label={t("statusChanged")} />
                            )}
                            {sec.riskLevel !== "none" && sec.riskLevel !== "low" && (
                              <SkillRiskBadge level={sec.riskLevel} label={t(riskLabelKey(sec.riskLevel))} />
                            )}
                          </span>
                        )}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          ) : (
            <div className="space-y-1">
              <p className="text-xs text-muted-foreground">
                {t("allSummary", { count: settings.activeSkills.length })}
              </p>
              {flaggedCount > 0 && (
                <p className="text-xs text-muted-foreground">
                  {t("flaggedHint", { count: flaggedCount })}{" "}
                  <Link href="/skills" className="text-primary underline-offset-2 hover:underline">
                    {t("attachReviewLink")}
                  </Link>
                </p>
              )}
            </div>
          )}
        </div>
      )}

      {settings && canDistill && (
        <div className="mt-4 space-y-2 border-t border-border pt-3">
          <Button
            variant="outline"
            size="sm"
            disabled={disabled || distillBusy}
            title={locked ? editLockedTooltip : undefined}
            onClick={scanDistill}
          >
            <Sparkles size={14} /> {distillBusy ? t("distillScanning") : t("distillButton")}
          </Button>
          {distillNotice && <p className="text-xs text-muted-foreground">{distillNotice}</p>}
          {distillSkipped && (
            <p className="text-xs text-muted-foreground">
              {t("distillSkippedNotice", {
                count: Object.keys(distillSkipped).length,
                names: Object.keys(distillSkipped).join(", "),
              })}
            </p>
          )}
        </div>
      )}

      <AlertDialog open={!!attachPrompt} onOpenChange={(o) => !o && setAttachPrompt(null)}>
        <AlertDialogContent>
          {attachPrompt && (
            <>
              <AlertDialogHeader>
                <AlertDialogTitle>{t("attachTitle", { name: attachPrompt.name })}</AlertDialogTitle>
                <AlertDialogDescription>
                  {attachPrompt.summary.status === "NEEDS_REVIEW"
                    ? t("attachBodyChanged")
                    : attachPrompt.summary.riskLevel === "medium" || attachPrompt.summary.riskLevel === "high"
                      ? t("attachBodyRisk", { level: t(riskLabelKey(attachPrompt.summary.riskLevel)) })
                      : t("attachBodyFirst")}
                </AlertDialogDescription>
              </AlertDialogHeader>
              {attachPrompt.report && attachPrompt.report.scan.findings.length > 0 && (
                <div className="space-y-1">
                  <p className="text-xs font-medium text-foreground">{t("attachExamples")}</p>
                  <ul className="space-y-1">
                    {attachPrompt.report.scan.findings.slice(0, 3).map((f, i) => (
                      <li key={`${f.rule}:${i}`}>
                        <code className="block max-h-16 overflow-auto whitespace-pre-wrap break-words rounded bg-muted/50 px-2 py-1 font-mono text-xs text-foreground">
                          {f.excerpt}
                        </code>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <p className="text-xs leading-relaxed text-muted-foreground">
                {t("attachDisclaimer")}{" "}
                <Link href="/skills" className="text-primary underline-offset-2 hover:underline">
                  {t("attachReviewLink")}
                </Link>
              </p>
              <AlertDialogFooter>
                <AlertDialogCancel>{t("attachCancel")}</AlertDialogCancel>
                <AlertDialogAction
                  onClick={() => {
                    const name = attachPrompt.name;
                    setAttachPrompt(null);
                    applyToggle(name);
                  }}
                >
                  {t("attachConfirm")}
                </AlertDialogAction>
              </AlertDialogFooter>
            </>
          )}
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={distillOpen} onOpenChange={setDistillOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("distillTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("distillIntro")}</AlertDialogDescription>
          </AlertDialogHeader>
          {distillPatterns && distillPatterns.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("distillEmpty")}</p>
          ) : (
            <ul className="max-h-64 space-y-2 overflow-y-auto">
              {(distillPatterns ?? []).map((p) => (
                <li key={p.tools.join(">")} className="rounded-md border border-border p-3">
                  <p className="flex flex-wrap items-center gap-1 font-mono text-xs text-foreground">
                    {p.tools.map((tool, i) => (
                      <span key={i} className="flex items-center gap-1">
                        {i > 0 && <ArrowRight size={10} className="text-muted-foreground" />}
                        {tool}
                      </span>
                    ))}
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {t("distillSessions", {
                      sessions: p.distinctSessions,
                      runs: p.runs,
                      reward: p.meanReward.toFixed(2),
                    })}
                  </p>
                  {p.sampleGoals.length > 0 && (
                    <p className="mt-0.5 truncate text-xs text-muted-foreground">
                      {t("distillGoals", { goals: p.sampleGoals.join(" · ") })}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={distillBusy}>{t("distillCancel")}</AlertDialogCancel>
            {distillPatterns && distillPatterns.length > 0 && (
              <AlertDialogAction
                disabled={distillBusy}
                onClick={(e) => {
                  e.preventDefault();
                  void stageDistill();
                }}
              >
                {t("distillStage", { count: distillPatterns.length })}
              </AlertDialogAction>
            )}
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
