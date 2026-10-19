"use client";

import { Plus, Trash2 } from "lucide-react";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Switch } from "../ui/switch";
import { useTranslations } from "../../lib/i18n";
import { ScopedExpressionField } from "./ExpressionField";
import {
  AI_DOC_FORMATS,
  AI_PARAM_TYPES,
  MAX_AI_CLASSES,
  MAX_AI_PARAMS,
  MAX_AI_SOURCES,
  aiNodeProblems,
  newClass,
  newParam,
  optionalInt,
  optionsText,
  parseOptionsText,
  readClasses,
  readParams,
  readSources,
  removeAt,
  replaceAt,
  setAt,
  slugHandle,
  updateAt,
  withParamType,
  type AiNodeConfig,
  type AiNodeType,
  type AiParamType,
} from "./aiNodes";

// Config forms of the four Dify-style AI nodes (question_classifier, parameter_extractor, variable_aggregator,
// document_extractor). All strings come from the "workflowNodesAi" en.json namespace; every list edit goes through
// the immutable helpers in aiNodes.ts (the stored config is shared with the node type's defaultConfig object).

type Translator = (key: string, values?: Record<string, string | number>) => string;

type ConfigProps = {
  config: AiNodeConfig;
  setConfig: (patch: Partial<AiNodeConfig>) => void;
};

const TEXTAREA_CLASS =
  "flex w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";
const HINT = "ax-wf-empty";

/** The drawer's single entry point: renders the form for whichever AI node type is selected. */
export default function AiNodeConfigForm({ type, config, setConfig }: ConfigProps & { type: AiNodeType }) {
  const t = useTranslations("workflowNodesAi") as Translator;
  const problems = aiNodeProblems(type, config);
  return (
    <div data-testid={`ai-node-config-${type}`}>
      {type === "question_classifier" && <QuestionClassifierConfig t={t} config={config} setConfig={setConfig} />}
      {type === "parameter_extractor" && <ParameterExtractorConfig t={t} config={config} setConfig={setConfig} />}
      {type === "variable_aggregator" && <VariableAggregatorConfig t={t} config={config} setConfig={setConfig} />}
      {type === "document_extractor" && <DocumentExtractorConfig t={t} config={config} setConfig={setConfig} />}
      {problems.length > 0 && (
        <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-destructive" data-testid="ai-node-problems">
          {problems.map((p, i) => (
            <li key={`${p.code}-${i}`}>{t(`problem_${p.code}`, { value: p.value || "" })}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ModelOverride({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  return (
    <details className="field">
      <summary className="cursor-pointer text-xs text-muted-foreground">{t("modelOverrideTitle")}</summary>
      <Label className="mt-2">{t("providerLabel")}</Label>
      <Input
        type="text"
        value={config.provider || ""}
        onChange={(e) => setConfig({ provider: e.target.value })}
        placeholder={t("providerPlaceholder")}
      />
      <Label className="mt-2">{t("modelLabel")}</Label>
      <Input
        type="text"
        value={config.model || ""}
        onChange={(e) => setConfig({ model: e.target.value })}
        placeholder={t("modelPlaceholder")}
      />
      <p className={HINT}>{t("modelOverrideHint")}</p>
    </details>
  );
}

function TextOverride({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  return (
    <div className="field">
      <Label>{t("textLabel")}</Label>
      <ScopedExpressionField
        field="text"
        multiline
        value={config.text || ""}
        onChange={(v) => setConfig({ text: v })}
        placeholder={t("textPlaceholder")}
        ariaLabel={t("textLabel")}
      />
      <p className={HINT}>{t("textHint")}</p>
    </div>
  );
}

function InstructionField({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  return (
    <div className="field">
      <Label>{t("instructionLabel")}</Label>
      <textarea
        rows={2}
        aria-label={t("instructionLabel")}
        className={TEXTAREA_CLASS}
        placeholder={t("instructionPlaceholder")}
        value={config.instruction || ""}
        onChange={(e) => setConfig({ instruction: e.target.value })}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// question_classifier
// ---------------------------------------------------------------------------

function QuestionClassifierConfig({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  const classes = readClasses(config);
  const handles = classes.map((c) => c.handle.trim()).filter(Boolean);

  return (
    <>
      <p className={HINT}>{t("classifierHint")}</p>

      <div className="field">
        <Label>{t("classesLabel")}</Label>
        {classes.map((c, i) => (
          <div key={i} className="mb-2 rounded-md border border-border p-2" data-testid={`ai-class-${i}`}>
            <div className="flex items-center gap-2">
              <Input
                type="text"
                value={c.handle}
                onChange={(e) => setConfig({ classes: updateAt(classes, i, { handle: e.target.value }) })}
                onBlur={(e) => {
                  // A handle is an edge id: keep it to the safe slug alphabet once the author leaves the field.
                  const slug = slugHandle(e.target.value);
                  if (slug && slug !== e.target.value) setConfig({ classes: updateAt(classes, i, { handle: slug }) });
                }}
                placeholder={t("classHandlePlaceholder")}
                aria-label={t("classHandleLabel", { n: i + 1 })}
              />
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={() => setConfig({ classes: removeAt(classes, i) })}
                title={t("removeClassButton")}
                aria-label={t("removeClassButton")}
              >
                <Trash2 size={14} aria-hidden="true" />
              </Button>
            </div>
            <textarea
              rows={2}
              className={`${TEXTAREA_CLASS} mt-2`}
              placeholder={t("classDescriptionPlaceholder")}
              aria-label={t("classDescriptionLabel", { n: i + 1 })}
              value={c.description}
              onChange={(e) => setConfig({ classes: updateAt(classes, i, { description: e.target.value }) })}
            />
          </div>
        ))}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={classes.length >= MAX_AI_CLASSES}
          onClick={() => setConfig({ classes: [...classes, newClass(classes)] })}
        >
          <Plus size={14} aria-hidden="true" /> {t("addClassButton")}
        </Button>
        <p className={HINT}>{t("classesHandleHint")}</p>
      </div>

      <div className="field">
        <Label>{t("defaultClassLabel")}</Label>
        <Select
          value={config.defaultClass || ""}
          onChange={(e: any) => setConfig({ defaultClass: e.target.value })}
          options={[{ value: "", label: t("defaultClassFirst") }, ...handles.map((h) => ({ value: h, label: h }))]}
        />
      </div>

      <div className="field">
        <Label>{t("minConfidenceLabel")}</Label>
        <Input
          type="number"
          min={0}
          max={1}
          step={0.05}
          value={config.minConfidence ?? 0.5}
          onChange={(e) => {
            const n = Number(e.target.value);
            setConfig({ minConfidence: Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : undefined });
          }}
        />
        <p className={HINT}>{t("minConfidenceHint")}</p>
      </div>

      <TextOverride t={t} config={config} setConfig={setConfig} />
      <InstructionField t={t} config={config} setConfig={setConfig} />

      <div className="field">
        <Label>{t("outputModeLabel")}</Label>
        <Select
          value={config.outputMode === "result" ? "result" : "passthrough"}
          onChange={(e: any) => setConfig({ outputMode: e.target.value === "result" ? "result" : "passthrough" })}
          options={[
            { value: "passthrough", label: t("classifierOutput_passthrough") },
            { value: "result", label: t("classifierOutput_result") },
          ]}
        />
      </div>

      <ModelOverride t={t} config={config} setConfig={setConfig} />
    </>
  );
}

// ---------------------------------------------------------------------------
// parameter_extractor
// ---------------------------------------------------------------------------

function ParameterExtractorConfig({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  const params = readParams(config);

  return (
    <>
      <p className={HINT}>{t("extractorHint")}</p>

      <div className="field">
        <Label>{t("parametersLabel")}</Label>
        {params.map((p, i) => (
          <div key={i} className="mb-2 rounded-md border border-border p-2" data-testid={`ai-param-${i}`}>
            <div className="flex items-center gap-2">
              <Input
                type="text"
                value={p.name}
                onChange={(e) => setConfig({ parameters: updateAt(params, i, { name: e.target.value }) })}
                placeholder={t("paramNamePlaceholder")}
                aria-label={t("paramNameLabel", { n: i + 1 })}
              />
              <div className="min-w-[8rem]">
                <Select
                  value={p.type}
                  aria-label={t("paramTypeLabel", { n: i + 1 })}
                  onChange={(e: any) =>
                    setConfig({ parameters: setAt(params, i, withParamType(p, e.target.value as AiParamType)) })
                  }
                  options={AI_PARAM_TYPES.map((v) => ({ value: v, label: t(`paramType_${v}`) }))}
                />
              </div>
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={() => setConfig({ parameters: removeAt(params, i) })}
                title={t("removeParamButton")}
                aria-label={t("removeParamButton")}
              >
                <Trash2 size={14} aria-hidden="true" />
              </Button>
            </div>
            <Input
              type="text"
              className="mt-2"
              value={p.description}
              onChange={(e) => setConfig({ parameters: updateAt(params, i, { description: e.target.value }) })}
              placeholder={t("paramDescriptionPlaceholder")}
              aria-label={t("paramDescriptionLabel", { n: i + 1 })}
            />
            {p.type === "enum" && (
              <Input
                type="text"
                className="mt-2"
                value={optionsText(p)}
                onChange={(e) =>
                  setConfig({ parameters: updateAt(params, i, { options: parseOptionsText(e.target.value) }) })
                }
                placeholder={t("paramOptionsPlaceholder")}
                aria-label={t("paramOptionsLabel", { n: i + 1 })}
              />
            )}
            <div className="mt-2 flex items-center gap-2">
              <Switch
                checked={p.required}
                onCheckedChange={(on) => setConfig({ parameters: updateAt(params, i, { required: on }) })}
                aria-label={t("paramRequiredLabel")}
              />
              <span className="text-sm text-foreground">{t("paramRequiredLabel")}</span>
            </div>
          </div>
        ))}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={params.length >= MAX_AI_PARAMS}
          onClick={() => setConfig({ parameters: [...params, newParam(params)] })}
        >
          <Plus size={14} aria-hidden="true" /> {t("addParamButton")}
        </Button>
        <p className={HINT}>{t("extractorOutputHint")}</p>
      </div>

      <TextOverride t={t} config={config} setConfig={setConfig} />
      <InstructionField t={t} config={config} setConfig={setConfig} />
      <ModelOverride t={t} config={config} setConfig={setConfig} />
    </>
  );
}

// ---------------------------------------------------------------------------
// variable_aggregator
// ---------------------------------------------------------------------------

function VariableAggregatorConfig({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  const sources = readSources(config);

  return (
    <>
      <p className={HINT}>{t("aggregatorHint")}</p>

      <div className="field">
        <Label>{t("sourcesLabel")}</Label>
        {sources.map((value, i) => (
          <div key={i} className="mb-2 flex items-start gap-2" data-testid={`ai-source-${i}`}>
            <div className="min-w-0 flex-1">
              <ScopedExpressionField
                field={`sources[${i}]`}
                value={value}
                onChange={(v) => setConfig({ sources: replaceAt(sources, i, v) })}
                placeholder={t("sourcePlaceholder")}
                ariaLabel={t("sourceLabel", { n: i + 1 })}
              />
            </div>
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={() => setConfig({ sources: removeAt(sources, i) })}
              title={t("removeSourceButton")}
              aria-label={t("removeSourceButton")}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          </div>
        ))}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={sources.length >= MAX_AI_SOURCES}
          onClick={() => setConfig({ sources: [...sources, ""] })}
        >
          <Plus size={14} aria-hidden="true" /> {t("addSourceButton")}
        </Button>
      </div>

      <div className="field">
        <Label>{t("fallbackLabel")}</Label>
        <Select
          value={config.fallback === "empty" ? "empty" : "input"}
          onChange={(e: any) => setConfig({ fallback: e.target.value === "empty" ? "empty" : "input" })}
          options={[
            { value: "input", label: t("fallback_input") },
            { value: "empty", label: t("fallback_empty") },
          ]}
        />
      </div>
      <p className={HINT}>{t("aggregatorLimitHint")}</p>
    </>
  );
}

// ---------------------------------------------------------------------------
// document_extractor
// ---------------------------------------------------------------------------

function DocumentExtractorConfig({ t, config, setConfig }: { t: Translator } & ConfigProps) {
  return (
    <>
      <p className={HINT}>{t("documentHint")}</p>

      <div className="field">
        <Label>{t("documentSourceLabel")}</Label>
        <ScopedExpressionField
          field="source"
          value={config.source || ""}
          onChange={(v) => setConfig({ source: v })}
          placeholder={t("documentSourcePlaceholder")}
          ariaLabel={t("documentSourceLabel")}
        />
        <p className={HINT}>{t("documentSourceHint")}</p>
      </div>

      <div className="field">
        <Label>{t("formatLabel")}</Label>
        <Select
          value={config.format || "auto"}
          onChange={(e: any) => setConfig({ format: e.target.value })}
          options={AI_DOC_FORMATS.map((f) => ({ value: f, label: t(`format_${f}`) }))}
        />
      </div>

      <div className="field">
        <Label>{t("documentOutputLabel")}</Label>
        <Select
          value={config.outputMode === "json" ? "json" : "text"}
          onChange={(e: any) => setConfig({ outputMode: e.target.value === "json" ? "json" : "text" })}
          options={[
            { value: "text", label: t("documentOutput_text") },
            { value: "json", label: t("documentOutput_json") },
          ]}
        />
      </div>

      <div className="field">
        <Label>{t("limitsLabel")}</Label>
        <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
          <div>
            <Label>{t("maxCharsLabel")}</Label>
            <Input
              type="number"
              min={500}
              value={config.maxChars ?? ""}
              onChange={(e) => setConfig({ maxChars: optionalInt(e.target.value, 500, 1_000_000) })}
              placeholder={t("limitPlaceholder")}
            />
          </div>
          <div>
            <Label>{t("maxPagesLabel")}</Label>
            <Input
              type="number"
              min={1}
              value={config.maxPages ?? ""}
              onChange={(e) => setConfig({ maxPages: optionalInt(e.target.value, 1, 1000) })}
              placeholder={t("limitPlaceholder")}
            />
          </div>
          <div>
            <Label>{t("maxRowsLabel")}</Label>
            <Input
              type="number"
              min={1}
              value={config.maxRows ?? ""}
              onChange={(e) => setConfig({ maxRows: optionalInt(e.target.value, 1, 100000) })}
              placeholder={t("limitPlaceholder")}
            />
          </div>
        </div>
        <p className={HINT}>{t("limitsHint")}</p>
      </div>
    </>
  );
}
