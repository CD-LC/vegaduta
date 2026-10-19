"use client";

import { useCallback, useRef, useState } from "react";
import { assistantApi, type AssistantWorkspace } from "../../lib/api/assistant";
import { errorMessage } from "../../lib/errors";
import { effectiveMimeType, formatFileSize, type PendingAttachment } from "../ChatAttachments";
import type { useComposerAttachments } from "../chat/useComposerAttachments";

/** Mirrors WorkspaceFolderController.MAX_UPLOAD_BYTES / MAX_UPLOAD_FILES. */
export const MAX_ASSISTANT_FILE_BYTES = 25 * 1024 * 1024;
export const MAX_ASSISTANT_FILES = 10;

let seq = 0;

function readDataUrl(file: File): Promise<string | null> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(file);
  });
}

/**
 * Attaching in the Assistant's box. Where the install has a projects folder every file is saved there
 * (so the Assistant can search and read it, whatever the model can take natively); an image also rides
 * inline when the model can see, so it is looked at as well as findable. Without a projects folder this
 * is the console's ordinary attach (inline / sandbox upload), unchanged.
 */
export function useAssistantFiles(
  attach: ReturnType<typeof useComposerAttachments>,
  workspace: AssistantWorkspace | null,
  folder: string | null
) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef<PendingAttachment[]>(attach.attachments);
  latest.current = attach.attachments;
  const searchable = !!workspace?.available;

  const pick = useCallback(
    async (picked: File[]) => {
      if (picked.length === 0) return;
      if (!searchable) {
        await attach.handleFilesPicked(picked);
        return;
      }
      if (busy) return;
      setError(null);
      setBusy(true);
      try {
        const list = latest.current;
        if (list.length + picked.length > MAX_ASSISTANT_FILES) {
          setError(`You can attach up to ${MAX_ASSISTANT_FILES} files per message.`);
          return;
        }
        const tooBig = picked.find((f) => f.size > MAX_ASSISTANT_FILE_BYTES);
        if (tooBig) {
          setError(`"${tooBig.name}" is ${formatFileSize(tooBig.size)} - the limit is ${formatFileSize(MAX_ASSISTANT_FILE_BYTES)} per file.`);
          return;
        }
        const saved = await assistantApi.uploadWorkspaceFiles(picked, folder);
        const caps = attach.modalities;
        let inlineCount = list.filter((a) => a.dataBase64).length;
        let inlineBytes = list.reduce((sum, a) => sum + (a.dataBase64 ? a.sizeBytes : 0), 0);
        const made: PendingAttachment[] = [];
        for (let i = 0; i < picked.length; i++) {
          const file = picked[i];
          const where = saved.files[i];
          if (!where) continue;
          const mimeType = effectiveMimeType(file) || "application/octet-stream";
          const isImage = mimeType.startsWith("image/");
          const dataUrl = isImage ? await readDataUrl(file) : null;
          // A vision model is shown the picture itself, within its own small caps; the saved copy stays findable.
          const inline =
            !!dataUrl &&
            !!caps?.image &&
            inlineCount < caps.maxAttachments &&
            inlineBytes + file.size <= caps.maxAttachmentBytes;
          if (inline) {
            inlineCount++;
            inlineBytes += file.size;
          }
          made.push({
            id: `att-${Date.now()}-${seq++}`,
            fileName: file.name,
            mimeType,
            sizeBytes: file.size,
            dataBase64: inline && dataUrl ? dataUrl.slice(dataUrl.indexOf(",") + 1) : "",
            previewUrl: dataUrl,
            workspacePath: where.path,
            workspaceTextPath: where.textPath,
          });
        }
        attach.setAttachments([...list, ...made]);
      } catch (err) {
        setError(errorMessage(err, "Could not attach that file. Please try again."));
      } finally {
        setBusy(false);
      }
    },
    [searchable, busy, attach, folder]
  );

  return {
    searchable,
    items: attach.attachments,
    attaching: busy || attach.attaching,
    error: error ?? attach.attachError,
    pick,
    remove: (id: string) => {
      setError(null);
      attach.removeAttachment(id);
    },
  };
}
