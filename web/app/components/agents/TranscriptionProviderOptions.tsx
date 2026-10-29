"use client";

import type { ComponentType } from "react";
import type { ReactNode } from "react";
import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react";
import { Button } from "../ui/button";
import Select from "../Select";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";

/**
 * Shared shape for transcription-style provider options (voice STT, video-to-text, ...).
 * Individual features export their own typed/branded PROVIDERS array using this shape.
 */
export interface TranscriptionProviderOption {
  id: string | null;
  name: string;
  description: string;
  model: string | null;
  isPremium: boolean;
  requiresConfig: boolean;
  // Set true for an option that has no working backend on this deployment yet
  // (e.g. SARVAM_VIDEO - see VideoModelProvider.java javadoc: no
  // SarvamVideoProvider implementation is registered, so selecting+saving it
  // always throws "not available" server-side). Such options are pulled out
  // of the selectable dropdown entirely and shown as a greyed-out,
  // non-interactive row with `disabledReason` instead, so a tenant never
  // picks something that is guaranteed to fail on save.
  disabled?: boolean;
  disabledReason?: string;
}

export interface TranscriptionProviderOptionsProps {
  providers: TranscriptionProviderOption[];
  icon: ComponentType<{ size?: number | string; className?: string }>;
  label: string;
  tooltipContent: ReactNode;
  freePlanWarningText: ReactNode;
  editorLockedTitle: string;
  // Current selection
  value: string | null | undefined;
  onChange: (provider: string | null, model: string | null) => void;
  isFreePlan: boolean;
  disabled?: boolean;
  onBusy?: (busy: boolean) => void;
  // null = not checked, "available" = yes, "unavailable" = no, "checking" = in progress
  providerStatus?: Record<string, "available" | "unavailable" | "checking">;
  className?: string;
  showLabel?: boolean;
  showDescription?: boolean;
  // If true, shows an editor button instead of inline select (for existing agents)
  editorMode?: boolean;
  onEdit?: () => void;
  isBusy?: boolean;
}

/**
 * Shared rendering for provider-selector components that offer a dropdown of
 * transcription-style providers with an editor-mode "current value + Change
 * button" view, a status badge per provider, and a free-plan premium-lock
 * warning box. Feature-specific wrappers (VoiceProviderSelector,
 * VideoProviderSelector) supply their own options array, icon, and copy.
 */
export default function TranscriptionProviderOptions({
  providers,
  icon: Icon,
  label,
  tooltipContent,
  freePlanWarningText,
  editorLockedTitle,
  value,
  onChange,
  isFreePlan,
  disabled = false,
  providerStatus = {},
  className,
  showLabel = true,
  showDescription = true,
  editorMode = false,
  onEdit,
  isBusy = false,
}: TranscriptionProviderOptionsProps) {
    const tHC = useTranslations("hcComponentsAgentsTranscriptionProviderOptions");
  // Current selected provider details
  const currentProvider = providers.find((p) => p.id === value);

  // Available (selectable) options: hide premium providers from FREE users,
  // and pull out anything with no working backend (disabled: true) entirely -
  // it never belongs in the dropdown as a normal, clickable choice.
  const availableProviders = providers.filter((p) => {
    if (isFreePlan && p.isPremium) return false;
    if (p.disabled) return false;
    return true;
  });

  // Providers with no working backend on this deployment - rendered as a
  // separate, non-interactive "Coming soon" row below the dropdown instead of
  // a selectable option (see TranscriptionProviderOption.disabled doc above).
  const disabledProviders = providers.filter((p) => {
    if (isFreePlan && p.isPremium) return false;
    return !!p.disabled;
  });

  const handleChange = (newValue: string | null) => {
    const selected = providers.find((p) => p.id === newValue);
    if (selected) {
      onChange(selected.id, selected.model);
    }
  };

  // Provider status badge
  const getStatusBadge = (providerId: string | null) => {
    if (providerId === null) return null; // Platform default has no status

    const status = providerStatus[providerId];
    if (!status || status === "available")
      return <CheckCircle2 size={14} className="text-[color:var(--state-success)]" aria-hidden="true" />;
    if (status === "checking")
      return <Loader2 size={14} className="animate-spin text-[color:var(--state-warning)]" aria-hidden="true" />;
    return <AlertTriangle size={14} className="text-destructive" aria-hidden="true" />;
  };

  // "Needs setup" affordance for requiresConfig:true providers - shown only
  // while the live credential check (providerStatus, populated by the
  // caller from api.get*Credential()) hasn't confirmed one is active. Used
  // to be gated on provider.requiresConfig alone, which is a static "this
  // kind of provider needs a credential at all" flag - it never turned off
  // once a tenant actually added the credential, so ElevenLabs/Sarvam kept
  // showing "Needs setup" right next to getStatusBadge's own green
  // checkmark for the same, already-active credential. Mirror
  // getStatusBadge's optimistic default (undefined/"available" both read as
  // configured) so the two badges never contradict each other again.
  const getNeedsSetupBadge = (provider: TranscriptionProviderOption) => {
    if (!provider.requiresConfig) return null;
    if (provider.id === null) return null;
    if (providerStatus[provider.id] !== "unavailable") return null;
    return (
      <span
        className="state-pill"
        style={{ ["--state" as any]: "var(--state-warning)" } as any}
        title={tHC("hcComponentsAgentsTranscriptionProviderOptions.s2")}
      >
        {tHC("hcComponentsAgentsTranscriptionProviderOptions.s1")}
      </span>
    );
  };

  // Editor mode: show current value with edit button
  if (editorMode) {
    return (
      <div className={cn("flex items-center gap-2", className)}>
        <div className="flex items-center gap-2 px-3 py-2 rounded-md bg-secondary/50 flex-1">
          <Icon size={14} aria-hidden="true" />
          <div className="text-sm">
            <div className="font-medium">{currentProvider?.name || "Platform Default"}</div>
            {showDescription && <div className="text-xs text-muted-foreground">{currentProvider?.description}</div>}
          </div>
          {getStatusBadge(currentProvider?.id || null)}
          {currentProvider && getNeedsSetupBadge(currentProvider)}
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={onEdit}
          disabled={disabled || isBusy || isFreePlan}
          title={isFreePlan ? editorLockedTitle : undefined}
        >
          {isBusy ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : "Change"}
        </Button>
      </div>
    );
  }

  // Inline mode: show dropdown
  const selectOptions = availableProviders.map((p) => ({
    value: p.id === null ? "__platform_default__" : p.id,
    label: (
      <div className="flex items-center gap-2">
        <Icon size={14} aria-hidden="true" />
        <div>
          <div className="font-medium">{p.name}</div>
          {showDescription && <div className="text-xs text-muted-foreground">{p.description}</div>}
        </div>
        {getStatusBadge(p.id)}
        {getNeedsSetupBadge(p)}
        {isFreePlan && p.isPremium && (
          <span className="state-pill ms-auto" style={{ ["--state" as any]: "var(--state-warning)" } as any}>
            Premium
          </span>
        )}
      </div>
    ),
  }));

  return (
    <div className={className}>
      {showLabel && (
        <div className="flex items-center gap-1 mb-2">
          <label className="text-sm font-medium">{label}</label>
          <Tooltip>
            <TooltipTrigger asChild>
              <div className="w-4 h-4 text-xs text-muted-foreground cursor-help">ℹ️</div>
            </TooltipTrigger>
            <TooltipContent side="right" className="max-w-xs">
              {tooltipContent}
            </TooltipContent>
          </Tooltip>
        </div>
      )}

      {isFreePlan && (
        <div
          className="mb-3 flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
          style={
            {
              ["--state" as any]: "var(--state-warning)",
              color: "var(--state)",
              background: "color-mix(in srgb, var(--state) 11%, transparent)",
              borderColor: "color-mix(in srgb, var(--state) 34%, transparent)",
            } as any
          }
        >
          <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
          <div>{freePlanWarningText}</div>
        </div>
      )}

      <Select
        options={selectOptions}
        value={
          currentProvider
            ? currentProvider.id === null
              ? "__platform_default__"
              : currentProvider.id
            : "__platform_default__"
        }
        onChange={(e) => handleChange(e.target.value === "__platform_default__" ? null : e.target.value)}
        disabled={disabled || isFreePlan}
        searchable={true}
      />

      {/* Not-yet-implemented providers (e.g. SARVAM_VIDEO - no backend
          registered, see VideoProviderFactory/VideoProviderValidator): shown
          as a greyed-out, non-interactive row rather than a Select option, so
          they're visible (a tenant can see it's coming) without being
          selectable (a tenant can never pick something guaranteed to fail on
          save). */}
      {disabledProviders.map((p) => (
        <div
          key={p.id ?? "disabled-default"}
          className="mt-2 flex items-center gap-2 rounded-md border border-dashed px-3 py-2 opacity-60"
          style={{ cursor: "not-allowed" }}
          aria-disabled="true"
          title={p.disabledReason || "Not available yet"}
        >
          <Icon size={14} aria-hidden="true" />
          <div className="text-sm flex-1">
            <div className="font-medium">{p.name}</div>
            {showDescription && <div className="text-xs text-muted-foreground">{p.description}</div>}
          </div>
          <span className="state-pill" style={{ ["--state" as any]: "var(--text-muted)" } as any}>
            {p.disabledReason || "Coming soon"}
          </span>
        </div>
      ))}
    </div>
  );
}
