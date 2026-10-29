"use client";

import { Speaker, Video, Volume2 } from "lucide-react";
import { SetupHint } from "../../setup/SetupLinks";
import { Button } from "../../ui/button";
import TextToSpeechProviderSelector from "../TextToSpeechProviderSelector";
import VideoProviderSelector from "../VideoProviderSelector";
import VoiceProviderSelector from "../VoiceProviderSelector";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";
import { useTranslations } from "../../../lib/i18n";

export default function TranscriptionSection() {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchTranscriptionSection");
  const {
    isDraft,
    transcriptionSummary,
    voiceModelEditing,
    t,
    selectedAgent,
    locked,
    voiceModelBusy,
    isFreePlan,
    openVoiceModelEditor,
    videoModelEditing,
    videoModelBusy,
    openVideoModelEditor,
    ttsModelEditing,
    ttsModelBusy,
    openTtsModelEditor,
    ttsModelDraft,
    setTtsModelDraft,
    handleUpdateTtsModel,
    setTtsModelEditing,
    voiceModelDraft,
    setVoiceModelDraft,
    handleUpdateVoiceModel,
    setVoiceModelEditing,
    videoModelDraft,
    setVideoModelDraft,
    handleUpdateVideoModel,
    setVideoModelEditing,
  } = useWorkbench();
  return (
    <>
      {/* Transcription — Voice (STT) + Video (Video-to-Text) + TTS provider
       * selection for an existing agent, now its own collapsed section
       * (QA-05). Each card/editor's own gating, data and handlers are
       * unchanged - only the wrapper moved. */}
      {isDraft ? (
        <WorkbenchSection.SavedOnly
          icon={<Volume2 size={13} />}
          label="Transcription"
          cat="var(--cat-creative)"
          what="voice, video and text-to-speech providers attach to a saved agent."
        />
      ) : (
        <WorkbenchSection
          icon={<Volume2 size={13} />}
          label="Transcription"
          cat="var(--cat-creative)"
          actions={<WorkbenchSection.Summary>{transcriptionSummary}</WorkbenchSection.Summary>}
        >
          <div className="space-y-3">
            <SetupHint
              targets={["providers", "secrets"]}
              lead="Voice, video and text-to-speech models need a provider key:"
            />
            {/* Voice STT provider selection - Premium feature, available for all
             * agents (not just MANUAL tuning mode like fallback/cost-routing).
             * Shows current selection + "Change" button for existing agents.
             */}
            {!voiceModelEditing && (
              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
                <div className="flex min-w-0 items-center gap-2.5">
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <Volume2 size={15} />
                  </span>
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-foreground">{t("voiceProviderLabel")}</div>
                    <div className="truncate text-xs text-muted-foreground">
                      {selectedAgent.voiceModelProvider
                        ? `${selectedAgent.voiceModelProvider} · ${selectedAgent.voiceModel}`
                        : t("voiceProviderNotSet")}
                    </div>
                  </div>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="shrink-0"
                  disabled={locked || voiceModelBusy || isFreePlan}
                  onClick={openVoiceModelEditor}
                  upgradeLocked={isFreePlan}
                  upgradeReason={t("voiceProviderFreePlanTooltip")}
                  title={isFreePlan ? undefined : locked ? t("editLockedTooltip") : "Change voice provider"}
                >
                  {t("change")}
                </Button>
              </div>
            )}

            {/* Video provider selection (Video-to-Text) - Premium feature, available for all
             * agents (not just MANUAL tuning mode like fallback/cost-routing).
             * Shows current selection + "Change" button for existing agents.
             */}
            {!videoModelEditing && (
              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
                <div className="flex min-w-0 items-center gap-2.5">
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <Video size={15} />
                  </span>
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-foreground">{t("videoProviderLabel")}</div>
                    <div className="truncate text-xs text-muted-foreground">
                      {selectedAgent.videoModelProvider
                        ? `${selectedAgent.videoModelProvider} · ${selectedAgent.videoModel}`
                        : t("videoProviderNotSet")}
                    </div>
                  </div>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="shrink-0"
                  disabled={locked || videoModelBusy || isFreePlan}
                  onClick={openVideoModelEditor}
                  upgradeLocked={isFreePlan}
                  upgradeReason={t("videoProviderFreePlanTooltip")}
                  title={isFreePlan ? undefined : locked ? t("editLockedTooltip") : "Change video provider"}
                >
                  {t("change")}
                </Button>
              </div>
            )}

            {/* TTS provider selection (Stage 2, 2026-08-09) - output-side twin
             * of the voice (STT) block above: which provider synthesizes this
             * agent's voice REPLIES, instead of transcribing voice input.
             * Premium feature, same summary+editor shape as voice/video above.
             */}
            {!ttsModelEditing && (
              <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5">
                <div className="flex min-w-0 items-center gap-2.5">
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <Speaker size={15} />
                  </span>
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-foreground">{t("ttsProviderLabel")}</div>
                    <div className="truncate text-xs text-muted-foreground">
                      {selectedAgent.ttsProvider
                        ? `${selectedAgent.ttsProvider} · ${selectedAgent.ttsModel}`
                        : t("ttsProviderNotSet")}
                    </div>
                  </div>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="shrink-0"
                  disabled={locked || ttsModelBusy || isFreePlan}
                  onClick={openTtsModelEditor}
                  upgradeLocked={isFreePlan}
                  upgradeReason={t("ttsProviderFreePlanTooltip")}
                  title={isFreePlan ? undefined : locked ? t("editLockedTooltip") : "Change TTS provider"}
                >
                  {t("change")}
                </Button>
              </div>
            )}

            {ttsModelEditing && (
              <div className="rounded-lg border border-border bg-card p-3">
                <div className="mb-3">
                  <div className="mb-1 text-xs text-muted-foreground">{tHC("hcComponentsAgentsWorkbenchTranscriptionSection.s3")}</div>
                  <TextToSpeechProviderSelector
                    value={ttsModelDraft.ttsProvider}
                    onChange={(provider, model) => {
                      setTtsModelDraft({ ttsProvider: provider, ttsModel: model });
                    }}
                    isFreePlan={isFreePlan}
                    disabled={ttsModelBusy}
                    showLabel={true}
                    showDescription={true}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="button" size="sm" disabled={ttsModelBusy} onClick={handleUpdateTtsModel}>
                    {ttsModelBusy ? t("saving") : t("save")}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={ttsModelBusy}
                    onClick={() => setTtsModelEditing(false)}
                  >
                    {t("cancel")}
                  </Button>
                </div>
              </div>
            )}

            {voiceModelEditing && (
              <div className="rounded-lg border border-border bg-card p-3">
                <div className="mb-3">
                  <div className="mb-1 text-xs text-muted-foreground">{tHC("hcComponentsAgentsWorkbenchTranscriptionSection.s2")}</div>
                  <VoiceProviderSelector
                    value={voiceModelDraft.voiceModelProvider}
                    onChange={(provider, model) => {
                      setVoiceModelDraft({ voiceModelProvider: provider, voiceModel: model });
                    }}
                    isFreePlan={isFreePlan}
                    disabled={voiceModelBusy}
                    showLabel={true}
                    showDescription={true}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="button" size="sm" disabled={voiceModelBusy} onClick={handleUpdateVoiceModel}>
                    {voiceModelBusy ? t("saving") : t("save")}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={voiceModelBusy}
                    onClick={() => setVoiceModelEditing(false)}
                  >
                    {t("cancel")}
                  </Button>
                </div>
              </div>
            )}

            {videoModelEditing && (
              <div className="rounded-lg border border-border bg-card p-3">
                <div className="mb-3">
                  <div className="mb-1 text-xs text-muted-foreground">{tHC("hcComponentsAgentsWorkbenchTranscriptionSection.s1")}</div>
                  <VideoProviderSelector
                    value={videoModelDraft.videoModelProvider}
                    onChange={(provider, model) => {
                      setVideoModelDraft({ videoModelProvider: provider, videoModel: model });
                    }}
                    isFreePlan={isFreePlan}
                    disabled={videoModelBusy}
                    showLabel={true}
                    showDescription={true}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="button" size="sm" disabled={videoModelBusy} onClick={handleUpdateVideoModel}>
                    {videoModelBusy ? t("saving") : t("save")}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={videoModelBusy}
                    onClick={() => setVideoModelEditing(false)}
                  >
                    {t("cancel")}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </WorkbenchSection>
      )}
    </>
  );
}
