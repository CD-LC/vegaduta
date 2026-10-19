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

export function HostedProviderForm({ pool }: { pool: HostedPool }) {
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
            <Plus size={13} /> Add a hosted provider
          </span>
        ) : (
          `Editing "${hostedProviderDraft.label}"`
        )}
      </div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground">Credential type:</span>
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
            Custom base URL
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
            Provider + key (auto)
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
            placeholder="e.g. Groq - Llama 3.3 70B"
          />
        </div>
        {hostedProviderDraft.authType !== "API_KEY" ? (
          <>
            <div className="w-56 space-y-1.5">
              <Label htmlFor="hosted-vertex-project" className="text-xs text-muted-foreground">
                Vertex AI project ID
              </Label>
              <Input
                id="hosted-vertex-project"
                value={hostedProviderDraft.vertexProjectId}
                onChange={(e) => setHostedProviderDraft((d) => ({ ...d, vertexProjectId: e.target.value }))}
                placeholder="e.g. my-gcp-project"
              />
            </div>
            <div className="w-48 space-y-1.5">
              <Label htmlFor="hosted-vertex-location" className="text-xs text-muted-foreground">
                Vertex AI location
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
              placeholder="Choose a provider…"
            />
          </div>
        ) : (
          <>
            <div className="w-64 max-w-full space-y-1.5">
              <Label htmlFor="hosted-base-url" className="text-xs text-muted-foreground">
                Base URL
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
                placeholder="Choose a hosted provider…"
                allowCustom
                searchable
              />
            </div>
            <div className="w-64 max-w-full space-y-1.5">
              <Label htmlFor="hosted-completions-path" className="text-xs text-muted-foreground">
                Completions path (optional)
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
              placeholder="e.g. gemini-2.5-flash"
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
                placeholder="Choose a model…"
                allowCustom
                searchable
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleDiscoverHostedModels}
                disabled={discoveringHostedModels}
                title="Live-probe this base URL/key's real GET /v1/models endpoint"
              >
                {discoveringHostedModels ? "…" : "Discover"}
              </Button>
            </div>
          )}
        </div>
        {hostedProviderDraft.authType === "VERTEX_SERVICE_ACCOUNT" && (
          <div className="w-full space-y-1.5 sm:w-80">
            <Label htmlFor="hosted-sa-json" className="text-xs text-muted-foreground">
              Service account JSON key
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
            placeholder="Choose a priority…"
            allowCustom
          />
        </div>
        <div className="w-40 space-y-1.5">
          <Label htmlFor="hosted-region" className="text-xs text-muted-foreground">
            Region (optional)
          </Label>
          <Select
            id="hosted-region"
            value={hostedProviderDraft.region}
            onChange={(e) => setHostedProviderDraft((d) => ({ ...d, region: e.target.value }))}
            options={withCurrentValue(HOSTED_PROVIDER_REGION_OPTIONS, hostedProviderDraft.region)}
            placeholder="No region label"
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
        <strong>Provider + key (auto)</strong> mode is the generic flow, mirroring tenant BYOK: pick
        OpenAI/Grok/OpenRouter/Gemini/Meta (Muse Spark), paste a key, and leave Model blank to auto-select the
        best currently-available model from that provider&apos;s catalog - the fix for a hand-typed model id
        (e.g. a retired Gemini model) breaking every FREE-tier chat call with no warning.{" "}
        <strong>Custom base URL</strong> mode is unchanged from before: any OpenAI-compatible host (Groq,
        Together AI, ...) as a dropdown (search or pick &quot;Use...&quot; to type a different one), with a{" "}
        <strong>Discover</strong> button to live-probe that host/key&apos;s real model list instead of typing
        blind. Base URL must NOT include a trailing <code>/v1</code> - by default{" "}
        <code>/v1/chat/completions</code> is appended automatically (same composition Spring AI&apos;s OpenAiApi
        builder does for every OpenAI-compatible provider). Leave <strong>Completions path</strong> blank for
        the conventional providers; set it only when a provider&apos;s OpenAI-compatible endpoint lives
        elsewhere - e.g. <strong>Google Gemini</strong>, base URL{" "}
        <code>https://generativelanguage.googleapis.com</code> + completions path{" "}
        <code>/v1beta/openai/chat/completions</code> (auto-filled in either mode). Region is an optional label
        for your own organization (e.g. which datacenter/geo a provider serves) - selection among enabled rows
        is still purely priority-order, not routed by region or by tenant location. Label stays free text -
        it&apos;s your own name for this row, not a value to pick from a list. The{" "}
        <strong>Credential type</strong> selector above is a separate axis from Custom/Provider mode: switching
        it to <strong>Vertex AI — Application Default Credentials</strong> authenticates via this
        platform&apos;s own GCP service identity (no key to paste - just a project ID and location), while{" "}
        <strong>Vertex AI — Service Account JSON</strong> uses a pasted service-account key (the same API-key
        field, just a multi-line JSON paste instead of a short string) - both replace the Base URL/Provider/API
        key fields with a project ID + location, and neither is echoed back once saved.
      </p>
    </form>
  );
}
