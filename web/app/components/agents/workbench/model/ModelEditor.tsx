"use client";

import { ProviderHealthWarning } from "../../../ProviderHealthWarning";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import ModelInfoCard, { modelOptionLabel } from "../../ModelInfoCard";
import { useWorkbench } from "../context";

/** Saved agent: the inline provider/model editor opened by the Change model button. */
export default function ModelEditor() {
  const {
    t,
    isDraft,
    onPlatformLlm,
    providers,
    modelEditing,
    setModelEditing,
    modelDraft,
    setModelDraft,
    modelDraftUseCustom,
    modelDraftCatalog,
    modelDraftLoading,
    setModelDraftUseCustom,
    modelDraftCatalogError,
    modelBusy,
    handleUpdateModel,
  } = useWorkbench();
  if (isDraft || !modelEditing) return null;
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2">
      {/* Clarity fix (2026-08-12, direct report: "what is this? why
       * its showing anthropic?" — a user saw this editor's model
       * dropdown, e.g. an Anthropic model like Claude, directly
       * below IntelligenceSourceControl's "Your request will be
       * processed by Hosted" line and assumed that model was what
       * actually answered. This editor sets the agent's OWN
       * provider/model, which is real config, but it's a no-op for
       * every turn while the tenant is on the platform-hosted pool
       * — that routing decision lives entirely in onPlatformLlm,
       * unrelated to what's picked here. IntelligenceSourceControl
       * already shows the REAL serving model correctly; this note
       * closes the gap on the OTHER model picker, which had no
       * such context. w-full so it lands on its own row above the
       * provider/model controls in this flex-wrap. */}
      {onPlatformLlm && (
        <p className="w-full text-xs text-muted-foreground">{t("modelEditorInertWhilePlatformPool")}</p>
      )}
      <Select
        aria-label={t("chooseProvider")}
        id="agent-model-edit-provider-select"
        name="agent-model-edit-provider-select"
        data-testid="agent-model-edit-provider-select"
        placeholder={t("chooseProvider")}
        value={modelDraft.providerName}
        onChange={(e) => setModelDraft((d) => ({ ...d, providerName: e.target.value, model: "" }))}
        options={providers.map((p) => ({ value: p.providerName, label: p.providerName }))}
      />
      <ProviderHealthWarning providerName={modelDraft.providerName} providers={providers} />
      {/* Provider-scoped catalog dropdown, same pattern as the
       * creation form above and AgentsPanel.js's own editor -
       * an actual provider switch clears model so this can't save
       * a stale model string left over from a different provider
       * (the exact Gemini/Claude mismatch bug this pattern was
       * built to close). The editor's *initial* load (opened with
       * the agent's already-saved provider/model) does NOT clear -
       * see modelEditorInitialLoadRef in the fetch effect above. */}
      {modelDraftUseCustom || modelDraftCatalog.length === 0 ? (
        <div className="flex min-w-[200px] flex-1 items-center gap-2">
          <Input
            aria-label={t("chooseModel")}
            type="text"
            value={modelDraft.model}
            onChange={(e) => setModelDraft((d) => ({ ...d, model: e.target.value }))}
            placeholder={modelDraftLoading ? t("loadingModels") : t("modelExamplePlaceholder")}
            className="flex-1"
            maxLength={128}
          />
          {modelDraftCatalog.length > 0 && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setModelDraftUseCustom(false)}
              title={t("useList")}
            >
              {t("useList")}
            </Button>
          )}
        </div>
      ) : (
        <Select
          aria-label={t("chooseModel")}
          id="agent-model-edit-model-select"
          name="agent-model-edit-model-select"
          data-testid="agent-model-edit-model-select"
          placeholder={t("chooseModel")}
          searchable
          value={modelDraft.model}
          onChange={(e) => {
            if (e.target.value === "__custom__") {
              setModelDraftUseCustom(true);
              return;
            }
            setModelDraft((d) => ({ ...d, model: e.target.value }));
          }}
          options={[
            { value: "", label: t("chooseModel") },
            ...modelDraftCatalog.map((m) => ({
              value: m.modelId,
              label: modelOptionLabel(m, t("modelDeprecatedLabel")),
            })),
            { value: "__custom__", label: t("otherManual") },
          ]}
        />
      )}
      {modelDraftCatalogError && (
        <p className="w-full text-xs text-muted-foreground">{t("modelCatalogLoadError")}</p>
      )}
      <Button
        type="button"
        size="sm"
        disabled={modelBusy || !modelDraft.model.trim()}
        onClick={handleUpdateModel}
      >
        {modelBusy ? t("saving") : t("save")}
      </Button>
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={modelBusy}
        onClick={() => setModelEditing(false)}
      >
        {t("cancel")}
      </Button>
      {/* w-full drops the metadata card onto its own row below the
       * inline provider/model/save controls in this flex-wrap. */}
      {!modelDraftUseCustom && (
        <ModelInfoCard className="w-full" model={modelDraftCatalog.find((m) => m.modelId === modelDraft.model)} />
      )}
    </div>
  );
}
