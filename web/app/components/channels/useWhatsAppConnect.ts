"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import type { ChannelUi } from "./useSimpleChannel";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * WhatsApp connect state for ChannelsPanel: the Meta Cloud API and Twilio
 * credential forms, their load/refresh, and activate/disconnect handlers.
 */
export function useWhatsAppConnect({ t, setError, setSuccess, setBusy, refreshStatus }: ChannelUi) {
  // ---------------------------------------------------------------------
  // WhatsApp - two official connect methods (Meta Cloud API / Twilio), same
  // method-tab structure as the mockup's right rail.
  // ---------------------------------------------------------------------
  const [waMethod, setWaMethod] = useState("meta");

  const [twilioCredential, setTwilioCredential] = useState(null);
  const [twilioLoaded, setTwilioLoaded] = useState(false);
  const [accountSid, setAccountSid] = useState("");
  const [authToken, setAuthToken] = useState("");
  const [waFromNumber, setWaFromNumber] = useState("");

  const [metaCredential, setMetaCredential] = useState(null);
  const [metaLoaded, setMetaLoaded] = useState(false);
  const [metaPhoneNumberId, setMetaPhoneNumberId] = useState("");
  const [metaWabaId, setMetaWabaId] = useState("");
  const [metaAccessToken, setMetaAccessToken] = useState("");
  const [metaAppSecret, setMetaAppSecret] = useState("");

  // These callbacks return their promise so callers CAN await the refetch
  // before showing "saved" (awaiting them used to be an await-thenable lint
  // error - they returned undefined, so the success toast raced the reload).
  const refreshTwilio = useCallback(() => {
    return api
      .getTwilioCredential()
      .then(setTwilioCredential)
      .catch(() => setTwilioCredential(null))
      .finally(() => setTwilioLoaded(true));
  }, []);
  const refreshMeta = useCallback(() => {
    return api
      .getMetaWhatsAppCredential()
      .then(setMetaCredential)
      .catch(() => setMetaCredential(null))
      .finally(() => setMetaLoaded(true));
  }, []);

  useEffect(() => {
    void refreshTwilio();
    void refreshMeta();
  }, [refreshTwilio, refreshMeta]);

  async function handleActivateTwilio(e: any) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!accountSid.trim() || !authToken.trim() || !waFromNumber.trim()) {
      setError(t("twilioRequiredFieldsError"));
      return;
    }
    setBusy(true);
    try {
      await api.activateTwilioCredential({ accountSid, authToken, whatsAppFromNumber: waFromNumber });
      setAccountSid("");
      setAuthToken("");
      setWaFromNumber("");
      setSuccess(t("twilioConnectedSuccess"));
      await refreshTwilio();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("twilioActivateError")));
    } finally {
      setBusy(false);
    }
  }

  // Disconnects Twilio; WhatsAppDispatchService then uses Meta (if
  // connected) for outbound, and inbound Twilio messages stop being
  // accepted (the credential is no longer ACTIVE).
  async function handleDisconnectTwilio() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.disconnectTwilioCredential();
      setSuccess(t("twilioDisconnectedSuccess"));
      await refreshTwilio();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("twilioDisconnectError")));
    } finally {
      setBusy(false);
    }
  }

  // "Log out of WhatsApp (Meta)" - same DISCONNECTED lifecycle as Twilio;
  // unlinks the phoneNumberId->tenant route so inbound Meta webhooks stop
  // resolving this tenant at all.
  async function handleDisconnectMeta() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.disconnectMetaWhatsAppCredential();
      setSuccess(t("metaDisconnectedSuccess"));
      await refreshMeta();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("metaDisconnectError")));
    } finally {
      setBusy(false);
    }
  }

  async function handleActivateMeta(e: any) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!metaPhoneNumberId.trim() || !metaWabaId.trim() || !metaAccessToken.trim() || !metaAppSecret.trim()) {
      setError(t("metaRequiredFieldsError"));
      return;
    }
    setBusy(true);
    try {
      await api.activateMetaWhatsAppCredential({
        phoneNumberId: metaPhoneNumberId,
        wabaId: metaWabaId,
        accessToken: metaAccessToken,
        appSecret: metaAppSecret,
      });
      setMetaPhoneNumberId("");
      setMetaWabaId("");
      setMetaAccessToken("");
      setMetaAppSecret("");
      setSuccess(t("metaConnectedSuccess"));
      await refreshMeta();
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("metaActivateError")));
    } finally {
      setBusy(false);
    }
  }

  return {
    waMethod,
    setWaMethod,
    handleActivateMeta,
    metaLoaded,
    metaCredential,
    handleDisconnectMeta,
    metaPhoneNumberId,
    setMetaPhoneNumberId,
    metaWabaId,
    setMetaWabaId,
    metaAccessToken,
    setMetaAccessToken,
    metaAppSecret,
    setMetaAppSecret,
    handleActivateTwilio,
    twilioLoaded,
    twilioCredential,
    handleDisconnectTwilio,
    accountSid,
    setAccountSid,
    authToken,
    setAuthToken,
    waFromNumber,
    setWaFromNumber,
  };
}
