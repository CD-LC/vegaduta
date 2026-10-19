"use client";

import Select from "../Select";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import ErrorStrategyExplainer from "../explainers/ErrorStrategyExplainer";
import {
  RETRY_MAX_ATTEMPTS,
  RETRY_MAX_BACKOFF_MS,
  isIrreversibleNode,
  normalizeRetry,
  parseOnError,
  retryFieldPatch,
  retryPatch,
  supportsErrorStrategy,
} from "./workflowControl";

type Props = {
  nodeType: string;
  config: any;
  setConfig: (patch: any) => void;
};

/**
 * Per-node error strategy: `onError` (fail | continue | branch) and a bounded `retry` block. Written straight
 * into the node's config JSON (no schema change). Core ignores both unless the deployment has the node error
 * strategy switched on, which the hint says; the red "error" port on the node is still how a failure branch is
 * wired. A node type that can send or change something outside the workflow shows the duplicate-effect warning
 * and the explicit opt-in the server also requires.
 */
export default function NodeErrorStrategyEditor({ nodeType, config, setConfig }: Props) {
  const t = useTranslations("workflowForms");
  if (!supportsErrorStrategy(nodeType)) return null;
  const onError = parseOnError(config.onError);
  const retry = normalizeRetry(config.retry);
  const risky = isIrreversibleNode(nodeType, config);

  return (
    <div className="field" style={{ marginTop: "0.5rem" }} data-testid="error-strategy-editor">
      <Label>{t("errorStrategyHeading")}</Label>
      <Select
        value={onError}
        onChange={(e: any) => setConfig({ onError: e.target.value === "fail" ? undefined : e.target.value })}
        options={[
          { value: "fail", label: t("onError_fail") },
          { value: "continue", label: t("onError_continue") },
          { value: "branch", label: t("onError_branch") },
        ]}
        aria-label={t("onErrorLabel")}
      />

      <label className="row" style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}>
        <input
          type="checkbox"
          checked={!!retry}
          onChange={(e) => setConfig(retryPatch(e.target.checked, config.retry))}
        />
        {t("retryEnabledLabel")}
      </label>

      {retry && (
        <>
          <div className="row" style={{ gap: "0.5rem" }}>
            <div className="field" style={{ flex: 1 }}>
              <Label>{t("retryMaxAttemptsLabel")}</Label>
              <Input
                type="number"
                min={1}
                max={RETRY_MAX_ATTEMPTS}
                value={retry.maxAttempts}
                onChange={(e) => setConfig(retryFieldPatch(config.retry, { maxAttempts: Number(e.target.value) }))}
              />
            </div>
            <div className="field" style={{ flex: 1 }}>
              <Label>{t("retryBackoffMsLabel")}</Label>
              <Input
                type="number"
                min={0}
                max={RETRY_MAX_BACKOFF_MS}
                step={100}
                value={retry.backoffMs}
                onChange={(e) => setConfig(retryFieldPatch(config.retry, { backoffMs: Number(e.target.value) }))}
              />
            </div>
          </div>
          <div className="row" style={{ gap: "0.5rem" }}>
            <div className="field" style={{ flex: 1 }}>
              <Label>{t("retryBackoffKindLabel")}</Label>
              <Select
                value={retry.backoff}
                onChange={(e: any) => setConfig(retryFieldPatch(config.retry, { backoff: e.target.value }))}
                options={[
                  { value: "fixed", label: t("backoff_fixed") },
                  { value: "exponential", label: t("backoff_exponential") },
                ]}
              />
            </div>
            <div className="field" style={{ flex: 1 }}>
              <Label>{t("retryOnLabel")}</Label>
              <Select
                value={retry.retryOn}
                onChange={(e: any) => setConfig(retryFieldPatch(config.retry, { retryOn: e.target.value }))}
                options={[
                  { value: "all", label: t("retryOn_all") },
                  { value: "transient", label: t("retryOn_transient") },
                ]}
              />
            </div>
          </div>
          {risky && (
            <>
              <p className="ax-wf-empty" role="note">{t("retryRiskHint")}</p>
              <label className="row" style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)" }}>
                <input
                  type="checkbox"
                  checked={config.retryUnsafe === true}
                  onChange={(e) => setConfig({ retryUnsafe: e.target.checked ? true : undefined })}
                />
                {t("retryUnsafeLabel")}
              </label>
            </>
          )}
        </>
      )}
      <p className="ax-wf-empty">{t("errorStrategyHint")}</p>
      <ErrorStrategyExplainer className="mt-2" />
    </div>
  );
}
