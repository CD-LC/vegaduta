"use client";

import type { SkillSummary } from "../../lib/api";
import type { SkillSecurityOverview } from "../../lib/api/skillSecurity";
import { riskLabelKey } from "../../lib/skillSecurity";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Badge } from "../ui/badge";
import { Card, CardContent } from "../ui/card";
import SkillRiskBadge from "./SkillRiskBadge";

type SkillSecuritySummary = SkillSecurityOverview["skills"][number];

/** One skill in SkillsPanel's lists: name, source/draft/risk badges, description, and the on/off switch. */
export default function SkillRow({
  skill,
  selected,
  sec,
  canManageSkills,
  busy,
  onOpen,
  onToggle,
}: {
  skill: SkillSummary;
  selected: boolean;
  sec: SkillSecuritySummary | undefined;
  canManageSkills: boolean;
  busy: boolean;
  onOpen: () => void;
  onToggle: () => void;
}) {
  const t = useTranslations("skillsPage");
  return (
    <li>
      <Card className={cn("transition-shadow", selected && "ring-1 ring-primary/40")}>
        <CardContent className="flex items-start gap-3 p-4">
          <button
            type="button"
            onClick={onOpen}
            className="min-w-0 flex-1 border-0 bg-transparent p-0 text-start shadow-none"
          >
            <span className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-semibold text-foreground">{skill.name}</span>
              <Badge variant={skill.source === "BUILT_IN" ? "accent" : "secondary"}>
                {skill.source === "BUILT_IN" ? t("badgeBuiltIn") : t("badgeCustom")}
              </Badge>
              {skill.createdBy?.startsWith("agent:") && (
                <Badge variant="outline" title={t("agentDraftHint", { by: skill.createdBy })}>
                  {skill.enabled ? t("badgeAgentAuthored") : t("badgeAgentDraft")}
                </Badge>
              )}
              {skill.pendingImprovement && (
                <Badge variant="state" state="var(--state-warning)">
                  {t("badgeStagedRewrite")}
                </Badge>
              )}
              {sec && (
                <SkillRiskBadge
                  level={sec.riskLevel}
                  label={t(riskLabelKey(sec.riskLevel))}
                  title={t("riskBadgeTitle", { level: t(riskLabelKey(sec.riskLevel)) })}
                />
              )}
              {sec?.status === "NEEDS_REVIEW" && (
                <Badge variant="state" state="var(--state-warning)">
                  {t("statusChanged")}
                </Badge>
              )}
              {sec?.status === "UNPINNED" && sec.riskLevel === "high" && (
                <Badge variant="state" state="var(--state-error)">
                  {t("statusUnapproved")}
                </Badge>
              )}
              {skill.fileCount > 0 && (
                <span className="text-xs text-muted-foreground">{t("fileCount", { count: skill.fileCount })}</span>
              )}
            </span>
            <span
              className={cn("mt-1 block text-sm leading-relaxed text-muted-foreground", !skill.enabled && "opacity-60")}
            >
              {skill.description}
            </span>
          </button>
          {canManageSkills ? (
            <button
              type="button"
              role="switch"
              aria-checked={skill.enabled}
              aria-label={t("toggleAria", { name: skill.name })}
              disabled={busy}
              className={cn("ax-toggle mt-0.5 shrink-0", skill.enabled && "ax-toggle-on")}
              onClick={onToggle}
            />
          ) : (
            !skill.enabled && <Badge variant="outline">{t("badgeOff")}</Badge>
          )}
        </CardContent>
      </Card>
    </li>
  );
}
