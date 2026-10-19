"use client";

import { useState } from "react";
import { ChevronDown, Wand2 } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { INDUSTRY_CREATIVE_PACKS } from "../../creative/industryPrompts";
import { PERSONA_PACKS } from "../../creative/personaPrompts";

/**
 * The "tailor the suggestions" disclosure under Magic Studio's prompt box: an industry row and a
 * separately labelled role row, each re-flavouring the suggestion chips above it.
 */
export default function TailorPickers({
  industryId,
  onIndustryChange,
  industryName,
  personaId,
  onPersonaChange,
  personaName,
}: {
  industryId: string | null;
  onIndustryChange: (id: string | null) => void;
  industryName: string | null;
  personaId: string | null;
  onPersonaChange: (id: string | null) => void;
  personaName: string | null;
}) {
  const t = useTranslations("landing");
  // Disclosure for the industry/persona rows (collapsed by default — see the
  // render comment at the pickers).
  const [showPickers, setShowPickers] = useState(false);
  return (
  <div className="mt-3">
    <button
      type="button"
      onClick={() => setShowPickers((v) => !v)}
      className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground transition hover:text-foreground"
      aria-expanded={showPickers || industryId !== null || personaId !== null}
    >
      <Wand2 className="h-3 w-3 shrink-0" aria-hidden />
      {industryId || personaId
        ? t("magic_tailoredSuggestions", { name: industryName ?? personaName ?? "" })
        : t("magic_tailorPrompt")}
      <ChevronDown
        className={`h-3 w-3 transition-transform ${showPickers || industryId || personaId ? "rotate-180" : ""}`}
        aria-hidden
      />
    </button>
    {(showPickers || industryId !== null || personaId !== null) && (
      <div className="mt-3 space-y-3">
        <div>
          <p className="mb-1.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {t("magic_businessQuestion")}
          </p>
          <div className="flex flex-wrap gap-1.5">
            <button
              type="button"
              onClick={() => onIndustryChange(null)}
              className={`rounded-full border px-3 py-1 text-xs transition ${
                industryId === null
                  ? "border-primary/60 bg-primary/10 text-primary"
                  : "border-border/50 bg-background/40 text-muted-foreground hover:border-primary/40 hover:text-foreground"
              }`}
            >
              {t("magic_general")}
            </button>
            {INDUSTRY_CREATIVE_PACKS.map((pack) => {
              const Icon = pack.icon;
              const active = industryId === pack.id;
              return (
                <button
                  key={pack.id}
                  type="button"
                  onClick={() => onIndustryChange(active ? null : pack.id)}
                  className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition ${
                    active
                      ? "border-primary/60 bg-primary/10 text-primary"
                      : "border-border/50 bg-background/40 text-muted-foreground hover:border-primary/40 hover:text-foreground"
                  }`}
                >
                  <Icon className="h-3 w-3" aria-hidden />
                  {pack.name}
                </button>
              );
            })}
          </div>
        </div>
        <div>
          <p className="mb-1.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {t("magic_roleQuestion")}
          </p>
          <div className="flex flex-wrap gap-1.5">
            <button
              type="button"
              onClick={() => onPersonaChange(null)}
              className={`rounded-full border px-3 py-1 text-xs transition ${
                personaId === null
                  ? "border-primary/60 bg-primary/10 text-primary"
                  : "border-border/50 bg-background/40 text-muted-foreground hover:border-primary/40 hover:text-foreground"
              }`}
            >
              {t("magic_general")}
            </button>
            {PERSONA_PACKS.map((pack) => {
              const Icon = pack.icon;
              const active = personaId === pack.id;
              return (
                <button
                  key={pack.id}
                  type="button"
                  onClick={() => onPersonaChange(active ? null : pack.id)}
                  className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition ${
                    active
                      ? "border-primary/60 bg-primary/10 text-primary"
                      : "border-border/50 bg-background/40 text-muted-foreground hover:border-primary/40 hover:text-foreground"
                  }`}
                >
                  <Icon className="h-3 w-3" aria-hidden />
                  {pack.name}
                </button>
              );
            })}
          </div>
        </div>
      </div>
    )}
  </div>
  );
}
