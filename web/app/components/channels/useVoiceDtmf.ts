"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import type { ChannelUi } from "./useSimpleChannel";

/* eslint-disable @typescript-eslint/no-explicit-any */
// VoiceDtmfMappingService's per-digit menu entry / this page's own draft
// shape for an in-progress (unsaved) edit to one digit's mapping.
interface DtmfMapping {
  digit: string;
  agentId: string;
  label?: string;
}

interface DtmfDraft {
  agentId: string;
  label: string;
}

const DTMF_DIGITS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"];

/**
 * Voice channel state for ChannelsPanel: the default-agent picker value and
 * the per-digit DTMF menu with its unsaved drafts and save/clear handlers.
 */
export function useVoiceDtmf({ t, setError, setSuccess }: Pick<ChannelUi, "t" | "setError" | "setSuccess">) {
  // Voice (#1074/#1075) - reuses the tenant's existing TwilioCredential, so
  // there's nothing to "connect" here beyond WhatsApp's own Twilio setup;
  // this card only needs a default-agent picker plus the per-digit DTMF
  // menu (VoiceDtmfMappingService / migration V98) that lets a caller press
  // a key mid-call to switch which agent answers for the rest of that call.
  const [voiceDefaultAgentId, setVoiceDefaultAgentId] = useState("");
  const [dtmfMenu, setDtmfMenu] = useState<DtmfMapping[]>([]);
  const [dtmfLoaded, setDtmfLoaded] = useState(false);
  const [dtmfDrafts, setDtmfDrafts] = useState<Record<string, DtmfDraft>>({});
  const [dtmfBusyDigit, setDtmfBusyDigit] = useState(null);
  const refreshDtmfMenu = useCallback(() => {
    return api
      .getVoiceDtmfMenu()
      .then((list) => {
        const menu = list || [];
        setDtmfMenu(menu);
        setDtmfDrafts((prev) => {
          const next = { ...prev };
          menu.forEach((m: DtmfMapping) => {
            next[m.digit] = { agentId: m.agentId, label: m.label || "" };
          });
          return next;
        });
      })
      .catch(() => setDtmfMenu([]))
      .finally(() => setDtmfLoaded(true));
  }, []);
  useEffect(() => {
    void refreshDtmfMenu();
  }, [refreshDtmfMenu]);
  function dtmfDraft(digit: any) {
    return dtmfDrafts[digit] || { agentId: "", label: "" };
  }
  function setDtmfDraftAgent(digit: any, agentId: any) {
    setDtmfDrafts((prev) => ({ ...prev, [digit]: { ...dtmfDraft(digit), agentId } }));
  }
  function setDtmfDraftLabel(digit: any, label: any) {
    setDtmfDrafts((prev) => ({ ...prev, [digit]: { ...dtmfDraft(digit), label } }));
  }
  async function handleSaveDtmfMapping(digit: any) {
    const draft = dtmfDraft(digit);
    if (!draft.agentId) return;
    setError(null);
    setSuccess(null);
    setDtmfBusyDigit(digit);
    try {
      await api.setVoiceDtmfMapping(digit, draft.agentId, draft.label || undefined);
      setSuccess(t("dtmfMappingSavedSuccess", { digit }));
      await refreshDtmfMenu();
    } catch (err) {
      setError(errorMessage(err, t("dtmfMappingSaveError")));
    } finally {
      setDtmfBusyDigit(null);
    }
  }
  async function handleClearDtmfMapping(digit: any) {
    setError(null);
    setSuccess(null);
    setDtmfBusyDigit(digit);
    try {
      await api.deleteVoiceDtmfMapping(digit);
      setDtmfDrafts((prev) => ({ ...prev, [digit]: { agentId: "", label: "" } }));
      setSuccess(t("dtmfMappingClearedSuccess", { digit }));
      await refreshDtmfMenu();
    } catch (err) {
      setError(errorMessage(err, t("dtmfMappingClearError")));
    } finally {
      setDtmfBusyDigit(null);
    }
  }

  return {
    voiceDefaultAgentId,
    setVoiceDefaultAgentId,
    dtmfLoaded,
    DTMF_DIGITS,
    dtmfDraft,
    dtmfMenu,
    handleSaveDtmfMapping,
    setDtmfDraftAgent,
    setDtmfDraftLabel,
    dtmfBusyDigit,
    handleClearDtmfMapping,
  };
}
