"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { Puzzle, RefreshCw, Upload } from "lucide-react";
import { api, API_BASE } from "../lib/api";
import AdminShell from "../components/AdminShell";
import Select from "../components/Select";
import { PageHint } from "../components/sections/common";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Textarea } from "../components/ui/textarea";
import { useTranslations } from "../../lib/i18n";

// First-party IDE/browser plugin distribution (VS Code / JetBrains / Chrome).
// What consumers see is the public marketplace page (web's /plugins) backed by
// GET /api/plugins/catalog - a platform setting (`plugins.catalog`) over an
// in-code seeded default, the exact edge-models pattern. This screen is the
// publish path: upload a built artifact (multipart POST, server computes
// fileName/sizeBytes/sha256 - never hand-entered), then the screen auto-merges
// that fragment + version + releasedAt into the matching catalog entry and
// PUTs the setting. The JSON panel at the bottom shows exactly what clients
// will receive; anything this v1 form doesn't cover (adding a brand-new
// pluginId, removing an entry) is a raw-settings-JSON edit on the settings
// screen, same posture as edge-models' non-toggle fields.

type PluginArtifact = { fileName: string; sizeBytes: number; sha256: string };

type PluginEntry = {
  id: string;
  displayName: string;
  description: string;
  latestVersion: string | null;
  releasedAt: string | null;
  releaseNotes: string | null;
  artifact: PluginArtifact | null;
  externalStoreUrl: string | null;
};

type PluginCatalog = { version: string; plugins: PluginEntry[] };

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return "0 KB";
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
}

export default function PluginsPage() {
    const tHC = useTranslations("hcPlatformadminPluginsPage");
  const [catalog, setCatalog] = useState<PluginCatalog | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Upload form. pluginId defaults to the first entry once the catalog loads.
  const [pluginId, setPluginId] = useState("");
  const [version, setVersion] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [releaseNotes, setReleaseNotes] = useState("");
  const [externalStoreUrl, setExternalStoreUrl] = useState("");
  const [uploading, setUploading] = useState(false);
  // Remount key for the file input - a controlled value can't clear a native
  // file picker after a successful publish.
  const [fileInputKey, setFileInputKey] = useState(0);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const cat: PluginCatalog = await api.getPluginCatalog();
      setCatalog(cat);
      setError(null);
      setPluginId((prev) => (prev && cat.plugins.some((p) => p.id === prev) ? prev : cat.plugins[0]?.id || ""));
    } catch (err: any) {
      setError(err?.message || String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Failed to load the plugin catalog", e));
  }, [refresh]);

  const selectedEntry = useMemo(
    () => catalog?.plugins.find((p) => p.id === pluginId) || null,
    [catalog, pluginId]
  );

  const publish = async (e: FormEvent) => {
    e.preventDefault();
    if (!catalog) return;
    const trimmedVersion = version.trim();
    if (!pluginId || !trimmedVersion || !file) {
      toast.error("Pick a plugin, enter a version, and choose an artifact file.");
      return;
    }
    setUploading(true);
    try {
      // 1. Upload - the server validates pluginId/version/extension and
      //    computes the digest; its response is the only source of
      //    fileName/sizeBytes/sha256.
      const stored = await api.uploadPluginArtifact(pluginId, trimmedVersion, file);

      // 2. Merge the fragment into the matching entry and bump the catalog
      //    version (numeric-string increment, same cheap "did it change"
      //    convention as the edge manifest).
      const prev = parseInt(catalog.version, 10);
      const next: PluginCatalog = {
        version: String(Number.isFinite(prev) ? prev + 1 : 2),
        plugins: catalog.plugins.map((p) =>
          p.id === pluginId
            ? {
                ...p,
                latestVersion: stored.version,
                releasedAt: new Date().toISOString(),
                artifact: stored.artifact,
                // Blank form fields mean "leave the stored value alone", so a
                // re-cut build doesn't silently wipe notes/store links.
                releaseNotes: releaseNotes.trim() || p.releaseNotes,
                externalStoreUrl: externalStoreUrl.trim() || p.externalStoreUrl,
              }
            : p
        ),
      };

      // 3. Persist - until this PUT lands, the uploaded file exists in
      //    storage but no client is pointed at it, so a failure here leaves
      //    the public catalog consistent (just retry the publish).
      await api.savePluginCatalog(JSON.stringify(next));
      toast.success(
        `Published ${pluginId} v${stored.version} (${formatSize(stored.artifact.sizeBytes)}). The public page picks it up within ~5 minutes (catalog cache).`
      );
      setVersion("");
      setFile(null);
      setReleaseNotes("");
      setExternalStoreUrl("");
      setFileInputKey((k) => k + 1);
      await refresh();
    } catch (err: any) {
      toast.error(err?.message || "Failed to publish the artifact.");
    } finally {
      setUploading(false);
    }
  };

  return (
    <AdminShell
      title={tHC("hcPlatformadminPluginsPage.s25")}
      eyebrow="Distribution"
      actions={
        <div className="flex items-center gap-2 flex-wrap">
          {catalog && <Badge variant="outline">{tHC("hcPlatformadminPluginsPage.s24")}{catalog.version}</Badge>}
          <Button variant="outline" size="sm" onClick={refresh} disabled={loading || uploading}>
            <RefreshCw size={14} aria-hidden /> Refresh
          </Button>
        </div>
      }
    >
      <PageHint>
        {tHC("hcPlatformadminPluginsPage.s23")} <code>.vsix</code>{tHC("hcPlatformadminPluginsPage.s22")}{" "}
        <code>.zip</code>{tHC("hcPlatformadminPluginsPage.s21")} <code>.zip</code>/<code>.crx</code>{tHC("hcPlatformadminPluginsPage.s20")}{" "}
        <code>/plugins</code> {tHC("hcPlatformadminPluginsPage.s19")} <code>plugins.catalog</code> {tHC("hcPlatformadminPluginsPage.s18")}
      </PageHint>

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm flex items-center gap-2">
            <Puzzle size={16} className="text-primary" aria-hidden /> {tHC("hcPlatformadminPluginsPage.s17")}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={publish} className="space-y-4">
            <div className="flex flex-wrap items-end gap-3">
              <div className="w-56 max-w-full space-y-1.5">
                <Label htmlFor="plugin-publish-id" className="text-xs text-muted-foreground">Plugin</Label>
                <Select
                  id="plugin-publish-id"
                  value={pluginId}
                  onChange={(e) => setPluginId(e.target.value)}
                  options={(catalog?.plugins || []).map((p) => ({ value: p.id, label: p.displayName }))}
                  disabled={loading || uploading}
                />
              </div>
              <div className="w-40 max-w-full space-y-1.5">
                <Label htmlFor="plugin-publish-version" className="text-xs text-muted-foreground">Version</Label>
                <Input
                  id="plugin-publish-version"
                  placeholder={tHC("hcPlatformadminPluginsPage.s16")}
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  disabled={uploading}
                />
              </div>
              <div className="w-72 max-w-full space-y-1.5">
                <Label htmlFor="plugin-publish-artifact" className="text-xs text-muted-foreground">{tHC("hcPlatformadminPluginsPage.s15")}</Label>
                <Input
                  id="plugin-publish-artifact"
                  key={fileInputKey}
                  type="file"
                  accept=".vsix,.zip,.crx"
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                  disabled={uploading}
                />
              </div>
            </div>
            <div className="flex flex-wrap items-end gap-3">
              <div className="w-[28rem] max-w-full space-y-1.5">
                <Label htmlFor="plugin-publish-notes" className="text-xs text-muted-foreground">{tHC("hcPlatformadminPluginsPage.s14")}</Label>
                <Textarea
                  id="plugin-publish-notes"
                  rows={2}
                  placeholder={tHC("hcPlatformadminPluginsPage.s13")}
                  value={releaseNotes}
                  onChange={(e) => setReleaseNotes(e.target.value)}
                  disabled={uploading}
                />
              </div>
              <div className="w-96 max-w-full space-y-1.5">
                <Label htmlFor="plugin-publish-store-url" className="text-xs text-muted-foreground">
                  {tHC("hcPlatformadminPluginsPage.s12")}
                </Label>
                <Input
                  id="plugin-publish-store-url"
                  type="url"
                  placeholder="https://marketplace.visualstudio.com/…"
                  value={externalStoreUrl}
                  onChange={(e) => setExternalStoreUrl(e.target.value)}
                  disabled={uploading}
                />
              </div>
            </div>
            {selectedEntry && (
              <p className="text-xs text-muted-foreground">
                {selectedEntry.artifact && selectedEntry.latestVersion ? (
                  <>
                    {tHC("hcPlatformadminPluginsPage.s11")}{selectedEntry.latestVersion} —{" "}
                    {selectedEntry.artifact.fileName} ({formatSize(selectedEntry.artifact.sizeBytes)}{tHC("hcPlatformadminPluginsPage.s10")}
                  </>
                ) : (
                  <>{tHC("hcPlatformadminPluginsPage.s9")}</>
                )}
              </p>
            )}
            <Button type="submit" size="sm" disabled={uploading || loading || !catalog}>
              <Upload size={14} aria-hidden /> {uploading ? "Publishing…" : "Upload & publish"}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Catalog</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {loading ? (
            <div className="text-sm text-muted-foreground">{tHC("hcPlatformadminPluginsPage.s8")}</div>
          ) : (
            (catalog?.plugins || []).map((p) => (
              <div key={p.id} className="flex items-start justify-between gap-4 rounded-md border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium">{p.displayName}</span>
                    {p.latestVersion ? (
                      <Badge variant="outline">v{p.latestVersion}</Badge>
                    ) : (
                      <Badge variant="secondary">{tHC("hcPlatformadminPluginsPage.s7")}</Badge>
                    )}
                    {p.artifact && <Badge variant="outline">{formatSize(p.artifact.sizeBytes)}</Badge>}
                    {p.externalStoreUrl && <Badge variant="secondary">{tHC("hcPlatformadminPluginsPage.s6")}</Badge>}
                  </div>
                  <p className="text-xs text-muted-foreground mt-1 font-mono break-all">{p.id}</p>
                  {p.artifact && p.latestVersion && (
                    <>
                      <p className="text-xs text-muted-foreground mt-1 break-all">
                        {/* Derived, not stored - same rule as the public card. */}
                        {API_BASE}/api/plugins/artifacts/{p.id}/{p.latestVersion}/{p.artifact.fileName}
                      </p>
                      <p className="text-xs text-muted-foreground mt-1 font-mono break-all">
                        sha256: {p.artifact.sha256}
                      </p>
                    </>
                  )}
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tHC("hcPlatformadminPluginsPage.s5")}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground mb-2">
            {tHC("hcPlatformadminPluginsPage.s4")} <code>{tHC("hcPlatformadminPluginsPage.s3")}</code> {tHC("hcPlatformadminPluginsPage.s2")}{" "}
            <code>plugins.catalog</code> {tHC("hcPlatformadminPluginsPage.s1")}
          </p>
          <pre className="overflow-x-auto rounded-md border bg-muted/30 p-3 text-xs leading-relaxed">
            {loading ? "Loading…" : JSON.stringify(catalog, null, 2)}
          </pre>
        </CardContent>
      </Card>
    </AdminShell>
  );
}
