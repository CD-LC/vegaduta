"use client";

// Public-directory publish panel for the agent trust console (page.tsx).

import { useState } from "react";
import { api } from "../../lib/api";
import type { DirectoryEntry } from "../../lib/api/agentTrust";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { csvList, fmtTime, statusBadge } from "./trustFormat";

// ---- Directory publish panel -------------------------------------------------

export function DirectoryPanel({
  agentId,
  entry,
  onChanged,
}: {
  agentId: string;
  entry: DirectoryEntry | undefined;
  onChanged: () => void;
}) {
  const tHC = useTranslations("hcAdminAgenttrustDirectoryPanel");
  const t = useTranslations("adminAgentTrustPage");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ displayName: "", description: "", capabilities: "", homepageUrl: "" });
  const [manifest, setManifest] = useState<Record<string, any> | null>(null);
  const [manifestError, setManifestError] = useState<string | null>(null);

  const published = entry?.status === "PUBLISHED";

  const handlePublish = async () => {
    setBusy(true);
    try {
      await api.publishToDirectory(agentId, {
        displayName: form.displayName || null,
        description: form.description || null,
        capabilities: csvList(form.capabilities),
        homepageUrl: form.homepageUrl || null,
      });
      toast.success(t("publishSuccess"));
      setOpen(false);
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("publishFailed"));
    } finally {
      setBusy(false);
    }
  };

  const handleUnpublish = async () => {
    setBusy(true);
    try {
      await api.unpublishFromDirectory(agentId);
      toast.success(t("unpublishSuccess"));
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("unpublishFailed"));
    } finally {
      setBusy(false);
    }
  };

  const handleManifest = async () => {
    setManifestError(null);
    try {
      setManifest(await api.getAgentManifest(agentId));
    } catch (e: any) {
      setManifestError(e?.message || t("manifestFailed"));
    }
  };

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">{t("directoryHeading")}</h2>
        <div className="flex items-center gap-2">
          {/* The directory lesson (l18-11) teaches exactly what publishing
              here does — kept as a quiet link rather than a second button. */}
          {entry && statusBadge(entry.status)}
          {published ? (
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={handleUnpublish}>
              {t("unpublish")}
            </Button>
          ) : (
            <Button type="button" size="sm" onClick={() => setOpen((v) => !v)}>
              {open ? t("cancel") : t("publish")}
            </Button>
          )}
          <Button type="button" variant="outline" size="sm" onClick={handleManifest}>
            {t("viewManifest")}
          </Button>
        </div>
      </div>
      {entry && (
        <p className="mb-3 text-xs text-muted-foreground">
          {t("directoryStatus", {
            status: entry.status,
            publishedAt: fmtTime(entry.publishedAt),
          })}
        </p>
      )}
      {open && !published && (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="dir-name">{t("fieldDisplayName")}</Label>
            <Input
              id="dir-name"
              value={form.displayName}
              onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="dir-url">{t("fieldHomepage")}</Label>
            <Input
              id="dir-url"
              value={form.homepageUrl}
              placeholder="https://…"
              onChange={(e) => setForm((f) => ({ ...f, homepageUrl: e.target.value }))}
            />
          </div>
          <div className="grid gap-1.5 sm:col-span-2">
            <Label htmlFor="dir-desc">{t("fieldDescription")}</Label>
            <Textarea
              id="dir-desc"
              rows={2}
              value={form.description}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
            />
          </div>
          <div className="grid gap-1.5 sm:col-span-2">
            <Label htmlFor="dir-caps">{t("fieldCapabilities")}</Label>
            <Input
              id="dir-caps"
              placeholder={tHC("s1")}
              value={form.capabilities}
              onChange={(e) => setForm((f) => ({ ...f, capabilities: e.target.value }))}
            />
          </div>
          <div className="sm:col-span-2">
            <Button type="button" size="sm" disabled={busy} onClick={handlePublish}>
              {t("publishConfirm")}
            </Button>
          </div>
        </div>
      )}
      {manifestError && <div className="mt-3 text-sm text-destructive">{manifestError}</div>}
      {manifest && (
        <pre className="mt-3 max-h-72 overflow-auto rounded-md border border-border bg-secondary/40 p-3 font-mono text-xs">
          {JSON.stringify(manifest, null, 2)}
        </pre>
      )}
    </Card>
  );
}
