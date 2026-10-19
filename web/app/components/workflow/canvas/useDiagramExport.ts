"use client";

import { useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage, errorName } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";

/** Hands a blob to the browser as a download via a throwaway object-URL anchor. */
export function saveBlobAs(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Diagram export (Mermaid / PlantUML source, PDF, share). Independent of any
 * run, so it lives in the main toolbar rather than the run inspector footer.
 */
export function useDiagramExport(opts: {
  workflowId: any;
  workflow: any;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const { workflowId, workflow, t } = opts;
  const [showDiagramModal, setShowDiagramModal] = useState(false);
  const [diagramFormat, setDiagramFormat] = useState("mermaid");
  const [diagramSource, setDiagramSource] = useState("");
  const [diagramLoading, setDiagramLoading] = useState(false);
  const [diagramPdfBusy, setDiagramPdfBusy] = useState(false);
  const [diagramShareBusy, setDiagramShareBusy] = useState(false);

  // `format` mirrors api.getWorkflowDiagram's own literal union - the only
  // two renderers WorkflowDiagramService supports.
  async function loadDiagram(format: "mermaid" | "plantuml") {
    setDiagramFormat(format);
    setDiagramLoading(true);
    try {
      const res = await api.getWorkflowDiagram(workflowId, format);
      setDiagramSource(res.source || "");
    } catch (err) {
      toast.error(errorMessage(err, t("diagramLoadError")));
      setDiagramSource("");
    } finally {
      setDiagramLoading(false);
    }
  }

  // Loads Mermaid source on open (the default format).
  async function openDiagramModal() {
    setShowDiagramModal(true);
    await loadDiagram("mermaid");
  }

  function downloadDiagramSource() {
    const extension = diagramFormat === "plantuml" ? "puml" : "mmd";
    saveBlobAs(new Blob([diagramSource], { type: "text/plain" }), `${workflow?.name || "workflow"}-diagram.${extension}`);
  }

  async function downloadDiagramPdf() {
    setDiagramPdfBusy(true);
    try {
      const { blob, filename } = await api.downloadWorkflowDiagramPdf(workflowId);
      saveBlobAs(blob, filename);
      return blob;
    } catch (err) {
      toast.error(errorMessage(err, t("diagramPdfError")));
      return null;
    } finally {
      setDiagramPdfBusy(false);
    }
  }

  // Hands the rendered PDF to the OS share sheet (Web Share API) when files
  // can be shared; otherwise copies the raw diagram source to the clipboard,
  // and as a last resort downloads the PDF.
  async function shareDiagram() {
    setDiagramShareBusy(true);
    try {
      const res = await api.downloadWorkflowDiagramPdf(workflowId);
      if (!res) return;
      const { blob, filename } = res;
      const file = new File([blob], filename, { type: "application/pdf" });
      if (typeof navigator !== "undefined" && navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: workflow?.name || t("diagramShareTitle") });
        return;
      }
      if (typeof navigator !== "undefined" && navigator.clipboard && diagramSource) {
        await navigator.clipboard.writeText(diagramSource);
        toast.success(t("diagramCopiedToClipboard"));
        return;
      }
      saveBlobAs(blob, filename);
    } catch (err) {
      if (errorName(err) !== "AbortError") {
        toast.error(errorMessage(err, t("diagramShareError")));
      }
    } finally {
      setDiagramShareBusy(false);
    }
  }

  return {
    openDiagramModal,
    modalProps: {
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
    },
  };
}
