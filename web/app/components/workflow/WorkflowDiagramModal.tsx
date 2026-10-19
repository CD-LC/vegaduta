"use client";

import { useRef } from "react";
import { X as CloseIcon, Share2, Download, Copy } from "lucide-react";
import { Button } from "../ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

export default function WorkflowDiagramModal({
  diagramFormat,
  diagramLoading,
  diagramPdfBusy,
  diagramShareBusy,
  diagramSource,
  downloadDiagramPdf,
  downloadDiagramSource,
  loadDiagram,
  setShowDiagramModal,
  shareDiagram,
  showDiagramModal,
  t,
}: any) {
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => setShowDiagramModal(false), { enabled: !!showDiagramModal });

  return (
    <>
      {/* Diagram export modal - Mermaid/PlantUML source (copyable/
       * downloadable as text) plus a real boxes-and-arrows PDF download and
       * a Share action (OS share sheet when available, clipboard-copy
       * fallback otherwise - see shareDiagram's own comment). */}
      {showDiagramModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => { if (e.target === e.currentTarget) setShowDiagramModal(false); }}
        >
          <div
            className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("diagramModalTitle")}</h2>
              <button
                type="button"
                className="-mr-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setShowDiagramModal(false)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">{t("diagramModalHint")}</p>

            <div className="mt-4 flex gap-2">
              <Button
                type="button"
                variant={diagramFormat === "mermaid" ? "default" : "outline"}
                size="sm"
                onClick={() => loadDiagram("mermaid")}
                disabled={diagramLoading}
              >
                Mermaid
              </Button>
              <Button
                type="button"
                variant={diagramFormat === "plantuml" ? "default" : "outline"}
                size="sm"
                onClick={() => loadDiagram("plantuml")}
                disabled={diagramLoading}
              >
                PlantUML
              </Button>
            </div>

            <div className="mt-3 rounded-lg border border-border bg-muted/30">
              <textarea
                readOnly
                value={diagramLoading ? t("loading") : diagramSource}
                className="h-64 w-full resize-none border-0 bg-transparent p-3 font-mono text-xs text-foreground outline-none"
                spellCheck={false}
              />
            </div>

            <div className="mt-4 flex flex-wrap justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => navigator.clipboard?.writeText(diagramSource)}
                disabled={diagramLoading || !diagramSource}
              >
                <Copy size={13} /> {t("copySource")}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={downloadDiagramSource}
                disabled={diagramLoading || !diagramSource}
              >
                <Download size={13} /> {t("downloadSource")}
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={downloadDiagramPdf} disabled={diagramPdfBusy}>
                <Download size={13} /> {diagramPdfBusy ? t("loading") : t("downloadPdf")}
              </Button>
              <Button type="button" size="sm" onClick={shareDiagram} disabled={diagramShareBusy}>
                <Share2 size={13} /> {diagramShareBusy ? t("loading") : t("shareDiagram")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
