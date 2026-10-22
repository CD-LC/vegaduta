"use client";

import { useCallback, useEffect, useState } from "react";
import { errorMessage } from "../../lib/errors";

/** Panel-level UI plumbing every channel hook reports through. */
export interface ChannelUi {
  t: (key: string, values?: Record<string, unknown>) => string;
  setError: (v: string | null) => void;
  setSuccess: (v: string | null) => void;
  setBusy: (v: boolean) => void;
  refreshStatus: () => void;
}

/**
 * A credential getter that is already settled with "nothing connected". The hooks below stay unconditional
 * (rules of hooks); a channel this install does not offer hands them this instead of the API call, so mounting
 * the panel fires no GET /api/channels/<key>/credential for it.
 */
export const noCredential = (): Promise<null> => Promise.resolve(null);

/** {@code getter} when the channel is offered on this install, else {@link noCredential} (a stable reference). */
export function credentialGetter<T extends () => Promise<unknown>>(offered: boolean, getter: T): T | typeof noCredential {
  return offered ? getter : noCredential;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
export function useSimpleChannel({
  getCredential,
  activate,
  setDefaultAgent,
  disconnect,
  t,
  setError,
  setSuccess,
  setBusy,
  refreshStatus,
}: {
  getCredential: any;
  activate: any;
  setDefaultAgent: any;
  // "Log out of this channel": DELETE on the credential endpoint. Marks the row DISCONNECTED server-side
  // (kept, so the card can still show what was connected) and unlinks the external account.
  disconnect?: any;
} & ChannelUi) {
  const [credential, setCredential] = useState(null);
  const [loaded, setLoaded] = useState(false);
  const [defaultAgentId, setDefaultAgentIdState] = useState("");
  const refresh = useCallback(() => {
    return getCredential()
      .then(setCredential)
      .catch(() => setCredential(null))
      .finally(() => setLoaded(true));
  }, [getCredential]);
  useEffect(() => {
    refresh();
  }, [refresh]);
  async function handleActivate(payload: any) {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await activate(payload);
      setSuccess(t("channelConnectedSuccess"));
      await refresh();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("channelConnectError")));
      throw err;
    } finally {
      setBusy(false);
    }
  }
  async function handleSetDefault(e: any) {
    e.preventDefault();
    if (!defaultAgentId) return;
    setBusy(true);
    try {
      await setDefaultAgent(defaultAgentId);
      setSuccess(t("defaultAgentUpdatedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }
  async function handleDisconnect() {
    if (!disconnect) return;
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await disconnect();
      setSuccess(t("channelDisconnectedSuccess"));
      await refresh();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("channelDisconnectError")));
    } finally {
      setBusy(false);
    }
  }
  return {
    credential,
    loaded,
    defaultAgentId,
    setDefaultAgentIdState,
    handleActivate,
    handleSetDefault,
    handleDisconnect,
  };
}
