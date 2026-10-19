"use client";

import { useRef, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";

/**
 * Run experience (#345): the runtime inputs a run is started with (free text,
 * comment, attached files, key=value parameters). All of it is per-run only,
 * never persisted on the workflow - openRunModal resets it to blank.
 */
export function useRunModal(opts: { t: (key: string, values?: Record<string, unknown>) => string }) {
  const { t } = opts;
  const [showRunModal, setShowRunModal] = useState(false);
  const [runInput, setRunInput] = useState("");
  const [runComment, setRunComment] = useState("");
  const [runAttachments, setRunAttachments] = useState<{ name: string; documentId: any }[]>([]);
  const [runParams, setRunParams] = useState([{ key: "", value: "" }]);
  const [runAttachBusy, setRunAttachBusy] = useState(false);
  const runFileInputRef = useRef(null);
  // simulateSends marks the run as a test run (sends are dry-run on core).
  const [simulateSends, setSimulateSends] = useState(false);

  function openRunModal() {
    setSimulateSends(false);
    setRunComment("");
    setRunAttachments([]);
    setRunParams([{ key: "", value: "" }]);
    setShowRunModal(true);
  }

  // Test run from the plan panel: the ordinary run modal, flagged so the run
  // goes out with simulateSends. openRunModal clears the flag, so the topbar Run stays real.
  function openTestRun() {
    openRunModal();
    setSimulateSends(true);
  }

  // Uploads straight into the tenant's knowledge base (same pattern as Quick
  // Task), so any Agent/Knowledge node can retrieve it via the search tool.
  async function attachRunFile(e: any) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setRunAttachBusy(true);
    try {
      const doc = await api.uploadKnowledgeFile(file, null);
      setRunAttachments((prev) => [...prev, { name: file.name, documentId: doc?.documentId }]);
      toast.success(t("attachFileSuccess", { name: file.name }));
    } catch (err) {
      toast.error(errorMessage(err, t("attachFileError")));
    } finally {
      setRunAttachBusy(false);
    }
  }

  function removeRunAttachment(index: any) {
    setRunAttachments((prev) => prev.filter((_, i) => i !== index));
  }

  function updateRunParam(index: any, field: any, value: any) {
    setRunParams((prev) => prev.map((p, i) => (i === index ? { ...p, [field]: value } : p)));
  }

  function addRunParam() {
    setRunParams((prev) => [...prev, { key: "", value: "" }]);
  }

  function removeRunParam(index: any) {
    setRunParams((prev) => prev.filter((_, i) => i !== index));
  }

  // Folds comment/attachments/parameters into the single `input` string the
  // backend's /run endpoint accepts (RunWorkflowRequest has no dedicated
  // fields for these): a client-side composition of the existing contract.
  function buildRunInputPayload() {
    const parts = [];
    if (runInput?.trim()) parts.push(runInput.trim());
    if (runComment?.trim()) parts.push(`Run comment: ${runComment.trim()}`);
    if (runAttachments.length > 0) {
      parts.push(
        `Attached document(s) available in the knowledge base for this run: ${runAttachments.map((a) => a.name).join(", ")}`
      );
    }
    const paramEntries = runParams.filter((p) => p.key.trim());
    if (paramEntries.length > 0) {
      parts.push(`Parameters: ${paramEntries.map((p) => `${p.key.trim()}=${p.value}`).join("; ")}`);
    }
    return parts.join("\n\n");
  }

  return {
    simulateSends,
    openRunModal,
    openTestRun,
    buildRunInputPayload,
    closeRunModal: () => setShowRunModal(false),
    modalProps: {
      addRunParam,
      attachRunFile,
      removeRunAttachment,
      removeRunParam,
      runAttachBusy,
      runAttachments,
      runComment,
      runFileInputRef,
      runInput,
      runParams,
      setRunComment,
      setRunInput,
      setShowRunModal,
      showRunModal,
      updateRunParam,
    },
  };
}
