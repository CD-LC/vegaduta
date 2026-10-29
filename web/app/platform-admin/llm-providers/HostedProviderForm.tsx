"use client";

// Add / edit form for one platform-hosted LLM provider row (split out of
// page.tsx). Draft, save and discovery live in useHostedPool.

import { Plus } from "lucide-react";
import Select from "../components/Select";
import { SwitchToggle } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Textarea } from "../components/ui/textarea";
import { LocalModelControls } from "./hostedRowParts";
import {
  CREDENTIAL_TYPE_OPTIONS,
  HOSTED_POOL_GENERIC_PROVIDERS,
  HOSTED_PROVIDER_PRESETS,
  HOSTED_PROVIDER_PRIORITY_OPTIONS,
  HOSTED_PROVIDER_REGION_OPTIONS,
  withCurrentValue,
} from "./constants";
import type { HostedPool } from "./useHostedPool";
import { useTranslations } from "../../lib/i18n";

export function HostedProviderForm({ pool }: { pool: HostedPool }) {
    const tHC = useTranslations("hcPlatformadminLlmprovidersHostedProviderForm");
  const {
    hostedProviderDraft,
    setHostedProviderDraft,
    editingHostedProviderId,
    savingHostedProvider,
    handleSaveHostedProvider,
    cancelHostedProviderEdit,
    genericProviderModels,
    loadingGenericProviderModels,
    discoveredHostedModels,
    discoveringHostedModels,
    handleDiscoverHostedModels,
  } = pool;
  return (
    <form onSubmit={handleSaveHostedProvider} className="rounded-lg border border-border p-4">
      <div className="mb-3 text-sm font-semibold text-foreground">
        {editingHostedProviderId === null ? (
          <span className="inline-flex items-center gap-1.5">
            <Plus size={13} /> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s36")}
          </span>
        ) : (
          `Editing "${hostedProviderDraft.label}"`
        )}
      </div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground">{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s35")}</span>
        {CREDENTIAL_TYPE_OPTIONS.map((opt) => (
          <Button
            key={opt.value}
            type="button"
            variant={(hostedProviderDraft.authType || "API_KEY") === opt.value ? "default" : "outline"}
            size="sm"
            aria-pressed={(hostedProviderDraft.authType || "API_KEY") === opt.value}
            onClick={() =>
              setHostedProviderDraft((d) => ({
                ...d,
                authType: opt.value,
                // Clear whatever the API key field held for the previous
                // mode (plain key vs pasted service-account JSON) rather
                // than silently carrying it across - and ADC never sends
                // one at all.
                apiKey: "",
              }))
            }
          >
            {opt.label}
          </Button>
        ))}
      </div>
      {hostedProviderDraft.authType === "API_KEY" && (
        <div className="mb-3 flex items-center gap-2">
          <Button
            type="button"
            variant={hostedProviderDraft.mode === "custom" ? "default" : "outline"}
            size="sm"
            aria-pressed={hostedProviderDraft.mode === "custom"}
            onClick={() => setHostedProviderDraft((d) => ({ ...d, mode: "custom" }))}
          >
            {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s17")}
          </Button>
          <Button
            type="button"
            variant={hostedProviderDraft.mode === "provider" ? "default" : "outline"}
            size="sm"
            aria-pressed={hostedProviderDraft.mode === "provider"}
            onClick={() =>
              setHostedProviderDraft((d) => ({
                ...d,
                mode: "provider",
                baseUrl: "",
                completionsPath: "",
                model: "",
              }))
            }
          >
            {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s19")}
          </Button>
          <span className="text-xs text-muted-foreground">
            {hostedProviderDraft.mode === "provider"
              ? "Pick a provider, paste a key, leave model blank to auto-select the best one - like tenant BYOK."
              : "Any OpenAI-compatible host (Groq, Together AI, ...) - type or discover a model."}
          </span>
        </div>
      )}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-56 space-y-1.5">
          <Label htmlFor="hosted-label" className="text-xs text-muted-foreground">
            Label
          </Label>
          <Input
            id="hosted-label"
            value={hostedProviderDraft.label}
            onChange={(e) => setHostedProviderDraft((d) => ({ ...d, label: e.target.value }))}
            placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s34")}
          />
        </div>
        {hostedProviderDraft.authType !== "API_KEY" ? (
          <>
            <div className="w-56 space-y-1.5">
              <Label htmlFor="hosted-vertex-project" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s33")}
              </Label>
              <Input
                id="hosted-vertex-project"
                value={hostedProviderDraft.vertexProjectId}
                onChange={(e) => setHostedProviderDraft((d) => ({ ...d, vertexProjectId: e.target.value }))}
                placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s32")}
              />
            </div>
            <div className="w-48 space-y-1.5">
              <Label htmlFor="hosted-vertex-location" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s31")}
              </Label>
              <Input
                id="hosted-vertex-location"
                value={hostedProviderDraft.vertexLocation}
                onChange={(e) => setHostedProviderDraft((d) => ({ ...d, vertexLocation: e.target.value }))}
                placeholder="us-central1"
              />
            </div>
          </>
        ) : hostedProviderDraft.mode === "provider" ? (
          <div className="w-56 max-w-full space-y-1.5">
            <Label htmlFor="hosted-provider" className="text-xs text-muted-foreground">
              Provider
            </Label>
            <Select
              id="hosted-provider"
              value={hostedProviderDraft.providerName}
              onChange={(e) =>
                setHostedProviderDraft((d) => ({ ...d, providerName: e.target.value, model: "" }))
              }
              options={HOSTED_POOL_GENERIC_PROVIDERS}
              placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s30")}
            />
          </div>
        ) : (
          <>
            <div className="w-64 max-w-full space-y-1.5">
              <Label htmlFor="hosted-base-url" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s29")}
              </Label>
              <Select
                id="hosted-base-url"
                value={hostedProviderDraft.baseUrl}
                onChange={(e) =>
                  setHostedProviderDraft((d) => ({
                    ...d,
                    baseUrl: e.target.value,
                    model: "",
                    // Auto-fill the completions path from the chosen preset
                    // (e.g. Gemini's /v1beta/openai/...); clear it for the
                    // conventional providers so it falls back to the default.
                    completionsPath:
                      HOSTED_PROVIDER_PRESETS.find((p) => p.value === e.target.value)?.completionsPath || "",
                  }))
                }
                options={withCurrentValue(
                  HOSTED_PROVIDER_PRESETS.map((p) => ({ value: p.value, label: `${p.label} — ${p.value}` })),
                  hostedProviderDraft.baseUrl
                )}
                placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s28")}
                allowCustom
                searchable
              />
            </div>
            <div className="w-64 max-w-full space-y-1.5">
              <Label htmlFor="hosted-completions-path" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s27")}
              </Label>
              <Input
                id="hosted-completions-path"
                value={hostedProviderDraft.completionsPath}
                onChange={(e) => setHostedProviderDraft((d) => ({ ...d, completionsPath: e.target.value }))}
                placeholder="/v1/chat/completions (default)"
              />
            </div>
          </>
        )}
        <div className="w-56 max-w-full space-y-1.5">
          <Label className="text-xs text-muted-foreground">
            Model{" "}
            {hostedProviderDraft.authType === "API_KEY" && hostedProviderDraft.mode === "provider"
              ? "(optional - blank = auto-select best)"
              : ""}
          </Label>
          {hostedProviderDraft.authType !== "API_KEY" ? (
            <Input
              value={hostedProviderDraft.model}
              onChange={(e) => setHostedProviderDraft((d) => ({ ...d, model: e.target.value }))}
              placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s26")}
            />
          ) : hostedProviderDraft.mode === "provider" ? (
            <Select
              value={hostedProviderDraft.model}
              onChange={(e) => setHostedProviderDraft((d) => ({ ...d, model: e.target.value }))}
              options={withCurrentValue(
                genericProviderModels.map((m) => ({ value: m.modelId, label: m.displayName || m.modelId })),
                hostedProviderDraft.model
              )}
              placeholder={loadingGenericProviderModels ? "Loading models…" : "Auto-select the best model"}
              allowCustom
              searchable
            />
          ) : (
            <div className="flex items-center gap-1.5">
              <Select
                value={hostedProviderDraft.model}
                onChange={(e) => setHostedProviderDraft((d) => ({ ...d, model: e.target.value }))}
                options={withCurrentValue(
                  [
                    ...(HOSTED_PROVIDER_PRESETS.find(
                      (p) => p.value === hostedProviderDraft.baseUrl
                    )?.models.map((m) => ({ value: m, label: m })) || []),
                    ...discoveredHostedModels.map((m) => ({ value: m, label: m })),
                  ],
                  hostedProviderDraft.model
                )}
                placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s25")}
                allowCustom
                searchable
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleDiscoverHostedModels}
                disabled={discoveringHostedModels}
                title={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s24")}
              >
                {discoveringHostedModels ? "…" : "Discover"}
              </Button>
            </div>
          )}
        </div>
        {hostedProviderDraft.authType === "VERTEX_SERVICE_ACCOUNT" && (
          <div className="w-full space-y-1.5 sm:w-80">
            <Label htmlFor="hosted-sa-json" className="text-xs text-muted-foreground">
              {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s23")}
            </Label>
            <Textarea
              id="hosted-sa-json"
              rows={4}
              value={hostedProviderDraft.apiKey}
              onChange={(e) => setHostedProviderDraft((d) => ({ ...d, apiKey: e.target.value }))}
              placeholder={
                editingHostedProviderId === null
                  ? 'Paste the full service-account JSON key, e.g. {"type": "service_account", ...}'
                  : "Leave blank to keep the current key"
              }
              className="font-mono text-xs"
            />
          </div>
        )}
        {hostedProviderDraft.authType === "API_KEY" && (
          <div className="w-56 space-y-1.5">
            <Label htmlFor="hosted-api-key" className="text-xs text-muted-foreground">
              API key
            </Label>
            <Input
              id="hosted-api-key"
              type="password"
              value={hostedProviderDraft.apiKey}
              onChange={(e) => setHostedProviderDraft((d) => ({ ...d, apiKey: e.target.value }))}
              placeholder={
                editingHostedProviderId === null ? "Required" : "Leave blank to keep the current key"
              }
              autoComplete="new-password"
            />
          </div>
        )}
        <div className="w-40 space-y-1.5">
          <Label htmlFor="hosted-priority" className="text-xs text-muted-foreground">
            Priority
          </Label>
          <Select
            id="hosted-priority"
            value={String(hostedProviderDraft.priority)}
            onChange={(e) => setHostedProviderDraft((d) => ({ ...d, priority: e.target.value }))}
            options={withCurrentValue(HOSTED_PROVIDER_PRIORITY_OPTIONS, hostedProviderDraft.priority)}
            placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s22")}
            allowCustom
          />
        </div>
        <div className="w-40 space-y-1.5">
          <Label htmlFor="hosted-region" className="text-xs text-muted-foreground">
            {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s21")}
          </Label>
          <Select
            id="hosted-region"
            value={hostedProviderDraft.region}
            onChange={(e) => setHostedProviderDraft((d) => ({ ...d, region: e.target.value }))}
            options={withCurrentValue(HOSTED_PROVIDER_REGION_OPTIONS, hostedProviderDraft.region)}
            placeholder={tHC("hcPlatformadminLlmprovidersHostedProviderForm.s20")}
            allowCustom
          />
        </div>
        <LocalModelControls draft={hostedProviderDraft} setDraft={setHostedProviderDraft} />
        <div className="flex items-center gap-2">
          <SwitchToggle
            on={hostedProviderDraft.enabled}
            label="Enabled"
            onClick={() => setHostedProviderDraft((d) => ({ ...d, enabled: !d.enabled }))}
          />
          <span className="text-xs text-muted-foreground">Enabled</span>
        </div>
      </div>
      <div className="mt-3 flex items-center gap-2">
        <Button type="submit" size="sm" disabled={savingHostedProvider}>
          {savingHostedProvider
            ? "Saving…"
            : editingHostedProviderId === null
              ? "Add provider"
              : "Save changes"}
        </Button>
        {editingHostedProviderId !== null && (
          <Button type="button" variant="outline" size="sm" onClick={cancelHostedProviderEdit}>
            Cancel
          </Button>
        )}
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s19")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s18")}{" "}
        <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s17")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s16")}{" "}
        <strong>Discover</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s15")} <code>/v1</code> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s14")}{" "}
        <code>/v1/chat/completions</code> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s13")} <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s12")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s11")} <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s10")}</strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s9")}{" "}
        <code>https://generativelanguage.googleapis.com</code> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s8")}{" "}
        <code>/v1beta/openai/chat/completions</code> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s7")}{" "}
        <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s6")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s5")} <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s4")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s3")}{" "}
        <strong>{tHC("hcPlatformadminLlmprovidersHostedProviderForm.s2")}</strong> {tHC("hcPlatformadminLlmprovidersHostedProviderForm.s1")}
      </p>
    </form>
  );
}
