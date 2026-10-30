"use client";

import { Video } from "lucide-react";
import TranscriptionProviderOptions from "./TranscriptionProviderOptions";
import { useModalityCatalog } from "./useModalityCatalog";
import { useTranslations } from "../../lib/i18n";

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
    const tHC = useTranslations("hcComponentsAgentsVideoProviderSelector");
  const { providers, providerStatus } = useModalityCatalog("videoToText", {
    name: "Platform Default",
    description: "Intelligent fallback across the deployment's video providers",
  });

  return (
    <TranscriptionProviderOptions
      providers={providers}
      icon={Video}
      label={tHC("s5")}
      tooltipContent={
        <>
          {tHC("s4")}
        </>
      }
      freePlanWarningText={
        <>
          {tHC("s3")} <strong>{tHC("s2")}</strong>{tHC("s1")}
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
