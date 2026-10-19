"use client";

import Select from "../Select";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import WaitNodeExplainer from "../explainers/WaitNodeExplainer";
import { ScopedExpressionField } from "./ExpressionField";
import { WAIT_MODES, WAIT_UNITS, isExpression, parseWaitMode, parseWaitUnit } from "./workflowControl";

type Props = {
  config: any;
  setConfig: (patch: any) => void;
};

/**
 * Wait node config. Three modes - a duration, a fixed/expression timestamp, or a webhook-style resume - all of
 * which park the run durably on the server (core WorkflowWaitService); nothing here holds a timer. `duration` and
 * `until` accept {{ }} expressions (resolved when the run reaches the node), so they use the expression field.
 */
export default function WaitNodeConfig({ config, setConfig }: Props) {
  const t = useTranslations("workflowForms");
  const mode = parseWaitMode(config);

  return (
    <div data-testid="wait-node-config">
      <div className="field">
        <Label>{t("waitModeLabel")}</Label>
        <Select
          value={mode}
          onChange={(e: any) => setConfig({ mode: e.target.value })}
          options={WAIT_MODES.map((m) => ({
            value: m,
            label: t(m === "duration" ? "waitModeDuration" : m === "until" ? "waitModeUntil" : "waitModeWebhook"),
          }))}
        />
      </div>

      {mode === "duration" && (
        <div className="row" style={{ gap: "0.5rem", alignItems: "flex-end" }}>
          <div className="field" style={{ flex: 1 }}>
            <Label>{t("waitDurationLabel")}</Label>
            <ScopedExpressionField
              field="duration"
              placeholder="30"
              value={String(config.duration ?? "")}
              onChange={(v) => setConfig({ duration: v })}
              ariaLabel={t("waitDurationLabel")}
            />
          </div>
          {!isExpression(config.duration) && /^\d+(\.\d+)?$/.test(String(config.duration ?? "").trim()) && (
            <div className="field" style={{ minWidth: "7rem" }}>
              <Label>{t("waitUnitLabel")}</Label>
              <Select
                value={parseWaitUnit(config.unit)}
                onChange={(e: any) => setConfig({ unit: e.target.value })}
                options={WAIT_UNITS.map((u) => ({ value: u, label: t(`unit_${u}`) }))}
              />
            </div>
          )}
        </div>
      )}

      {mode === "until" && (
        <div className="field">
          <Label>{t("waitUntilLabel")}</Label>
          <ScopedExpressionField
            field="until"
            placeholder={t("waitUntilPlaceholder")}
            value={String(config.until ?? "")}
            onChange={(v) => setConfig({ until: v })}
            ariaLabel={t("waitUntilLabel")}
          />
          <p className="ax-wf-empty">{t("waitUntilHint")}</p>
        </div>
      )}

      {mode === "webhook" && (
        <>
          <div className="field">
            <Label>{t("waitTimeoutLabel")}</Label>
            <Input
              type="number"
              min={1}
              value={config.timeout ?? ""}
              onChange={(e) =>
                setConfig(
                  e.target.value === ""
                    ? { timeout: undefined, timeoutUnit: undefined }
                    : { timeout: Math.max(1, Number(e.target.value)), timeoutUnit: "hours" },
                )
              }
            />
          </div>
          <div className="field">
            <Label>{t("waitOnTimeoutLabel")}</Label>
            <Select
              value={String(config.onTimeout ?? "").toLowerCase() === "continue" ? "continue" : "fail"}
              onChange={(e: any) => setConfig({ onTimeout: e.target.value })}
              options={[
                { value: "fail", label: t("waitOnTimeoutFail") },
                { value: "continue", label: t("waitOnTimeoutContinue") },
              ]}
            />
          </div>
          <p className="ax-wf-empty">{t("waitWebhookHint")}</p>
        </>
      )}

      <p className="ax-wf-empty">{t("waitCapHint")}</p>
      <p className="ax-wf-empty">{t("waitFeatureHint")}</p>
      <WaitNodeExplainer mode={mode} className="mt-2" />
    </div>
  );
}
