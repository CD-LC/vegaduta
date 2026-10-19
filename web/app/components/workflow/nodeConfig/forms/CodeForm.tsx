"use client";

import Select from "../../../Select";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { ScopedExpressionField } from "../../ExpressionField";
import { type WorkflowNodeConfig, CODE_OPERATION_VALUES, type CodeOperation } from "../types";

function codeOperationLabelKey(value: CodeOperation): string {
  return {
    extract: "operationExtract",
    regex_replace: "operationRegexReplace",
    template: "operationTemplate",
    uppercase: "operationUppercase",
    lowercase: "operationLowercase",
    json_parse: "operationJsonParse",
    json_stringify: "operationJsonStringify",
  }[value];
}

/** Transform / Code node config - #705. */
export function CodeNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const operation = config.operation || "extract";
  const codeOperations = CODE_OPERATION_VALUES.map((value) => ({ value, label: t(codeOperationLabelKey(value)) }));

  return (
    <>
      <div className="field">
        <Label>{t("operationLabel")}</Label>
        <Select value={operation} onChange={(e) => setConfig({ operation: e.target.value })} options={codeOperations} />
      </div>

      {operation === "extract" && (
        <div className="field">
          <Label>{t("fieldDotPathLabel")}</Label>
          <Input
            type="text"
            value={config.field || ""}
            onChange={(e) => setConfig({ field: e.target.value })}
            placeholder={t("fieldDotPathPlaceholder")}
          />
        </div>
      )}

      {operation === "regex_replace" && (
        <>
          <div className="field">
            <Label>{t("patternLabel")}</Label>
            <Input
              type="text"
              value={config.pattern || ""}
              onChange={(e) => setConfig({ pattern: e.target.value })}
              placeholder={t("patternPlaceholder")}
            />
          </div>
          <div className="field">
            <Label>{t("replacementLabel")}</Label>
            <Input
              type="text"
              value={config.replacement || ""}
              onChange={(e) => setConfig({ replacement: e.target.value })}
              placeholder={t("replacementPlaceholder")}
            />
          </div>
        </>
      )}

      {operation === "template" && (
        <div className="field">
          <Label>{t("templateLabel")}</Label>
          <ScopedExpressionField
            field="template"
            multiline
            value={config.template || "{{input}}"}
            onChange={(v) => setConfig({ template: v })}
            placeholder={t("templatePlaceholder")}
            ariaLabel={t("templateLabel")}
          />
          <p className="ax-wf-empty">
            {"{{input}}"}
            {t("templateHintPrefix")}
            {"{{name}}"}.
          </p>
        </div>
      )}

      {(operation === "uppercase" ||
        operation === "lowercase" ||
        operation === "json_parse" ||
        operation === "json_stringify") && <p className="ax-wf-empty">{t("noExtraConfigHint")}</p>}
    </>
  );
}

// ---------------------------------------------------------------------------
// HTTP Request node (WorkflowNodeType.HTTP / http_request tool sibling).
// ---------------------------------------------------------------------------

