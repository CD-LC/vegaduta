"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { NodeFormProps } from "../registryTypes";
import Select from "../../../Select";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { ScopedExpressionField } from "../../ExpressionField";
import { conditionMode } from "../../nodeDefs";

export default function ConditionForm({ config, setConfig }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
                <div className="field">
                  <Label>{t("conditionModeLabel")}</Label>
                  <Select
                    value={conditionMode(config)}
                    onChange={(e: any) =>
                      setConfig({ mode: e.target.value === "expression" ? "expression" : "contains" })
                    }
                    options={[
                      { value: "contains", label: t("conditionModeContains") },
                      { value: "expression", label: t("conditionModeExpression") },
                    ]}
                  />
                </div>
                {conditionMode(config) === "expression" ? (
                  <div className="field">
                    <Label>{t("conditionExpressionLabel")}</Label>
                    <ScopedExpressionField
                      field="expression"
                      bare
                      multiline
                      value={config.expression || ""}
                      onChange={(v) => setConfig({ expression: v })}
                      placeholder={t("conditionExpressionPlaceholder")}
                      ariaLabel={t("conditionExpressionLabel")}
                    />
                    <p className="ax-wf-empty">{t("conditionExpressionHint")}</p>
                  </div>
                ) : (
                  <div className="field">
                    <Label>{t("conditionLabel")}</Label>
                    <Input
                      type="text"
                      value={config.contains || ""}
                      onChange={(e) => setConfig({ contains: e.target.value })}
                      placeholder={t("conditionPlaceholder")}
                    />
                    <p className="ax-wf-empty">{t("conditionHint")}</p>
                  </div>
                )}
    </>
  );
}
