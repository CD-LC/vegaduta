"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import type { ChannelUi } from "./useSimpleChannel";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * X channel state for ChannelsPanel: the BYOK app registration, the OAuth
 * connect redirect (and its ?x=connected|error return), and disconnect.
 * {@code offered=false} (a personal install) keeps the hook mounted but makes it inert: no credential GET.
 */
export function useXChannel({
  t,
  setError,
  setSuccess,
  setBusy,
  refreshStatus,
  offered = true,
}: ChannelUi & { offered?: boolean }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  // X - two-step BYOK-then-OAuth (see XCredential's javadoc).
  const [x, setX] = useState(null);
  const [xLoaded, setXLoaded] = useState(false);
  const [xClientId, setXClientId] = useState("");
  const [xClientSecret, setXClientSecret] = useState("");
  const [xDefaultAgentId, setXDefaultAgentId] = useState("");
  const refreshX = useCallback(() => {
    // Not offered on this install: settle as "nothing connected" without calling the API.
    const credential: Promise<any> = offered ? api.getXCredential() : Promise.resolve(null);
    return credential
      .then(setX)
      .catch(() => setX(null))
      .finally(() => setXLoaded(true));
  }, [offered]);
  useEffect(() => {
    void refreshX();
  }, [refreshX]);
  useEffect(() => {
    const xParam = searchParams.get("x");
    if (!xParam) return;
    if (xParam === "connected") {
      setSuccess(t("xConnectedSuccess"));
      void refreshX();
      refreshStatus();
    } else if (xParam === "error") {
      setError(t("xConnectError"));
    }
    router.replace("/admin/channels");
  }, [searchParams, router, refreshX, refreshStatus, t, setError, setSuccess]);
  async function handleRegisterXApp(e: any) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!xClientId.trim() || !xClientSecret.trim()) {
      setError(t("xAppRequiredFieldsError"));
      return;
    }
    setBusy(true);
    try {
      await api.registerXApp({ clientId: xClientId, clientSecret: xClientSecret });
      setXClientId("");
      setXClientSecret("");
      setSuccess(t("xAppRegisteredSuccess"));
      await refreshX();
    } catch (err) {
      setError(errorMessage(err, t("xAppRegisterError")));
    } finally {
      setBusy(false);
    }
  }
  async function handleConnectX() {
    setError(null);
    setBusy(true);
    try {
      const { url } = await api.getXAuthorizeUrl();
      window.location.href = url;
    } catch (err) {
      setError(errorMessage(err, t("xConnectStartError")));
      setBusy(false);
    }
  }

  // "Log out of X" - DELETE /api/channels/x/connection wipes the OAuth
  // access token + refresh token; the registered app (clientId/secret)
  // survives, so reconnecting is one click of "Connect X account".
  async function handleDisconnectX() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.disconnectX();
      setSuccess(t("xDisconnectedSuccess"));
      await refreshX();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("xDisconnectError")));
    } finally {
      setBusy(false);
    }
  }

  return {
    x,
    xLoaded,
    xClientId,
    setXClientId,
    xClientSecret,
    setXClientSecret,
    xDefaultAgentId,
    setXDefaultAgentId,
    handleRegisterXApp,
    handleConnectX,
    handleDisconnectX,
  };
}
