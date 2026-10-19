"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import {
  ATTACH_ACCEPT_BY_CAPABILITY,
  type AttachCapabilities,
  type AttachCapabilityKey,
  type ChatAttachment,
  type PlaygroundAgent,
} from "./types";

const NO_CAPABILITIES: AttachCapabilities = {
  supportsImageInput: false,
  supportsAudioInput: false,
  supportsVideoInput: false,
  supportsDocumentInput: false,
  supportsSandboxInput: false,
};

/**
 * Stage 4 (chat file upload): which kinds of file this agent's CURRENT model
 * accepts (from the provider-model catalog entry, not a static per-provider
 * assumption), the uploaded attachment waiting to ride the next turn, and the
 * upload action.
 */
export function useAttachments(agent: PlaygroundAgent | null) {
  const [capabilities, setCapabilities] = useState<AttachCapabilities>(NO_CAPABILITIES);
  // True only while the provider-model catalog lookup is in flight - the
  // camera panel shows a one-line "checking" hint instead of vanishing then
  // popping in for vision-capable models.
  const [capsLoading, setCapsLoading] = useState(false);
  const [attachment, setAttachment] = useState<ChatAttachment | null>(null);
  const [attaching, setAttaching] = useState(false);
  const [attachError, setAttachError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const agentHandlesDocuments = !!agent?.modalities?.document;
  useEffect(() => {
    let cancelled = false;
    if (!agent?.providerName || !agent?.model) {
      setCapabilities(NO_CAPABILITIES);
      setCapsLoading(false);
      return undefined;
    }
    setCapsLoading(true);
    api
      .listProviderModels(agent.providerName)
      .then((models) => {
        if (cancelled) return;
        const entry = (models || []).find((m) => m.modelId === agent.model);
        setCapabilities({
          supportsImageInput: !!entry?.supportsImageInput,
          supportsAudioInput: !!entry?.supportsAudioInput,
          supportsVideoInput: !!entry?.supportsVideoInput,
          // The provider-model catalog entry has no document flag; document
          // (PDF-native) support comes from the agent's own modalities view
          // (backend ModelModalitiesService), the same source the inline
          // /playground path reads.
          supportsDocumentInput: agentHandlesDocuments,
          supportsSandboxInput: !!agent.modalities?.sandboxData,
        });
        setCapsLoading(false);
      })
      .catch(() => {
        // Best-effort: no attach button offered rather than blocking the playground.
        if (!cancelled) {
          setCapabilities(NO_CAPABILITIES);
          setCapsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent?.providerName, agent?.model, agentHandlesDocuments]);

  const canAttach =
    capabilities.supportsImageInput ||
    capabilities.supportsAudioInput ||
    capabilities.supportsVideoInput ||
    capabilities.supportsDocumentInput ||
    capabilities.supportsSandboxInput;
  // Object.entries deliberately widens keys to string in TS's lib types - safe
  // to narrow back because ATTACH_ACCEPT_BY_CAPABILITY is a const literal
  // whose exact keys AttachCapabilityKey is derived from.
  const acceptTypes = (Object.entries(ATTACH_ACCEPT_BY_CAPABILITY) as [AttachCapabilityKey, string][])
    .filter(([key]) => capabilities[key])
    .map(([, accept]) => accept)
    .join(",");

  async function handleAttachFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file later
    if (!file || !agent) return;
    setAttachError(null);
    setAttaching(true);
    try {
      const response = await api.uploadChatAttachment(agent.id, file);
      setAttachment(response);
    } catch (err) {
      setAttachError(errorMessage(err));
    } finally {
      setAttaching(false);
    }
  }

  function removeAttachment() {
    setAttachment(null);
    setAttachError(null);
  }

  return {
    capabilities,
    capsLoading,
    attachment,
    setAttachment,
    attaching,
    attachError,
    setAttachError,
    fileInputRef,
    canAttach,
    acceptTypes,
    handleAttachFile,
    removeAttachment,
  };
}
