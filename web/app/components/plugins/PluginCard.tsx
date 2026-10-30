"use client";

import { useState } from "react";
import { Braces, Check, Code2, Copy, Download, ExternalLink, Puzzle } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Chrome } from "../lucideBrandIcons";
import { API_BASE } from "../../lib/api";
import { Card, CardContent } from "../ui/card";
import { Button } from "../ui/button";
import type { PluginEntry } from "./serverFetch";
import { pluginDownloadPath } from "./serverFetch";
import { useTranslations } from "../../lib/i18n";

// One marketplace card. Honesty rule (same as TemplateCard): every figure
// shown - version, release date, size, sha256 - is real API data computed
// server-side at upload time; an entry with no published build says
// "Coming soon" instead of faking a download.

const PLUGIN_ICONS: Record<string, LucideIcon> = {
  vscode: Code2,
  jetbrains: Braces,
  chrome: Chrome,
};

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return "0 KB";
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
}

// Deterministic formatting (explicit locale + UTC): this renders during SSR
// and again on hydration, and a locale/timezone-dependent format would
// mismatch between the server's environment and the visitor's browser.
function formatReleasedAt(iso: string): string | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  }).format(date);
}

function Sha256Chip({ sha256 }: { sha256: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(sha256);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard unavailable (permissions / non-secure context) - the full
      // digest is still selectable text below, so silently doing nothing
      // beats an error popup for a convenience button.
    }
  };
  return (
    <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="shrink-0 font-medium">SHA-256</span>
      <code
        className="min-w-0 truncate rounded border border-border/70 bg-card px-1.5 py-0.5 font-mono text-[0.6875rem]"
        title={sha256}
      >
        {sha256}
      </code>
      <button
        type="button"
        onClick={copy}
        aria-label={copied ? "Checksum copied" : "Copy SHA-256 checksum"}
        className="inline-flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground"
      >
        {copied ? (
          <Check size={13} strokeWidth={2} aria-hidden="true" />
        ) : (
          <Copy size={13} strokeWidth={2} aria-hidden="true" />
        )}
      </button>
    </div>
  );
}

export default function PluginCard({ plugin }: { plugin: PluginEntry }) {
    const tHC = useTranslations("hcComponentsPluginsPluginCard");
  const Icon = PLUGIN_ICONS[plugin.id] ?? Puzzle;
  const released = plugin.releasedAt ? formatReleasedAt(plugin.releasedAt) : null;
  const downloadUrl =
    plugin.artifact && plugin.latestVersion
      ? `${API_BASE}${pluginDownloadPath(plugin.id, plugin.latestVersion, plugin.artifact.fileName)}`
      : null;

  return (
    <Card elevation="base" className="gap-0 p-0">
      <CardContent className="flex h-full flex-col gap-3 p-5">
        <div className="flex items-start justify-between gap-3">
          <span className="inline-flex size-9 shrink-0 items-center justify-center rounded-[0.55rem] border border-border bg-card text-primary">
            <Icon size={18} strokeWidth={2} aria-hidden="true" />
          </span>
          {plugin.latestVersion ? (
            <span className="inline-flex items-center gap-1.5 text-[0.6875rem] font-medium text-muted-foreground">
              v{plugin.latestVersion}
              {released && <span aria-hidden="true">·</span>}
              {released}
            </span>
          ) : (
            <span className="rounded-full border border-border/70 px-2 py-0.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {tHC("s4")}
            </span>
          )}
        </div>

        <div className="space-y-1">
          <h3 className="text-sm font-semibold leading-snug text-foreground">{plugin.displayName}</h3>
          <p className="text-sm leading-relaxed text-muted-foreground">{plugin.description}</p>
        </div>

        {plugin.releaseNotes && (
          <p className="rounded-md border border-border/70 bg-card px-3 py-2 text-xs leading-relaxed text-muted-foreground">
            {plugin.releaseNotes}
          </p>
        )}

        {plugin.artifact && <Sha256Chip sha256={plugin.artifact.sha256} />}

        <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
          {downloadUrl && plugin.artifact ? (
            <Button asChild size="sm">
              {/* Plain anchor, not a Next Link - this is a cross-origin file
                  download from the API, never a client-side navigation. */}
              <a href={downloadUrl} download={plugin.artifact.fileName}>
                <Download size={14} strokeWidth={2} aria-hidden="true" />
                {tHC("s3")}{formatSize(plugin.artifact.sizeBytes)})
              </a>
            </Button>
          ) : (
            <span className="text-xs text-muted-foreground">{tHC("s2")}</span>
          )}
          {plugin.externalStoreUrl && (
            <Button asChild variant="outline" size="sm">
              <a href={plugin.externalStoreUrl} target="_blank" rel="noopener noreferrer">
                <ExternalLink size={14} strokeWidth={2} aria-hidden="true" />
                {tHC("s1")}
              </a>
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
