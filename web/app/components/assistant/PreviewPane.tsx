"use client";

// The Assistant's live Preview: the page of a dev server it started in the build container. The
// runner publishes the server's port on this computer's loopback, so the frame is plain
// http://<this host>:<port>. Several servers: a small switcher. Everything stays on this machine.

import { useState } from "react";
import { ExternalLink, RefreshCw, Square, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { WorkspacePreview } from "../../lib/api/assistant";
import Select from "../Select";
import { Button } from "../ui/button";

export function previewUrl(port: number): string {
  return `http://${window.location.hostname}:${port}/`;
}

export default function PreviewPane({
  previews,
  onStop,
  onClose,
}: {
  previews: WorkspacePreview[];
  onStop: (port: number) => void;
  onClose: () => void;
}) {
  const t = useTranslations("assistant");
  const [picked, setPicked] = useState<number | null>(null);
  const [nonce, setNonce] = useState(0);
  const current = previews.find((p) => p.port === picked) ?? previews[0];
  if (!current) return null;
  const url = previewUrl(current.port);

  return (
    <aside
      className="absolute inset-y-0 end-0 z-20 flex w-full flex-col border-s border-border bg-background shadow-lg xl:static xl:z-auto xl:w-[44%] xl:min-w-[22rem] xl:shadow-none"
      data-testid="preview-pane"
      aria-label={t("previewTitle")}
    >
      <header className="flex items-center gap-1.5 border-b border-border px-3 py-2">
        <span className="size-2 shrink-0 rounded-full bg-emerald-500" aria-hidden="true" />
        <h3 className="text-sm font-semibold text-foreground">{t("previewTitle")}</h3>
        {previews.length > 1 ? (
          <Select
            aria-label={t("previewPick")}
            value={String(current.port)}
            onChange={(e: { target: { value: string } }) => setPicked(Number(e.target.value))}
            options={previews.map((p) => ({
              value: String(p.port),
              label: `${p.folder || t("previewRoot")} :${p.port}`,
            }))}
            className="max-w-44 text-xs"
          />
        ) : (
          <span className="truncate text-xs text-muted-foreground">
            {current.folder || t("previewRoot")} · :{current.port}
          </span>
        )}
        <span className="flex-1" />
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label={t("previewReload")}
          title={t("previewReload")}
          onClick={() => setNonce((n) => n + 1)}
        >
          <RefreshCw size={14} aria-hidden="true" />
        </Button>
        <Button asChild size="icon" variant="ghost">
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={t("previewOpen")}
            title={t("previewOpen")}
          >
            <ExternalLink size={14} aria-hidden="true" />
          </a>
        </Button>
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label={t("previewStop")}
          title={t("previewStop")}
          onClick={() => onStop(current.port)}
        >
          <Square size={14} aria-hidden="true" />
        </Button>
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label={t("previewClose")}
          title={t("previewClose")}
          onClick={onClose}
        >
          <X size={14} aria-hidden="true" />
        </Button>
      </header>
      <iframe
        key={`${current.port}:${nonce}`}
        src={url}
        title={t("previewTitle")}
        className="min-h-0 w-full flex-1 bg-white"
        sandbox="allow-scripts allow-forms allow-modals allow-popups allow-same-origin"
      />
    </aside>
  );
}
