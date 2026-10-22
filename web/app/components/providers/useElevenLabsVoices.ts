"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { usePagination } from "../../lib/usePagination";

type Translate = (key: string, values?: Record<string, any>) => string;

/** ElevenLabs voice-cloning state and handlers (the follow-up step after the key is ACTIVE). */
export function useElevenLabsVoices(
  setError: (m: string | null) => void,
  setSuccess: (m: string | null) => void,
  t: Translate,
  /** False where core has no ElevenLabs (a personal install): the voice list is not fetched. */
  enabled = true
) {
  const [voices, setVoices] = useState<any[]>([]);
  const [voiceName, setVoiceName] = useState("");
  const [voiceFile, setVoiceFile] = useState<File | null>(null);
  const [cloneBusy, setCloneBusy] = useState(false);
  const [deletingVoiceId, setDeletingVoiceId] = useState<string | null>(null);
  // Deleting a cloned voice is destructive - same two-step confirm as the provider delete button.
  const [confirmDeleteVoiceId, setConfirmDeleteVoiceId] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const pagination = usePagination(voices);

  const refreshVoices = () =>
    !enabled
      ? Promise.resolve()
      : api
          .listElevenLabsVoices()
          .then(setVoices)
          .catch(() => setVoices([]));

  useEffect(() => {
    void refreshVoices();
  }, []);

  async function handleCloneVoice(e: FormEvent) {
    e.preventDefault();
    if (!voiceName.trim() || !voiceFile) return;
    setCloneBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.cloneElevenLabsVoice(voiceName.trim(), voiceFile);
      await refreshVoices();
      setVoiceName("");
      setVoiceFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
      setSuccess(t("elevenLabsVoiceClonedSuccess"));
    } catch (err) {
      setError(errorMessage(err, t("elevenLabsVoiceCloneFailedError")));
    } finally {
      setCloneBusy(false);
    }
  }

  async function handleDeleteVoice(id: string) {
    setDeletingVoiceId(id);
    setError(null);
    setSuccess(null);
    try {
      await api.deleteElevenLabsVoice(id);
      await refreshVoices();
    } catch (err) {
      setError(errorMessage(err, t("elevenLabsVoiceDeleteFailedError")));
    } finally {
      setDeletingVoiceId(null);
      setConfirmDeleteVoiceId(null);
    }
  }

  // First click arms the confirm state on that voice's row; a second click on the same button deletes it.
  function handleDeleteVoiceClick(id: string) {
    if (confirmDeleteVoiceId === id) {
      void handleDeleteVoice(id);
    } else {
      setConfirmDeleteVoiceId(id);
    }
  }

  return {
    voices,
    voiceName,
    setVoiceName,
    voiceFile,
    setVoiceFile,
    cloneBusy,
    deletingVoiceId,
    confirmDeleteVoiceId,
    fileInputRef,
    pagination,
    handleCloneVoice,
    handleDeleteVoiceClick,
  };
}

export type ElevenLabsVoices = ReturnType<typeof useElevenLabsVoices>;
