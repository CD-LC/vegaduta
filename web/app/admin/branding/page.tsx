"use client";

import { useTranslations } from "../../lib/i18n";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ImageUp, Loader2, Sparkles, Trash2, TriangleAlert } from "lucide-react";
import { api } from "../../lib/api";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { isBillingEnabled } from "../../lib/billing-flag";

// White-label branding settings — the tenant-facing editor for the two
// brand_display_name / brand_logo_url columns V162 added. Those columns have
// existed (and been rendered on the public share pages, the workflow share
// page and the embeddable widget) since that migration, but there was no UI
// anywhere in web/ or web-admin/ that could WRITE them: the only write path
// was PUT /api/tenant/settings, i.e. curl. And even by curl, brandLogoUrl had
// to be an absolute https URL the tenant was already hosting themselves,
// which for most customers means the entitlement they are paying for is not
// actually usable. This page is that missing surface, and the logo field is
// a real upload (POST /api/tenant/branding/logo) rather than a URL box.
//
// The URL box is still here, below the uploader, because the
// bring-your-own-URL path is a genuine preference for anyone already serving
// their assets from their own CDN — the upload didn't replace it.
//
// Not yet translated (no useTranslations): a new page is ~25 new message
// keys and every key has to exist in all 30 locale files or it renders its
// own dotted path (see lib/i18n.tsx). That belongs in the next
// I18N-MIGRATION-PLAN batch alongside the other untranslated admin screens,
// not in a drive-by edit of en.json while other sessions are writing it.

/** Mirrors TenantBrandingLogoController#MAX_LOGO_BYTES — checked here too so an oversized pick fails instantly instead of after a 2MB round trip. */
const MAX_LOGO_BYTES = 2 * 1024 * 1024;

/**
 * Mirrors that controller's CONTENT_TYPE_TO_EXTENSION. SVG is deliberately
 * absent in both places: it is a script-bearing document, and the logo is
 * served from the API origin to anonymous viewers.
 */
const ACCEPTED_TYPES = ["image/png", "image/jpeg", "image/jpg", "image/webp"];

interface TenantSettings {
  experienceMode?: string | null;
  plan?: string | null;
  whiteLabel?: boolean;
  organizationName?: string | null;
  brandDisplayName?: string | null;
  brandLogoUrl?: string | null;
}

function AdminBrandingInner() {
  const tHC = useTranslations("hcAdminBrandingPage");
  const tg = useTranslations("f2GapsAdmin");
  // MANAGE_TENANT_SETTINGS scope (granular RBAC, 2026-09-23) - was blanket
  // isAdmin; branding/settings is its own delegatable scope now.
  const allowed = useRequireScope("MANAGE_TENANT_SETTINGS");

  const [settings, setSettings] = useState<TenantSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [displayName, setDisplayName] = useState("");
  const [logoUrlDraft, setLogoUrlDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // Applies a fresh TenantSettingsResponse — every write endpoint returns the
  // whole settings object, so this is the single place server truth lands and
  // the form drafts are reset from it. Keeps the two text inputs from holding
  // a stale draft after a successful save.
  const applySettings = useCallback((next: TenantSettings) => {
    setSettings(next);
    setDisplayName(next?.brandDisplayName || "");
    setLogoUrlDraft(next?.brandLogoUrl || "");
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .getTenantSettings()
      .then((s: TenantSettings) => {
        if (cancelled) return;
        applySettings(s);
        setLoadError(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoadError(e instanceof Error ? e.message : tg("brandingLoadFailed"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tg only supplies a fallback message
  }, [applySettings]);

  const whiteLabel = !!settings?.whiteLabel;
  const experienceMode = settings?.experienceMode ?? null;
  const logoUrl = settings?.brandLogoUrl || null;
  const busy = saving || uploading || removing;

  function reportError(e: unknown, fallback: string) {
    setNotice(null);
    setError(e instanceof Error && e.message ? e.message : fallback);
  }

  async function saveTextFields(nextDisplayName: string, nextLogoUrl: string) {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      // "" (not null) is what clears a field server-side — null means "leave
      // unchanged" (see UpdateExperienceModeRequest). The drafts are already
      // "" when emptied, so they map straight through.
      const updated = await api.setTenantBranding(experienceMode, nextDisplayName.trim(), nextLogoUrl.trim());
      applySettings(updated);
      setNotice("Branding saved.");
    } catch (e) {
      reportError(e, "Could not save your branding.");
    } finally {
      setSaving(false);
    }
  }

  async function onPickFile(file: File | null | undefined) {
    if (!file) return;
    setError(null);
    setNotice(null);
    // Both checks exist server-side too; these just make a bad pick fail
    // immediately and with the same wording, rather than after the upload.
    if (!ACCEPTED_TYPES.includes(file.type)) {
      setError(
        `${file.name} is a ${file.type || "unrecognised"} file. Logos must be PNG, JPEG or WebP — ` +
          "SVG is not accepted because it can carry scripts."
      );
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      setError(
        `${file.name} is ${(file.size / 1024 / 1024).toFixed(1)}MB. The limit is ${MAX_LOGO_BYTES / 1024 / 1024}MB.`
      );
      return;
    }
    setUploading(true);
    try {
      const updated = await api.uploadTenantBrandingLogo(file);
      applySettings(updated);
      setNotice("Logo uploaded. It is live on your shared agent pages now.");
    } catch (e) {
      reportError(e, "Could not upload that logo.");
    } finally {
      setUploading(false);
      // Always clear the input's value: without this, picking the SAME file
      // again after a failure fires no change event and the retry looks dead.
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }

  async function onRemoveLogo() {
    setRemoving(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.removeTenantBrandingLogo();
      applySettings(updated);
      setNotice("Logo removed. Shared pages show the VegaDūta mark again.");
    } catch (e) {
      reportError(e, "Could not remove your logo.");
    } finally {
      setRemoving(false);
    }
  }

  if (!allowed) return null;

  return (
    <AppShell eyebrow="Branding">
      <PageHeader
        eyebrow="White-label"
        title={tHC("s19")}
        subtitle="Replace the VegaDūta name and mark with your own on the pages your customers see — shared agents, shared workflows and the embeddable chat widget."
      />

      {loading && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {tHC("s18")}
        </div>
      )}

      {!loading && loadError && (
        <Alert variant="destructive">
          <TriangleAlert className="size-4" aria-hidden="true" />
          <AlertTitle>{tg("brandingLoadFailedTitle")}</AlertTitle>
          <AlertDescription>{loadError}</AlertDescription>
        </Alert>
      )}

      {!loading && !loadError && !whiteLabel && (
        // The server refuses these writes with a 403 on a plan without the
        // entitlement, so the form is not rendered at all rather than shown
        // and then failing on save. Reads stay open on every plan, which is
        // why any values already stored are still shown below.
        <Alert className="mb-6">
          <Sparkles className="size-4" aria-hidden="true" />
          <AlertTitle>{tHC("s17")}</AlertTitle>
          <AlertDescription className="flex flex-col items-start gap-3">
            <span>
              {tHC("s16")}
              {settings?.plan ? ` (${settings.plan})` : ""} {tHC("s15")}
            </span>
            {isBillingEnabled() && (
              <Button asChild size="sm">
                <Link href="/admin/billing">{tHC("s14")}</Link>
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}

      {!loading && !loadError && (
        <div className="flex flex-col gap-6">
          {error && (
            <Alert variant="destructive">
              <TriangleAlert className="size-4" aria-hidden="true" />
              <AlertTitle>{tHC("s13")}</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          {notice && (
            <Alert>
              <AlertTitle>Saved</AlertTitle>
              <AlertDescription>{notice}</AlertDescription>
            </Alert>
          )}

          <Card>
            <CardHeader>
              <CardTitle>Logo</CardTitle>
              <CardDescription>{tHC("s12")}</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div className="flex flex-wrap items-center gap-4">
                {/* Checkerboard behind the preview: most logos are
                    transparent PNGs, and on a plain card background a white
                    mark is invisible — which reads as "the upload failed". */}
                <div
                  className="flex h-20 w-40 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-border"
                  style={{
                    backgroundImage:
                      "linear-gradient(45deg, var(--surface-hover) 25%, transparent 25%, transparent 75%, var(--surface-hover) 75%), linear-gradient(45deg, var(--surface-hover) 25%, transparent 25%, transparent 75%, var(--surface-hover) 75%)",
                    backgroundSize: "12px 12px",
                    backgroundPosition: "0 0, 6px 6px",
                  }}
                >
                  {logoUrl ? (
                    // Plain <img>, not next/image: next.config.js's
                    // images.remotePatterns allows only i.ytimg.com, and the
                    // three existing render sites for this exact URL (both
                    // share pages and the widget) all use a plain <img> too.
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={logoUrl} alt={tHC("s11")} className="max-h-16 max-w-36 object-contain" />
                  ) : (
                    <span className="px-2 text-center text-xs text-muted-foreground">{tHC("s10")}</span>
                  )}
                </div>

                <div className="flex min-w-0 flex-col gap-2">
                  <input
                    ref={fileInputRef}
                    type="file"
                    aria-label={tHC("s9")}
                    accept={ACCEPTED_TYPES.join(",")}
                    className="sr-only"
                    onChange={(e) => void onPickFile(e.target.files?.[0])}
                    disabled={!whiteLabel || busy}
                  />
                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      type="button"
                      size="sm"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={!whiteLabel || busy}
                      upgradeLocked={!whiteLabel}
                      upgradeReason="White-labelling is included on the Unlimited plan."
                    >
                      {uploading ? (
                        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                      ) : (
                        <ImageUp className="size-4" aria-hidden="true" />
                      )}
                      {logoUrl ? "Replace logo" : "Upload logo"}
                    </Button>
                    {logoUrl && whiteLabel && (
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        onClick={() => void onRemoveLogo()}
                        disabled={busy}
                      >
                        {removing ? (
                          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                        ) : (
                          <Trash2 className="size-4" aria-hidden="true" />
                        )}
                        Remove
                      </Button>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground">{tHC("s8")}</p>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{tHC("s7")}</CardTitle>
              <CardDescription>{tHC("s6")}</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <Label htmlFor="brand-display-name">{tHC("s5")}</Label>
              <Input
                id="brand-display-name"
                value={displayName}
                maxLength={64}
                placeholder={settings?.organizationName || "Acme Support"}
                onChange={(e) => setDisplayName(e.target.value)}
                disabled={!whiteLabel || busy}
              />
              <div className="flex items-center gap-3">
                <Button
                  type="button"
                  size="sm"
                  onClick={() => void saveTextFields(displayName, logoUrlDraft)}
                  disabled={!whiteLabel || busy}
                  upgradeLocked={!whiteLabel}
                  upgradeReason="White-labelling is included on the Unlimited plan."
                >
                  {saving && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
                  Save
                </Button>
                <span className="text-xs text-muted-foreground">{displayName.length}/64</span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{tHC("s4")}</CardTitle>
              <CardDescription>{tHC("s3")}</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <Label htmlFor="brand-logo-url">{tHC("s2")}</Label>
              <Input
                id="brand-logo-url"
                value={logoUrlDraft}
                maxLength={512}
                placeholder="https://cdn.example.com/logo.png"
                onChange={(e) => setLogoUrlDraft(e.target.value)}
                disabled={!whiteLabel || busy}
              />
              <div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => void saveTextFields(displayName, logoUrlDraft)}
                  disabled={!whiteLabel || busy}
                  upgradeLocked={!whiteLabel}
                  upgradeReason="White-labelling is included on the Unlimited plan."
                >
                  {saving && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
                  {tHC("s1")}
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}
    </AppShell>
  );
}

export default function AdminBrandingPage() {
  return <AdminBrandingInner />;
}
