"use client";

import { Speaker } from "lucide-react";
import TranscriptionProviderOptions from "./TranscriptionProviderOptions";
import { useModalityCatalog } from "./useModalityCatalog";

/**
 * TTS provider selector - output-side twin of VoiceProviderSelector (which
 * selects the input-side, speech-to-text provider). Like it, this is now a
 * provider-agnostic wrapper over GET /api/providers/modalities'
 * textToSpeech list: the hardcoded TTS_PROVIDERS array and the
 * caller-computed providerStatus prop are gone - labels, models and
 * per-tenant availability all come from the server catalog.
 */
interface TextToSpeechProviderSelectorProps {
  // Current selection: null (platform default) | a catalog option's `provider` value
  value: string | null | undefined;
  onChange: (provider: string | null, model: string | null) => void;
  isFreePlan: boolean;
  disabled?: boolean;
  onBusy?: (busy: boolean) => void;
  className?: string;
  showLabel?: boolean;
  showDescription?: boolean;
  editorMode?: boolean;
  onEdit?: () => void;
  isBusy?: boolean;
}

export default function TextToSpeechProviderSelector({
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
}: TextToSpeechProviderSelectorProps) {
  const { providers, providerStatus } = useModalityCatalog("textToSpeech", {
    name: "Platform Default",
    description: "Intelligent fallback across the deployment's TTS providers",
  });

  return (
    <TranscriptionProviderOptions
      providers={providers}
      icon={Speaker}
      label="Voice Provider (Text-to-Speech)"
      tooltipContent={
        <>
          Pick which text-to-speech backend synthesizes this agent&apos;s voice replies, or leave
          the platform default fallback. Options marked &quot;Needs setup&quot; need a credential on
          the Providers page first.
        </>
      }
      freePlanWarningText={
        <>
          TTS provider selection is a <strong>Premium feature</strong>. Your agents use the
          platform default fallback.
        </>
      }
      editorLockedTitle="TTS provider selection is a Premium feature"
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
