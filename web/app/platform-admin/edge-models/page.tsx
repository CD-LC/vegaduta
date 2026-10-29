"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Plus, RefreshCw, RotateCcw, Save } from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { PageHint } from "../components/sections/common";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { useTranslations } from "../../lib/i18n";

// Edge AI model catalog - which on-device models consumers can see and
// download in the web app's VegaDuta Edge surface. The client reads
// GET /api/edge/manifest (5-minute public cache); what this screen edits is
// the platform setting `edge.model-manifest` that overrides the in-code
// default catalog (EdgeAgentsConfigController.DEFAULT_MANIFEST). Disabling a
// model here removes it from the manifest clients receive - devices that
// ALREADY downloaded it keep their local copy (we never reach into a user's
// browser storage), they just stop seeing it offered.
//
// v1 is deliberately enable/disable-only over the known catalog (defaults ∪
// stored) rather than free-form JSON editing - a malformed hand-written
// manifest silently falls back to the in-code default server-side, which
// would LOOK like this screen "not saving". The Reset action clears the
// override entirely (blank setting value -> server serves defaults again).

type EdgeModelEntry = {
  id: string;
  tier: number;
  displayName: string;
  detailName: string;
  sizeBytes: number;
  url: string | null;
  sha256: string | null;
  minDeviceMemoryGB: number | null;
  requiresWebGpu: boolean;
  kind: string;
  // Wave 2 (EDGE-C) + D-9 passthrough: this screen re-serializes rows into
  // the stored manifest on save, so any server field missing from this type
  // risks being stripped by a future field-by-field rebuild (today the row
  // objects pass through verbatim, but the type must carry the contract).
  // Both are read-only here — the server seeds them from the installed
  // WebLLM package's model records; editing them is a raw-settings-JSON
  // operation, not a v1 toggle-screen affordance.
  speedTier?: "fast" | "capable" | null;
  contextWindowSize?: number | null;
};

type EdgeModelManifest = { version: string; models: EdgeModelEntry[] };

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return "nothing to download";
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  return `${Math.round(bytes / 1_000_000)} MB`;
}

const TIER_LABELS: Record<number, string> = {
  0: "Browser built-in",
  1: "WebGPU (WebLLM)",
  2: "WASM / other",
};

const KIND_LABELS: Record<string, string> = {
  stt: "Speech-to-text",
  tts: "Text-to-speech",
  vision: "Vision",
};

const SPEED_TIER_LABELS: Record<string, string> = {
  fast: "fast (ordinary turns)",
  capable: "capable (complex turns)",
};

function formatContextWindow(tokens: number): string {
  return tokens % 1024 === 0 ? `${tokens / 1024}k-token context` : `${tokens}-token context`;
}

export default function EdgeModelsPage() {
    const tHC = useTranslations("hcPlatformadminEdgemodelsPage");
  const askConfirm = useConfirm();
  const [defaults, setDefaults] = useState<EdgeModelEntry[]>([]);
  const [effectiveVersion, setEffectiveVersion] = useState("1");
  const [overridePresent, setOverridePresent] = useState(false);
  // The editable state: model id -> enabled. Seeded from the effective
  // manifest (enabled = the model is currently served to clients).
  const [enabled, setEnabled] = useState<Record<string, boolean>>({});
  const [extraStored, setExtraStored] = useState<EdgeModelEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  // Browse-official-registry panel: curated WebLLM candidates not in the
  // catalog yet (REGISTRY_CATALOG). Loaded alongside the catalog itself -
  // it's one more cheap admin-only GET, no reason to gate it behind a
  // second click.
  const [registry, setRegistry] = useState<EdgeModelEntry[]>([]);
  const [registryLoading, setRegistryLoading] = useState(true);
  const [registryError, setRegistryError] = useState<string | null>(null);
  // Fixed on-device audio/vision utilities (Whisper STT, Kokoro TTS, SmolVLM
  // vision) - read-only, no enable/disable (see EdgeAgentsConfigController's
  // FIXED_UTILITIES javadoc: one hardcoded model each, nothing to toggle).
  const [fixedUtilities, setFixedUtilities] = useState<EdgeModelEntry[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const full = await api.getEdgeManifestFull();
      const effective: EdgeModelManifest = full.effective;
      const defaultManifest: EdgeModelManifest = full.defaults;
      setDefaults(defaultManifest.models);
      setEffectiveVersion(effective.version || "1");
      setOverridePresent(Boolean(full.storedOverridePresent));
      setFixedUtilities(full.fixedUtilities || []);
      const effIds = new Set(effective.models.map((m) => m.id));
      const map: Record<string, boolean> = {};
      for (const m of defaultManifest.models) map[m.id] = effIds.has(m.id);
      // Admin-added entries beyond the default catalog (edited via raw
      // settings JSON, or added from the registry panel below, at some
      // point) still deserve a row rather than being silently dropped on
      // the next save from this screen.
      const defIds = new Set(defaultManifest.models.map((m) => m.id));
      const extras = effective.models.filter((m) => !defIds.has(m.id));
      for (const m of extras) map[m.id] = true;
      setExtraStored(extras);
      setEnabled(map);
      setError(null);
      setDirty(false);
    } catch (err: any) {
      setError(err?.message || String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshRegistry = useCallback(async () => {
    setRegistryLoading(true);
    try {
      const candidates: EdgeModelEntry[] = await api.getEdgeRegistry();
      setRegistry(candidates);
      setRegistryError(null);
    } catch (err: any) {
      setRegistryError(err?.message || String(err));
    } finally {
      setRegistryLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Edge models refresh failed", e));
    refreshRegistry().catch((e) => console.error("Edge model registry refresh failed", e));
  }, [refresh, refreshRegistry]);

  const rows = useMemo(() => [...defaults, ...extraStored], [defaults, extraStored]);
  const enabledCount = rows.filter((m) => enabled[m.id]).length;
  const rowIds = useMemo(() => new Set(rows.map((m) => m.id)), [rows]);
  // Hide a candidate once it's in the catalog (added this session, or
  // already present in a stored override) - it now has a normal toggle row
  // above instead.
  const registryCandidates = useMemo(() => registry.filter((m) => !rowIds.has(m.id)), [registry, rowIds]);

  const registryPg = usePagination(registryCandidates, 10);

  const toggle = (id: string, value: boolean) => {
    setEnabled((prev) => ({ ...prev, [id]: value }));
    setDirty(true);
  };

  // One click: fold a registry candidate into the editable catalog, enabled,
  // same shape as an admin-added extra from a hand-edited manifest. Nothing
  // is served to clients until "Save catalog" persists it - identical to
  // toggling an existing row on.
  const addFromRegistry = (model: EdgeModelEntry) => {
    setExtraStored((prev) => [...prev, model]);
    setEnabled((prev) => ({ ...prev, [model.id]: true }));
    setDirty(true);
  };

  const save = async () => {
    if (enabledCount === 0) {
      toast.error(
        "At least one model must stay enabled - to hide the whole surface use the edge-agents kill switch instead."
      );
      return;
    }
    setSaving(true);
    try {
      // Version bump: numeric-string increment keeps the client's cheap
      // "did it change" comparison working; non-numeric stored versions
      // just restart at a timestamp-free counter.
      const prev = parseInt(effectiveVersion, 10);
      const nextVersion = String(Number.isFinite(prev) ? prev + 1 : 2);
      const manifest: EdgeModelManifest = {
        version: nextVersion,
        models: rows.filter((m) => enabled[m.id]),
      };
      await api.updatePlatformSetting("edge.model-manifest", JSON.stringify(manifest));
      toast.success("Edge model catalog saved. Clients pick it up within ~5 minutes (manifest cache).");
      await refresh();
    } catch (err: any) {
      toast.error(err?.message || "Failed to save the catalog.");
    } finally {
      setSaving(false);
    }
  };

  const resetToDefaults = async () => {
    if (
      !(await askConfirm(
        "Reset the edge model catalog to the built-in defaults? This clears your custom overrides immediately."
      ))
    )
      return;
    setSaving(true);
    try {
      await api.updatePlatformSetting("edge.model-manifest", "");
      toast.success("Override cleared - clients get the built-in default catalog again.");
      await refresh();
    } catch (err: any) {
      toast.error(err?.message || "Failed to reset.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <AdminShell
      title={tHC("hcPlatformadminEdgemodelsPage.s16")}
      eyebrow="On-device catalog"
      actions={
        <div className="flex items-center gap-2 flex-wrap">
          <Button variant="outline" size="sm" onClick={refresh} disabled={loading || saving}>
            <RefreshCw size={14} aria-hidden /> Refresh
          </Button>
          <Button variant="outline" size="sm" onClick={resetToDefaults} disabled={saving || !overridePresent}>
            <RotateCcw size={14} aria-hidden /> {tHC("hcPlatformadminEdgemodelsPage.s15")}
          </Button>
          <Button size="sm" onClick={save} disabled={saving || !dirty}>
            <Save size={14} aria-hidden /> {saving ? "Saving…" : "Save catalog"}
          </Button>
        </div>
      }
    >
      <PageHint>
        {tHC("hcPlatformadminEdgemodelsPage.s14")}<code>platform.edge-agents.enabled</code>{tHC("hcPlatformadminEdgemodelsPage.s13")}
      </PageHint>

      <div className="mb-4 flex items-center gap-2 flex-wrap">
        <Badge variant="outline">
          {enabledCount}/{rows.length} enabled
        </Badge>
        <Badge variant="secondary">{overridePresent ? "custom catalog active" : "serving built-in defaults"}</Badge>
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tHC("hcPlatformadminEdgemodelsPage.s12")}{effectiveVersion})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {loading ? (
            <div className="text-sm text-muted-foreground">{tHC("hcPlatformadminEdgemodelsPage.s11")}</div>
          ) : (
            rows.map((m) => (
              <div key={m.id} className="flex items-start justify-between gap-4 rounded-md border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium">{m.displayName}</span>
                    <Badge variant="outline">{TIER_LABELS[m.tier] ?? `tier ${m.tier}`}</Badge>
                    <Badge variant="outline">{formatSize(m.sizeBytes)}</Badge>
                    {m.requiresWebGpu && <Badge variant="secondary">{tHC("hcPlatformadminEdgemodelsPage.s6")}</Badge>}
                    {m.kind !== "llm" && <Badge variant="secondary">{m.kind}</Badge>}
                    {m.speedTier && <Badge variant="outline">{SPEED_TIER_LABELS[m.speedTier] ?? m.speedTier}</Badge>}
                    {m.contextWindowSize != null && m.contextWindowSize > 0 && (
                      <Badge variant="outline">{formatContextWindow(m.contextWindowSize)}</Badge>
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground mt-1 break-words">{m.detailName}</p>
                  <p className="text-xs text-muted-foreground mt-1 font-mono break-all">{m.id}</p>
                </div>
                {/* No Switch primitive in this app's ui/ set - a two-state
                    Button reads just as clearly and needs no new dependency. */}
                <Button
                  variant={enabled[m.id] ? "default" : "outline"}
                  size="sm"
                  className="shrink-0 w-24"
                  onClick={() => toggle(m.id, !enabled[m.id])}
                  disabled={saving}
                  aria-pressed={Boolean(enabled[m.id])}
                  aria-label={`Toggle ${m.displayName}`}
                >
                  {enabled[m.id] ? "Enabled" : "Disabled"}
                </Button>
              </div>
            ))
          )}
          <PaginationControls
            page={registryPg.page}
            totalPages={registryPg.totalPages}
            totalItems={registryPg.totalItems}
            pageSize={registryPg.pageSize}
            onPageChange={registryPg.setPage}
            onPageSizeChange={registryPg.setPageSize}
            itemLabel="models"
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            {tHC("hcPlatformadminEdgemodelsPage.s10")}
            <Badge variant="outline">{registryCandidates.length} available</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">
            {tHC("hcPlatformadminEdgemodelsPage.s9")}{" "}
            <strong>{tHC("hcPlatformadminEdgemodelsPage.s8")}</strong>.
          </p>
          {registryError && (
            <Alert variant="destructive">
              <AlertDescription>{registryError}</AlertDescription>
            </Alert>
          )}
          {registryLoading ? (
            <div className="text-sm text-muted-foreground">{tHC("hcPlatformadminEdgemodelsPage.s7")}</div>
          ) : registryCandidates.length === 0 ? (
            <div className="text-sm text-muted-foreground">
              {registry.length === 0
                ? "No registry candidates configured."
                : "Every registry candidate is already in the catalog above."}
            </div>
          ) : (
            registryPg.pageItems.map((m) => (
              <div key={m.id} className="flex items-start justify-between gap-4 rounded-md border border-dashed p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium">{m.displayName}</span>
                    <Badge variant="outline">{TIER_LABELS[m.tier] ?? `tier ${m.tier}`}</Badge>
                    <Badge variant="outline">{formatSize(m.sizeBytes)}</Badge>
                    {m.requiresWebGpu && <Badge variant="secondary">{tHC("hcPlatformadminEdgemodelsPage.s6")}</Badge>}
                    {m.speedTier && <Badge variant="outline">{SPEED_TIER_LABELS[m.speedTier] ?? m.speedTier}</Badge>}
                    {m.contextWindowSize != null && m.contextWindowSize > 0 && (
                      <Badge variant="outline">{formatContextWindow(m.contextWindowSize)}</Badge>
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground mt-1 break-words">{m.detailName}</p>
                  <p className="text-xs text-muted-foreground mt-1 font-mono break-all">{m.id}</p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  className="shrink-0 w-24"
                  onClick={() => addFromRegistry(m)}
                  disabled={saving}
                  aria-label={`Add ${m.displayName} to the catalog`}
                >
                  <Plus size={14} aria-hidden /> Add
                </Button>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tHC("hcPlatformadminEdgemodelsPage.s5")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">
            {tHC("hcPlatformadminEdgemodelsPage.s4")} <code>platform.edge-agents.enabled</code> {tHC("hcPlatformadminEdgemodelsPage.s3")}
          </p>
          {fixedUtilities.length === 0 ? (
            <div className="text-sm text-muted-foreground">{tHC("hcPlatformadminEdgemodelsPage.s2")}</div>
          ) : (
            fixedUtilities.map((m) => (
              <div key={m.id} className="flex items-start justify-between gap-4 rounded-md border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium">{m.displayName}</span>
                    <Badge variant="outline">{KIND_LABELS[m.kind] ?? m.kind}</Badge>
                    <Badge variant="outline">{formatSize(m.sizeBytes)}</Badge>
                  </div>
                  <p className="text-sm text-muted-foreground mt-1 break-words">{m.detailName}</p>
                  <p className="text-xs text-muted-foreground mt-1 font-mono break-all">{m.id}</p>
                </div>
                <Badge variant="secondary" className="shrink-0">
                  {tHC("hcPlatformadminEdgemodelsPage.s1")}
                </Badge>
              </div>
            ))
          )}
        </CardContent>
      </Card>
    </AdminShell>
  );
}
