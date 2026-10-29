"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import { Section } from "../components/ax/page-kit";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Badge } from "../components/ui/badge";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Select } from "../components/ui/select";
import { useAuth } from "../components/AuthProvider";
import { errorMessage } from "../lib/errors";
import { poolLabel } from "../lib/personalInstall";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { useTranslations } from "../lib/i18n";

// "AI Model Control" - per-tenant, per-feature preferred provider/model
// overrides. Backed by TenantAiModelPreferenceController.java (core/tenancy) -
// see that class + AiModelFeature.java's javadoc for the full contract this
// page mirrors. No design_handoff_axon mockup exists for this screen (it's a
// brand-new feature, not a rebuild), so this follows the same AXON idioms
// /providers already established (Section/Card/Button/Badge, no bespoke
// chrome) rather than inventing a new visual language.
//
// No i18n namespace - inline English copy, same posture /features documents
// for itself ("to avoid growing the shared messages bundle for a single
// screen").

// Every providerName TenantAiModelPreferenceService#KNOWN_PROVIDER_NAMES
// accepts (core/tenancy) - kept in sync by hand, mirroring how that backend
// set itself is a hand-kept mirror of ProviderName (core/tenancy cannot
// depend on core/providers - see AiModelPreference.java's javadoc).
// PLATFORM_HOSTED is listed first and is the one option that never needs a
// BYOK credential - it targets the shared platform-hosted pool a tenant may
// already be opted into (ProvidersPanel's "usePlatformLlm" toggle).
const KNOWN_PROVIDERS = [
  "PLATFORM_HOSTED",
  "ANTHROPIC",
  "OPENAI",
  "OPENROUTER",
  "GROK",
  "GEMINI",
  "GOOGLE_VERTEX",
  "AZURE_OPENAI",
  "AWS_BEDROCK",
  "OLLAMA",
  "SARVAM",
  "MISTRAL",
  "DEEPSEEK",
  "GROQ",
  "META",
  "LOCAL_OPENAI",
];

const PROVIDER_LABELS: Record<string, string> = {
  PLATFORM_HOSTED: poolLabel("Platform-hosted (shared pool)"),
  ANTHROPIC: "Anthropic",
  OPENAI: "OpenAI",
  OPENROUTER: "OpenRouter",
  GROK: "Grok (xAI)",
  GEMINI: "Gemini",
  GOOGLE_VERTEX: "Google Vertex AI",
  AZURE_OPENAI: "Azure OpenAI",
  AWS_BEDROCK: "AWS Bedrock",
  OLLAMA: "Ollama",
  SARVAM: "Sarvam",
  MISTRAL: "Mistral",
  DEEPSEEK: "DeepSeek",
  GROQ: "Groq",
  META: "Meta (Muse Spark)",
  LOCAL_OPENAI: "Local / OpenAI-compatible server",
};

interface ProviderRow {
  providerName: string;
  status: string;
}

interface FeatureRow {
  featureKey: string;
  featureLabel?: string;
  providerName?: string;
  model?: string;
}

interface ProviderOption {
  name: string;
  connected: boolean;
}

interface EditFormProps {
  row: FeatureRow;
  providerOptions: ProviderOption[];
  busy: boolean;
  onSave: (providerName: string, model: string) => void;
  onCancel: () => void;
}

function EditForm({ row, providerOptions, busy, onSave, onCancel }: EditFormProps) {
    const tHC = useTranslations("hcAimodelsPage");
  const [providerName, setProviderName] = useState(row.providerName || providerOptions[0]?.name || "PLATFORM_HOSTED");
  const [model, setModel] = useState(row.model || "");

  return (
    <form
      className="mt-4 space-y-3 border-t border-border pt-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (!model.trim()) return;
        onSave(providerName, model.trim());
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor={`provider-${row.featureKey}`}>Provider</Label>
          <Select
            id={`provider-${row.featureKey}`}
            value={providerName}
            onValueChange={setProviderName}
            options={providerOptions.map((p) => ({
              value: p.name,
              label:
                (PROVIDER_LABELS[p.name] || p.name) +
                (p.name === "PLATFORM_HOSTED" ? "" : p.connected ? " · Connected" : " · Not connected yet"),
            }))}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor={`model-${row.featureKey}`}>Model</Label>
          <Input
            id={`model-${row.featureKey}`}
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder={tHC("hcAimodelsPage.s12")}
            autoFocus
            required
          />
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        {tHC("hcAimodelsPage.s11")}{" "}
        <Link href="/providers" className="underline underline-offset-2">
          Providers
        </Link>
        {tHC("hcAimodelsPage.s10")}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? "Saving…" : "Save"}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

export default function AiModelControlPage() {
    const tHC = useTranslations("hcAimodelsPage");
  const { hasPermission } = useAuth();
  const canManageSettings = hasPermission("MANAGE_TENANT_SETTINGS");
  const [settings, setSettings] = useState(null);
  const [providers, setProviders] = useState([]);
  const [rows, setRows] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [editingKey, setEditingKey] = useState(null);
  const [busyKey, setBusyKey] = useState(null);

  const refresh = useCallback(async () => {
    try {
      const [s, p, r] = await Promise.all([
        api.getTenantSettings(),
        api.listProviders().catch((): ProviderRow[] => []),
        api.getAiModelPreferences(),
      ]);
      setSettings(s);
      setProviders(Array.isArray(p) ? p : []);
      setRows(Array.isArray(r) ? r : []);
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, "Could not load your AI model settings."));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);
  usePageReady(loaded);

  const pg = usePagination(rows);

  // Per-feature model overrides are a BYOK-adjacent capability - a plan that
  // can't activate a provider key (FREE) has nothing meaningful to point an
  // override at either, same posture ProvidersPanel already takes for its
  // whole activation surface. The individual "Set override" buttons below
  // stay visible either way (upgradeLocked, not hidden) so a FREE tenant can
  // still see the upsell inline, matching the shared Button's documented
  // pattern rather than the page silently disappearing controls.
  const byokAllowed = !!settings?.byokAllowed;

  const connectedProviders = new Set(providers.filter((p) => p.status === "ACTIVE").map((p) => p.providerName));
  const providerOptions = KNOWN_PROVIDERS.map((name) => ({
    name,
    connected: name === "PLATFORM_HOSTED" ? true : connectedProviders.has(name),
  }));

  function labelFor(featureKey: string, list: FeatureRow[]) {
    return list.find((r) => r.featureKey === featureKey)?.featureLabel || featureKey;
  }

  async function handleSave(featureKey: string, providerName: string, model: string) {
    setBusyKey(featureKey);
    setError(null);
    setSuccess(null);
    try {
      const updated = await api.setAiModelPreference(featureKey, providerName, model);
      setRows(updated);
      setEditingKey(null);
      setSuccess(`Saved override for "${labelFor(featureKey, updated)}".`);
    } catch (err) {
      setError(errorMessage(err, "Could not save that override."));
    } finally {
      setBusyKey(null);
    }
  }

  async function handleClear(featureKey: string) {
    setBusyKey(featureKey);
    setError(null);
    setSuccess(null);
    try {
      const updated = await api.clearAiModelPreference(featureKey);
      setRows(updated);
      setSuccess("Override cleared.");
    } catch (err) {
      setError(errorMessage(err, "Could not clear that override."));
    } finally {
      setBusyKey(null);
    }
  }

  return (
    <AppShell eyebrow="Workspace">
      <PageHeader
        title={tHC("hcAimodelsPage.s9")}
        subtitle="Choose which provider and model each AI-touching feature in your workspace runs on, instead of the platform's built-in default."
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/providers">
              {tHC("hcAimodelsPage.s8")} <ArrowRight size={14} />
            </Link>
          </Button>
        }
      />

      {loadError && (
        <Card className="mb-4 gap-0 border-[var(--danger-border)] bg-[var(--danger-bg)] p-4 text-sm text-[var(--danger-text)]">
          {loadError}
        </Card>
      )}
      {error && (
        <Card className="mb-4 gap-0 border-[var(--danger-border)] bg-[var(--danger-bg)] p-4 text-sm text-[var(--danger-text)]">
          {error}
        </Card>
      )}
      {success && (
        <Card className="mb-4 gap-0 border-[var(--success-border)] bg-[var(--success-bg)] p-4 text-sm text-[var(--success-text)]">
          {success}
        </Card>
      )}

      {!canManageSettings && (
        <Card className="mb-4 gap-0 border-dashed p-4">
          <p className="text-sm text-muted-foreground">
            {tHC("hcAimodelsPage.s7")}
          </p>
        </Card>
      )}

      {canManageSettings && !byokAllowed && (
        <Card className="mb-4 gap-0 border-dashed p-4">
          <p className="text-sm text-muted-foreground">
            {poolLabel(
              'Your plan routes every AI feature through the shared platform-hosted pool. Upgrade to connect your own provider keys and choose a model per feature — hover any "Set override" button below to upgrade.',
              "Every AI feature runs on the local model (Ollama) on this machine. Connect a provider key to choose a cloud model per feature."
            )}
          </p>
        </Card>
      )}

      <Card className="mb-4 gap-0 border-dashed p-4">
        <p className="text-sm text-muted-foreground">
          {tHC("hcAimodelsPage.s6")}{" "}
          <Link href="/edge" className="underline underline-offset-2">
            {tHC("hcAimodelsPage.s5")}
          </Link>{" "}
          {tHC("hcAimodelsPage.s4")}
        </p>
      </Card>

      <Section
        title={`Features (${rows.length})`}
        description={
          "Default applies to every AI call that doesn't set a more specific feature below — it's the only row " +
          "wired into live routing today. The other rows are saved and validated now, and will take effect " +
          "automatically the moment that feature adopts per-feature routing; until then they follow Default."
        }
        className="space-y-3"
      >
        {pg.pageItems.map((row) => {
          const hasOverride = !!(row.providerName && row.model);
          const editing = editingKey === row.featureKey;
          const busy = busyKey === row.featureKey;
          const isDefault = row.featureKey === "default";

          return (
            <Card key={row.featureKey} className="gap-0 p-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0 space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-foreground">{row.featureLabel || row.featureKey}</span>
                    {isDefault ? (
                      <Badge variant="accent">{tHC("hcAimodelsPage.s3")}</Badge>
                    ) : (
                      <Badge variant="outline">{tHC("hcAimodelsPage.s2")}</Badge>
                    )}
                  </div>
                  {hasOverride ? (
                    <Badge variant="secondary" className="font-mono text-[0.7rem]">
                      {(PROVIDER_LABELS[row.providerName] || row.providerName) + " · " + row.model}
                    </Badge>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      {tHC("hcAimodelsPage.s1")}
                    </p>
                  )}
                </div>
                {canManageSettings && !editing && (
                  <div className="flex shrink-0 items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setEditingKey(row.featureKey)}
                      disabled={busy}
                      upgradeLocked={!byokAllowed}
                      upgradeReason="Per-feature model overrides need Bring-Your-Own-Key access, included from the Premium plan."
                    >
                      {hasOverride ? "Change" : "Set override"}
                    </Button>
                    {hasOverride && (
                      <Button size="sm" variant="ghost" onClick={() => handleClear(row.featureKey)} disabled={busy}>
                        {busy ? "Clearing…" : "Clear"}
                      </Button>
                    )}
                  </div>
                )}
              </div>
              {canManageSettings && byokAllowed && editing && (
                <EditForm
                  row={row}
                  providerOptions={providerOptions}
                  busy={busy}
                  onSave={(providerName, model) => handleSave(row.featureKey, providerName, model)}
                  onCancel={() => setEditingKey(null)}
                />
              )}
            </Card>
          );
        })}
        <PaginationControls
          page={pg.page}
          totalPages={pg.totalPages}
          totalItems={pg.totalItems}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          onPageSizeChange={pg.setPageSize}
        />
      </Section>
    </AppShell>
  );
}
