"use client";

import { Volume2 } from "lucide-react";
import { DefaultModelNotice, ProviderHealthWarning } from "../../../ProviderHealthWarning";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import ModelInfoCard, { modelOptionLabel } from "../../ModelInfoCard";
import TextToSpeechProviderSelector from "../../TextToSpeechProviderSelector";
import VideoProviderSelector from "../../VideoProviderSelector";
import VoiceProviderSelector from "../../VoiceProviderSelector";
import { useWorkbench } from "../context";

/** Creation form only: provider health, the default-model notice, the model picker and the transcription and voice-reply providers. */
export default function DraftModelFields() {
  const {
    t,
    isDraft,
    onPlatformLlm,
    isFreePlan,
    providerName,
    providers,
    defaultModelNotice,
    draftModelUseCustom,
    draftModelCatalog,
    draftModelLoading,
    model,
    setModel,
    setDraftModelUseCustom,
    draftModelCatalogError,
    voiceModelProvider,
    setVoiceModelProvider,
    setVoiceModel,
    busy,
    videoModelProvider,
    setVideoModelProvider,
    setVideoModel,
    ttsProvider,
    setTtsProvider,
    setTtsModel,
  } = useWorkbench();
  return (
    <>
      {/* 2026-07-21 fix: same gate as the provider Select above - this free-text model
       * field was completely unrestricted for FREE-tier drafts too. Hidden entirely for
       * isFreePlan rather than shown-disabled, since a disabled field pre-filled with
       * nothing would just be confusing next to the badge above that already explains what
       * model is actually used.
       * 2026-09-25 fix (live report: "when platform pool is selected still its showing
       * model option"): gate generalized from isFreePlan to onPlatformLlm - a paid
       * tenant opted into the managed pool hit the exact same bug the provider Select
       * gate was fixed for (WB-014): the badge above says the pick is ignored, yet a
       * fully interactive model dropdown still rendered. onPlatformLlm includes
       * isFreePlan by definition, so FREE-tier behavior is unchanged. */}
      {/* Provider-scoped, searchable model catalog for the creation form
       * (direct feedback). Loads on provider select via the draft catalog
       * effect above. `searchable` filters long model lists; the
       * "Other (manual)" row / useCustom flag keeps the free-text escape
       * hatch so an unlisted model id is never blocked, and an empty
       * selection still means "use server default" (the placeholder). */}
      {isDraft && !onPlatformLlm && (
        <ProviderHealthWarning providerName={providerName} providers={providers} className="mb-2" />
      )}
      {isDraft && !onPlatformLlm && (
        <DefaultModelNotice notice={defaultModelNotice} providerName={providerName} className="mb-2" />
      )}
      {isDraft && !onPlatformLlm && (
        <div className="mb-3">
          {draftModelUseCustom || draftModelCatalog.length === 0 ? (
            <div className="flex items-center gap-2">
              <Input
                aria-label={t("chooseModel")}
                type="text"
                placeholder={draftModelLoading ? t("loadingModels") : t("modelOptionalPlaceholder")}
                value={model}
                onChange={(e) => setModel(e.target.value)}
                className="w-full"
                maxLength={128}
              />
              {draftModelCatalog.length > 0 && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setDraftModelUseCustom(false)}
                  title={t("useList")}
                >
                  {t("useList")}
                </Button>
              )}
            </div>
          ) : null}
          {draftModelCatalogError && (
            <p className="mt-1.5 text-xs text-muted-foreground">{t("modelCatalogLoadError")}</p>
          )}
          {!(draftModelUseCustom || draftModelCatalog.length === 0) && (
            <Select
              aria-label={t("chooseModel")}
              id="agent-draft-model-select"
              name="agent-draft-model-select"
              data-testid="agent-draft-model-select"
              placeholder={t("modelOptionalPlaceholder")}
              searchable
              value={model}
              onChange={(e) => {
                if (e.target.value === "__custom__") {
                  setDraftModelUseCustom(true);
                  return;
                }
                setModel(e.target.value);
              }}
              options={[
                { value: "", label: t("modelOptionalPlaceholder") },
                ...draftModelCatalog.map((m) => ({
                  value: m.modelId,
                  label: modelOptionLabel(m, t("modelDeprecatedLabel")),
                })),
                { value: "__custom__", label: t("otherManual") },
              ]}
            />
          )}
          {/* Catalog metadata for whatever is currently picked - the API
           * has always returned capabilitySummary/contextWindow/modality
           * flags per model, this is the first surface that shows them.
           * Hidden on the free-text path (no catalog row to describe). */}
          {!draftModelUseCustom && (
            <ModelInfoCard className="mt-2" model={draftModelCatalog.find((m) => m.modelId === model)} />
          )}
        </div>
      )}

      {/* Transcription — Voice (STT) + Video (Video-to-Text) provider
       * selection, grouped under one heading so they read as one
       * coherent "how this agent transcribes audio/video" feature with
       * two applicable contexts, rather than two unrelated pickers that
       * happen to be adjacent. Same sub-heading treatment as the
       * "Recent failures" / "On-device (edge) details" sub-groups in
       * the Reliability section below. Only shown in creation form for
       * now; editing is available via separate buttons for existing
       * agents (see voice/video-model editors below). Selectors
       * themselves are unchanged - grouping/heading only. */}
      {isDraft && (
        <div className="mb-3 space-y-3">
          <span className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            <Volume2 size={11} />
            Transcription
          </span>
          <div>
            <div className="mb-1 text-xs text-muted-foreground">For voice calls</div>
            <VoiceProviderSelector
              value={voiceModelProvider}
              onChange={(provider, model) => {
                setVoiceModelProvider(provider);
                setVoiceModel(model);
              }}
              isFreePlan={isFreePlan}
              disabled={busy}
              showLabel={true}
              showDescription={true}
            />
          </div>
          <div>
            <div className="mb-1 text-xs text-muted-foreground">For video calls</div>
            <VideoProviderSelector
              value={videoModelProvider}
              onChange={(provider, model) => {
                setVideoModelProvider(provider);
                setVideoModel(model);
              }}
              isFreePlan={isFreePlan}
              disabled={busy}
              showLabel={true}
              showDescription={true}
            />
          </div>
          <div>
            <div className="mb-1 text-xs text-muted-foreground">For voice replies</div>
            <TextToSpeechProviderSelector
              value={ttsProvider}
              onChange={(provider, model) => {
                setTtsProvider(provider);
                setTtsModel(model);
              }}
              isFreePlan={isFreePlan}
              disabled={busy}
              showLabel={true}
              showDescription={true}
            />
          </div>
        </div>
      )}
    </>
  );
}
