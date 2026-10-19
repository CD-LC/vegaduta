"use client";

import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import CodeEditorTextarea from "../CodeEditorTextarea";
import AskAiPopover from "./AskAiPopover";
import type { useToolGeneration } from "./useToolGeneration";
import { TEMPLATES, type CustomCodeToolLanguage, type FormState } from "./model";

/**
 * The create/edit form's inputs for a custom code tool: identity, language
 * (with the Ask-AI generator), source, schema/output notes and limits. The
 * panel owns the form state, the submit and the test panel.
 */
export default function ToolFormFields({
  form,
  editing,
  set,
  chooseLanguage,
  gen,
}: {
  form: FormState;
  /** True when editing a saved tool (toolName is then immutable). */
  editing: boolean;
  set: <K extends keyof FormState>(field: K, value: FormState[K]) => void;
  chooseLanguage: (language: CustomCodeToolLanguage) => void;
  gen: ReturnType<typeof useToolGeneration>;
}) {
  const t = useTranslations("customCodeTools");
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="cct-tool-name">{t("toolNameLabel")}</Label>
          <Input
            id="cct-tool-name"
            type="text"
            placeholder={t("toolNamePlaceholder")}
            value={form.toolName}
            onChange={(e) => set("toolName", e.target.value)}
            disabled={editing}
            className="font-mono"
            aria-describedby="cct-tool-name-help"
          />
          <p id="cct-tool-name-help" className="text-xs text-muted-foreground">
            {editing ? t("toolNameImmutableNote") : t("toolNameHelp")}
          </p>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="cct-display-name">{t("displayNameLabel")}</Label>
          <Input
            id="cct-display-name"
            type="text"
            placeholder={t("displayNamePlaceholder")}
            value={form.displayName}
            onChange={(e) => set("displayName", e.target.value)}
          />
        </div>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="cct-description">{t("descriptionLabel")}</Label>
        <Textarea
          id="cct-description"
          placeholder={t("descriptionPlaceholder")}
          value={form.description}
          onChange={(e) => set("description", e.target.value)}
          rows={2}
          aria-describedby="cct-description-help"
        />
        <p id="cct-description-help" className="text-xs text-muted-foreground">
          {t("descriptionHelp")}
        </p>
      </div>

      <div className="space-y-1.5">
        <Label>{t("languageLabel")}</Label>
        <div className="flex flex-wrap items-center gap-2">
          {(["JAVA", "PYTHON"] as CustomCodeToolLanguage[]).map((lang) => (
            <Button
              key={lang}
              type="button"
              size="sm"
              variant={form.language === lang ? "default" : "outline"}
              aria-pressed={form.language === lang}
              onClick={() => chooseLanguage(lang)}
            >
              {lang === "PYTHON" ? t("languagePython") : t("languageJava")}
            </Button>
          ))}

          <AskAiPopover gen={gen} language={form.language} onChooseLanguage={chooseLanguage} />
        </div>
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <Label htmlFor="cct-source">{t("sourceCodeLabel")}</Label>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => set("sourceCode", TEMPLATES[form.language])}
          >
            {t("insertTemplateButton")}
          </Button>
        </div>
        <CodeEditorTextarea
          id="cct-source"
          value={form.sourceCode}
          onChange={(next) => set("sourceCode", next)}
          aria-label={t("sourceCodeLabel")}
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="cct-schema">{t("inputSchemaLabel")}</Label>
          <Textarea
            id="cct-schema"
            placeholder={t("inputSchemaPlaceholder")}
            value={form.inputSchemaJson}
            onChange={(e) => set("inputSchemaJson", e.target.value)}
            rows={3}
            className="font-mono text-xs"
            aria-describedby="cct-schema-help"
          />
          <p id="cct-schema-help" className="text-xs text-muted-foreground">
            {t("inputSchemaHelp")}
          </p>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="cct-output">{t("outputDescriptionLabel")}</Label>
          <Textarea
            id="cct-output"
            placeholder={t("outputDescriptionPlaceholder")}
            value={form.outputDescription}
            onChange={(e) => set("outputDescription", e.target.value)}
            rows={3}
            aria-describedby="cct-output-help"
          />
          <p id="cct-output-help" className="text-xs text-muted-foreground">
            {t("outputDescriptionHelp")}
          </p>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="cct-timeout">{t("timeoutLabel")}</Label>
          <Input
            id="cct-timeout"
            type="number"
            min={100}
            step={100}
            value={form.timeoutMs}
            onChange={(e) => set("timeoutMs", Number(e.target.value))}
            className="tabular-nums"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="cct-memory">{t("memoryLabel")}</Label>
          <Input
            id="cct-memory"
            type="number"
            min={16}
            step={16}
            value={form.memoryMb}
            onChange={(e) => set("memoryMb", Number(e.target.value))}
            className="tabular-nums"
          />
        </div>
      </div>
    </>
  );
}
