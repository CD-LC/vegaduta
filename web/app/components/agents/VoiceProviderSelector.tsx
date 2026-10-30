"use client";

import { Volume2 } from "lucide-react";
import TranscriptionProviderOptions from "./TranscriptionProviderOptions";
import { useModalityCatalog } from "./useModalityCatalog";
import { useTranslations } from "../../lib/i18n";

/**
 * Voice STT provider selector - a thin, provider-agnostic wrapper over
 * GET /api/providers/modalities' speechToText list. The hardcoded
 * VOICE_PROVIDERS array and the caller-supplied providerStatus prop were
 * retired: each option's label/description/model/premium and live
 * availability (credential active, sidecar healthy) all arrive from the
 * server catalog, so a new STT provider appears here with no frontend
 * change.
 */
interface VoiceProviderSelectorProps {
  // Current selection: null (platform default) | a catalog option's `provider` value
  value: string | null | undefined;
  onChange: (provider: string | null, model: string | null) => void;
  isFreePlan: boolean;
  disabled?: boolean;
  onBusy?: (busy: boolean) => void;
  className?: string;
  showLabel?: boolean;
  showDescription?: boolean;
  // If true, shows an editor button instead of inline select (for existing agents)
  editorMode?: boolean;
  onEdit?: () => void;
  isBusy?: boolean;
}

export default function VoiceProviderSelector({
  value,
  onChange,
  isFreePlan,
  disabled = false,
  onBusy,
  className,
  showLabel = true,
  showDescription = true,
  editorMode = false,
  onEdit,
  isBusy = false,
}: VoiceProviderSelectorProps) {
  const tHC = useTranslations("hcComponentsAgentsVoiceProviderSelector");
  const { providers, providerStatus } = useModalityCatalog("speechToText", {
    name: "Platform Default",
    description: "Intelligent fallback across the deployment's STT providers",
  });

  return (
    <TranscriptionProviderOptions
      providers={providers}
      icon={Volume2}
      label={tHC("s5")}
      tooltipContent={<>{tHC("s4")}</>}
      freePlanWarningText={
        <>
          {tHC("s3")} <strong>{tHC("s2")}</strong>
          {tHC("s1")}
        </>
      }
      editorLockedTitle="Voice provider selection is a Premium feature"
      value={value}
      onChange={onChange}
      isFreePlan={isFreePlan}
      disabled={disabled}
      onBusy={onBusy}
      providerStatus={providerStatus}
      className={className}
      showLabel={showLabel}
      showDescription={showDescription}
      editorMode={editorMode}
      onEdit={onEdit}
      isBusy={isBusy}
    />
  );
}
