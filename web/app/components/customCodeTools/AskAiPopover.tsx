"use client";

import { AlertTriangle, Sparkles } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Label } from "../ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { Textarea } from "../ui/textarea";
import { GEN_TEMPLATE_PRESETS, type CustomCodeToolLanguage } from "./model";
import type { ToolGeneration } from "./useToolGeneration";

/** "Ask AI to generate the tool" popover. State lives in useToolGeneration. */
export default function AskAiPopover({
  gen,
  language,
  onChooseLanguage,
}: {
  gen: ToolGeneration;
  language: CustomCodeToolLanguage;
  onChooseLanguage: (language: CustomCodeToolLanguage) => void;
}) {
  const t = useTranslations("customCodeTools");
  const { genResult, genOrigin, generating } = gen;
  return (
    <Popover open={gen.open} onOpenChange={(open: boolean) => (open ? gen.setOpen(true) : gen.reset())}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" className="ml-auto gap-1.5">
          <Sparkles aria-hidden="true" size={13} /> {t("askAiTriggerLabel")}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96 space-y-3 p-4">
        <div className="space-y-0.5">
          <div className="text-sm font-semibold text-foreground">{t("askAiPopoverTitle")}</div>
          <p className="text-xs text-muted-foreground">{t("askAiPopoverSubtitle")}</p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="cct-gen-requirement">{t("askAiRequirementLabel")}</Label>
          <Textarea
            id="cct-gen-requirement"
            rows={3}
            value={gen.requirementInput}
            onChange={(e) => gen.setRequirementInput(e.target.value)}
            placeholder={t("askAiRequirementPlaceholder")}
            className="text-xs"
            autoFocus
          />
        </div>

        <div className="space-y-1.5">
          <Label>{t("askAiTemplateLabel")}</Label>
          <div className="flex flex-wrap gap-1.5">
            {GEN_TEMPLATE_PRESETS.map((preset) => (
              <Button
                key={preset.id}
                type="button"
                size="sm"
                variant={gen.templateChoice === preset.id ? "default" : "outline"}
                className="h-7 px-2 text-xs"
                aria-pressed={gen.templateChoice === preset.id}
                onClick={() => gen.setTemplateChoice(preset.id)}
              >
                {t(preset.labelKey)}
              </Button>
            ))}
          </div>
          {gen.templateChoice === "custom" && (
            <Textarea
              rows={2}
              value={gen.customTemplate}
              onChange={(e) => gen.setCustomTemplate(e.target.value)}
              placeholder={t("askAiCustomTemplatePlaceholder")}
              className="text-xs"
            />
          )}
        </div>

        <div className="space-y-1.5">
          <Label>{t("languageLabel")}</Label>
          <div className="flex gap-2">
            {(["JAVA", "PYTHON"] as CustomCodeToolLanguage[]).map((lang) => (
              <Button
                key={lang}
                type="button"
                size="sm"
                variant={language === lang ? "default" : "outline"}
                aria-pressed={language === lang}
                onClick={() => onChooseLanguage(lang)}
              >
                {lang === "PYTHON" ? t("languagePython") : t("languageJava")}
              </Button>
            ))}
          </div>
        </div>

        {gen.error && (
          <div className="rounded-md border border-[var(--danger-border)] bg-[var(--danger-bg)] px-2.5 py-1.5 text-xs text-[var(--danger-text)]">
            {gen.error}
          </div>
        )}

        {genResult && (
          <div
            className={cn(
              "space-y-1.5 rounded-md border p-2.5",
              genResult.valid
                ? "border-[var(--success-border)] bg-[var(--success-bg)]"
                : "border-[var(--danger-border)] bg-[var(--danger-bg)]"
            )}
          >
            <div
              className={cn(
                "flex items-center gap-1.5 text-xs font-semibold",
                genResult.valid ? "text-[var(--success-text)]" : "text-[var(--danger-text)]"
              )}
            >
              {!genResult.valid && <AlertTriangle aria-hidden="true" size={13} />}
              {genResult.valid ? t("askAiGeneratedValidTitle") : t("askAiGeneratedInvalidTitle")}
            </div>
            <p className={cn("text-xs", genResult.valid ? "text-[var(--success-text)]" : "text-[var(--danger-text)]")}>
              {genResult.valid ? t("askAiGeneratedValidBody") : t("askAiGeneratedInvalidBody")}
            </p>
            {genResult.errors.length > 0 && (
              <ul className="list-disc space-y-0.5 pl-4 font-mono text-[0.6875rem] text-[var(--danger-text)]">
                {genResult.errors.map((err, i) => (
                  <li key={i}>{err.message || JSON.stringify(err)}</li>
                ))}
              </ul>
            )}
            {genResult.warnings.length > 0 && (
              <ul className="list-disc space-y-0.5 pl-4 text-[0.6875rem] text-muted-foreground">
                {genResult.warnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        <div className="flex items-center justify-between gap-2 pt-0.5">
          <span className="text-[0.6875rem] text-muted-foreground">
            {genOrigin === "edge" ? t("askAiOriginEdge") : genOrigin === "server" ? t("askAiOriginServer") : ""}
          </span>
          <div className="flex gap-1.5">
            <Button type="button" size="sm" variant="ghost" onClick={gen.reset} disabled={generating}>
              {t("cancelButton")}
            </Button>
            <Button type="button" size="sm" onClick={gen.generate} disabled={generating}>
              {generating
                ? t("askAiGeneratingEllipsis")
                : genResult
                  ? t("askAiRegenerateButton")
                  : t("askAiGenerateButton")}
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
