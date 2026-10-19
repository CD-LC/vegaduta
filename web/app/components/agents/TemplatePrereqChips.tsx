"use client";

import { useMemo } from "react";
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../ui/badge";
import { templateChips, type TemplateChip } from "./templateAudience";

/**
 * Short requirement chips on a template card ("Needs your documents", "Brave
 * Search optional", "Drafts only - never sends"). They come from the same
 * prerequisite data the setup guide behind the card's button reads
 * (templatePrereqs.ts), so the card and the guide cannot disagree. The chips
 * only state what the template needs - nothing here checks the workspace, so
 * none of them ever says "ready" or "connected".
 */
export default function TemplatePrereqChips({
  templateKey,
  category,
  suggestKnowledgeBase,
}: {
  templateKey: string;
  category?: string;
  suggestKnowledgeBase?: boolean;
}) {
  const t = useTranslations("agentTemplates");
  const chips = useMemo<TemplateChip[]>(
    () => templateChips(templateKey, { category, suggestKnowledgeBase }),
    [templateKey, category, suggestKnowledgeBase]
  );
  if (chips.length === 0) return null;
  return (
    <ul
      className="mt-3 flex flex-wrap gap-1.5"
      aria-label={t("chipsAriaLabel")}
      data-testid={`template-chips-${templateKey}`}
    >
      {chips.map((chip) => (
        <li key={chip.id}>
          <Badge variant={chip.tone === "required" ? "outline" : chip.tone === "info" ? "accent" : "secondary"}>
            {t(chip.labelKey, chip.params)}
          </Badge>
        </li>
      ))}
    </ul>
  );
}
