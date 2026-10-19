"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { addPickedFiles, readModalities, type PendingAttachment } from "../ChatAttachments";

/* eslint-disable @typescript-eslint/no-explicit-any */
/** ChatPanel's composer attachments: the picked files, their read/upload state and errors. */
export function useComposerAttachments(agent: any, sending: boolean) {
  // Multimodal attachments - null modalities (older core, or a text-only
  // model) means the attach UI doesn't render at all; see ChatAttachments.tsx.
  const modalities = readModalities(agent);
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [attachError, setAttachError] = useState<string | null>(null);
  // True while a picked file is being read / uploaded (a sandbox data file is
  // a network upload that takes seconds). Send, Enter and the paperclip wait
  // for it, so a turn can never leave without the file the person just picked.
  const [attaching, setAttaching] = useState(false);
  // The agent a pick was started for vs the one on screen when it resolves -
  // an upload key belongs to one agent and must not land in another's composer.
  const currentAgentIdRef = useRef<string | null>(agent?.id ?? null);
  useEffect(() => {
    currentAgentIdRef.current = agent?.id ?? null;
  }, [agent?.id]);

  async function handleFilesPicked(files: File[]) {
    if (!modalities || attaching || sending) return;
    setAttachError(null);
    const pickedForAgentId = agent.id;
    setAttaching(true);
    try {
      // Sandbox data files (xlsx; csv/json/pdf/txt when the model reads no
      // documents) are stored via the upload endpoint, the rest stay inline.
      const result = await addPickedFiles(attachments, files, modalities, (file) =>
        api.uploadChatAttachment(pickedForAgentId, file)
      );
      // The person switched agents while this was in flight: drop the result.
      if (currentAgentIdRef.current !== pickedForAgentId) return;
      if ("error" in result) {
        setAttachError(result.error);
      } else {
        setAttachments(result.attachments);
      }
    } catch (err) {
      // FileReader failure (permissions, file vanished mid-read) - same
      // inline-error surface as a validation miss.
      if (currentAgentIdRef.current !== pickedForAgentId) return;
      setAttachError(errorMessage(err, "Could not read that file. Please try again."));
    } finally {
      setAttaching(false);
    }
  }

  function removeAttachment(id: string) {
    setAttachments((list) => list.filter((a) => a.id !== id));
    setAttachError(null);
  }

  return {
    modalities,
    attachments,
    setAttachments,
    attachError,
    setAttachError,
    attaching,
    handleFilesPicked,
    removeAttachment,
  };
}
