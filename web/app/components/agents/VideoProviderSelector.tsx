"use client";

import { Video } from "lucide-react";
import TranscriptionProviderOptions from "./TranscriptionProviderOptions";
import { useModalityCatalog } from "./useModalityCatalog";

/**
 * Video-to-text provider selector - a thin, provider-agnostic wrapper over
 * GET /api/providers/modalities' videoToText list. The hardcoded
 * VIDEO_PROVIDERS array (including its hand-maintained "SARVAM_VIDEO =
 * disabled/Coming soon" entry) and the caller-computed providerStatus prop
 * are gone: catalog rows whose provider bean isn't registered on this
 * deployment arrive as `implemented: false` and render as the same greyed-out
 * "Coming soon" rows generically.
 */
interface VideoProviderSelectorProps {
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

export default function VideoProviderSelector({
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
}: VideoProviderSelectorProps) {
  const { providers, providerStatus } = useModalityCatalog("videoToText", {
    name: "Platform Default",
    description: "Intelligent fallback across the deployment's video providers",
  });

  return (
    <TranscriptionProviderOptions
      providers={providers}
      icon={Video}
      label="Video Provider (Video-to-Text)"
      tooltipContent={
        <>
          Pick which backend transcribes video input for this agent, or leave the platform default
          fallback. Options marked &quot;Needs setup&quot; need a credential on the Providers page
          first.
        </>
      }
      freePlanWarningText={
        <>
          Video provider selection is a <strong>Premium feature</strong>. Your agents use the
          platform default fallback chain.
        </>
      }
      editorLockedTitle="Video provider selection is a Premium feature"
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
