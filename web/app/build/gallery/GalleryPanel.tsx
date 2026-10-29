"use client";

// Template gallery for the App Builder: search + filter ready-made Studio apps
// and server templates, start a project from one, and export/import a Studio
// project as a JSON template bundle. All state is local; nothing is uploaded.

import { useMemo, useRef, useState } from "react";
import { Download, Search, Upload } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Select } from "../../components/ui/select";
import { withHarness } from "../studio/runtime";
import { GALLERY, type GalleryCategory, type GalleryItem } from "./galleryData";
import {
  bundleFilename,
  categoriesOf,
  exportBundle,
  MAX_BUNDLE_BYTES,
  REMIX_TARGETS,
  filterGallery,
  parseBundle,
  remixPrompt,
  type TemplateBundle,
} from "./galleryHelpers";
import { useTranslations } from "../../lib/i18n";

export interface GalleryProject {
  id: string;
  name: string;
  emoji: string;
  html: string;
}

interface Props {
  /** Open a Studio template as a new project. */
  onUseStudio: (t: {
    name: string;
    emoji: string;
    html: string;
    note: string;
    /** A "Make it yours" remix: the adapt request to pre-fill. */ adapt?: string;
  }) => void;
  /** Start the Create flow from a server template. Omit to hide the action. */
  onPickServer?: (templateId: string) => void;
  /** The person's Studio projects, offered for "Export as template". */
  projects?: GalleryProject[];
  items?: GalleryItem[];
}

const THUMB_W = 390;
const THUMB_H = 560;
const SCALE = 0.4;

function Thumb({ html }: { html: string }) {
  // Sandboxed like the real preview (scripts only, opaque origin); the harness
  // supplies an in-memory localStorage so apps render exactly as they would.
  return (
    <div
      className="relative overflow-hidden rounded-xl border border-border bg-muted/30"
      style={{ width: THUMB_W * SCALE, height: 150 }}
      aria-hidden
    >
      <iframe
        title="preview"
        loading="lazy"
        tabIndex={-1}
        sandbox="allow-scripts"
        srcDoc={withHarness(html)}
        style={{
          width: THUMB_W,
          height: THUMB_H,
          transform: `scale(${SCALE})`,
          transformOrigin: "top left",
          border: 0,
          pointerEvents: "none",
        }}
      />
    </div>
  );
}

function download(filename: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export default function GalleryPanel({ onUseStudio, onPickServer, projects = [], items = GALLERY }: Props) {
    const tHC = useTranslations("hcBuildGalleryGalleryPanel");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<GalleryCategory | "All">("All");
  const [kind, setKind] = useState<"all" | "studio" | "server">("all");
  const [msg, setMsg] = useState<{ tone: "ok" | "err"; text: string } | null>(null);
  const [exportId, setExportId] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  const cats = useMemo(() => categoriesOf(items), [items]);
  const shown = useMemo(() => filterGallery(items, { query, category, kind }), [items, query, category, kind]);

  const importText = (text: string) => {
    const r = parseBundle(text);
    if ("error" in r) return setMsg({ tone: "err", text: r.error });
    const b: TemplateBundle = r.bundle;
    onUseStudio({ name: b.name, emoji: b.emoji, html: b.html, note: "Imported from a template bundle" });
    setMsg({ tone: "ok", text: `Imported "${b.name}".` });
  };

  const onFile = async (f: File | undefined) => {
    if (!f) return;
    if (f.size > MAX_BUNDLE_BYTES)
      return setMsg({ tone: "err", text: `The file is too large (limit ${Math.round(MAX_BUNDLE_BYTES / 1024)} KB).` });
    importText(await f.text());
    if (fileRef.current) fileRef.current.value = "";
  };

  const doExport = () => {
    const p = projects.find((x) => x.id === exportId);
    if (!p) return;
    download(bundleFilename(p.name), exportBundle({ name: p.name, emoji: p.emoji, html: p.html }));
    setMsg({ tone: "ok", text: `Exported "${p.name}". The file has the app only, none of its saved data.` });
  };

  return (
    <section className="flex flex-col gap-4" aria-label={tHC("hcBuildGalleryGalleryPanel.s13")}>
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 className="text-lg font-semibold text-foreground">{tHC("hcBuildGalleryGalleryPanel.s13")}</h2>
          <p className="text-xs text-muted-foreground">{tHC("hcBuildGalleryGalleryPanel.s12")}</p>
        </div>
        <div className="relative w-full sm:w-64">
          <Search
            aria-hidden="true"
            size={14}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            value={query}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => setQuery(e.target.value)}
            placeholder={tHC("hcBuildGalleryGalleryPanel.s11")}
            className="ps-8"
            aria-label={tHC("hcBuildGalleryGalleryPanel.s10")}
          />
        </div>
      </div>

      <div className="flex flex-wrap gap-2" role="group" aria-label="Filters">
        {(["All", ...cats] as const).map((c) => (
          <button
            key={c}
            type="button"
            onClick={() => setCategory(c)}
            aria-pressed={category === c}
            className={`rounded-full border px-3 py-1 text-xs font-medium transition ${category === c ? "border-primary bg-primary text-primary-foreground" : "border-border bg-card text-foreground hover:border-primary/50"}`}
          >
            {c}
          </button>
        ))}
        <Select
          value={kind}
          onValueChange={(v) => setKind(v as "all" | "studio" | "server")}
          aria-label={tHC("hcBuildGalleryGalleryPanel.s9")}
          className="ms-auto w-44"
          options={[
            { value: "all", label: "All types" },
            { value: "studio", label: "Instant apps" },
            { value: "server", label: "Project templates" },
          ]}
        />
      </div>

      {shown.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          {tHC("hcBuildGalleryGalleryPanel.s8")}
        </p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {shown.map((t) => (
            <div key={t.id} className="flex flex-col gap-2 rounded-2xl border border-border bg-card p-3">
              {t.kind === "studio" ? (
                <Thumb html={t.html} />
              ) : (
                <div
                  className="grid place-items-center rounded-xl border border-border bg-muted/30 text-4xl"
                  style={{ width: THUMB_W * SCALE, height: 150 }}
                  aria-hidden
                >
                  {t.emoji}
                </div>
              )}
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-foreground">
                  {t.emoji} {t.name}
                </p>
                <p className="text-xs text-muted-foreground">{t.blurb}</p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  {t.category}
                  {t.kind === "server" ? ` · ${t.stack}` : " · opens instantly"}
                </p>
              </div>
              {t.kind === "studio" ? (
                <>
                  <Button
                    size="sm"
                    onClick={() =>
                      onUseStudio({
                        name: t.name,
                        emoji: t.emoji,
                        html: t.html,
                        note: `Started from the ${t.name} template`,
                      })
                    }
                  >
                    {tHC("hcBuildGalleryGalleryPanel.s7")}
                  </Button>
                  <div className="flex flex-wrap items-center gap-1" role="group" aria-label={`Make ${t.name} yours`}>
                    <span className="text-[11px] text-muted-foreground">{tHC("hcBuildGalleryGalleryPanel.s6")}</span>
                    {REMIX_TARGETS.map((r) => (
                      <button
                        key={r.id}
                        type="button"
                        title={`Open ${t.name} and pre-fill a request to adapt it for a ${r.label.toLowerCase()}`}
                        onClick={() =>
                          onUseStudio({
                            name: t.name,
                            emoji: t.emoji,
                            html: t.html,
                            note: `Started from the ${t.name} template`,
                            adapt: remixPrompt(t.name, r.label),
                          })
                        }
                        className="min-h-7 rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground transition hover:border-primary/50 hover:text-foreground"
                      >
                        {r.emoji} {r.label}
                      </button>
                    ))}
                  </div>
                </>
              ) : onPickServer ? (
                <Button size="sm" variant="outline" onClick={() => onPickServer(t.templateId)}>
                  {tHC("hcBuildGalleryGalleryPanel.s5")}
                </Button>
              ) : null}
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-2 rounded-2xl border border-border bg-card p-4">
        <p className="text-sm font-semibold text-foreground">{tHC("hcBuildGalleryGalleryPanel.s4")}</p>
        <p className="text-xs text-muted-foreground">
          Export one of your apps as a template file, or import a file someone sent you. Saved data is never included.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={exportId}
            onValueChange={setExportId}
            aria-label={tHC("hcBuildGalleryGalleryPanel.s3")}
            className="w-56"
            disabled={projects.length === 0}
            placeholder={projects.length ? "Choose an app..." : "No apps yet"}
            options={projects.map((p) => ({ value: p.id, label: `${p.emoji} ${p.name}` }))}
          />
          <Button size="sm" variant="outline" className="gap-1.5" disabled={!exportId} onClick={doExport}>
            <Download aria-hidden="true" size={14} /> Export
          </Button>
          <Button size="sm" variant="outline" className="gap-1.5" onClick={() => fileRef.current?.click()}>
            <Upload aria-hidden="true" size={14} /> {tHC("hcBuildGalleryGalleryPanel.s2")}
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(e) => void onFile(e.target.files?.[0])}
            aria-label={tHC("hcBuildGalleryGalleryPanel.s1")}
          />
        </div>
        {msg && (
          <p
            role="status"
            className={`text-xs ${msg.tone === "err" ? "text-destructive" : "text-[var(--success-text)]"}`}
          >
            {msg.text}
          </p>
        )}
      </div>
    </section>
  );
}
