"use client";

import { useEffect, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { Button } from "../../ui/button";
import { QrCode } from "../../ui/qr-code";
import {
  ChannelHealthCard,
  ConnectedCard,
  InboundRoutingCard,
  InspectorHead,
  TestSendCard,
} from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

/**
 * WhatsApp QR ("link your own number as a companion device") - core/omnichannel/whatsappqr plus the
 * whatsapp-qr bridge service. There is no credential form: the pairing scan IS the connect flow, so
 * this inspector owns a little state machine SimpleConnectPanel doesn't fit:
 *
 *   (none | DISCONNECTED) --[link]--> PAIRING (poll + render the live QR) --> ACTIVE --> disconnect
 *
 * The QR payload comes from GET /session while pairing; it rotates roughly every 20 s on the
 * bridge, so we poll on a short interval and stop the moment the scan lands.
 */
type QrSession = {
  status: "PAIRING" | "ACTIVE" | "DISCONNECTED";
  bridgeStatus?: string | null;
  qr?: string | null;
  phoneNumber?: string | null;
  configured?: boolean;
};

const POLL_MS = 2500;

function WhatsAppQrInspector({ ctx }: { ctx: ChannelCtx }) {
  const { t, busy, agents, cardsByKey, statusLoaded, setError, setSuccess, setBusy, refreshStatus } = ctx;
  const [session, setSession] = useState<QrSession | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [defaultAgentId, setDefaultAgentIdState] = useState("");
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    api
      .getWhatsAppQrSession()
      .then((s: unknown) => setSession(s as QrSession))
      .catch(() => setSession(null))
      .finally(() => setLoaded(true));
  }, []);

  const savedAgentId: string | undefined = cardsByKey.get("whatsappqr")?.defaultAgentId;
  useEffect(() => {
    if (savedAgentId) setDefaultAgentIdState(savedAgentId);
  }, [savedAgentId]);

  // While pairing is open, poll for the rotating QR and for the scan completing.
  const pairing = session?.status === "PAIRING";
  useEffect(() => {
    if (!pairing) return undefined;
    pollRef.current = setInterval(() => {
      api
        .getWhatsAppQrSession()
        .then((fresh: unknown) => {
          const next = fresh as QrSession;
          setSession(next);
          if (next && next.status === "ACTIVE") {
            refreshStatus();
          }
        })
        .catch(() => {});
    }, POLL_MS);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [pairing, refreshStatus]);

  async function connect() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const fresh = (await api.connectWhatsAppQrSession()) as QrSession;
      setSession(fresh);
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("whatsappQrConnectError")));
    } finally {
      setBusy(false);
    }
  }

  async function disconnect() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.disconnectWhatsAppQrSession();
      setSession({ status: "DISCONNECTED", phoneNumber: session?.phoneNumber });
      setSuccess(t("channelDisconnectedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("channelDisconnectError")));
    } finally {
      setBusy(false);
    }
  }

  async function saveDefaultAgent(e: React.FormEvent) {
    e.preventDefault();
    if (!defaultAgentId) return;
    setBusy(true);
    try {
      await api.setWhatsAppQrDefaultAgent(defaultAgentId);
      setSuccess(t("defaultAgentUpdatedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }

  const active = session?.status === "ACTIVE";
  const disconnected = session?.status === "DISCONNECTED";
  const bridgeDown = pairing && session?.qr == null && session?.bridgeStatus !== "QR_PENDING";

  return (
    <>
      <InspectorHead
        channelKey="whatsappqr"
        title={t("connectWhatsAppQrTitle")}
        subtitle={t("connectWhatsAppQrSubtitle")}
      />

      {loaded && session?.configured === false && (
        <p className="text-xs text-muted-foreground">{t("whatsappQrNoBridgeHint")}</p>
      )}

      {loaded && session?.configured !== false && disconnected && (
        <ConnectedCard
          label={t("disconnectedLabel")}
          value={`${session?.phoneNumber || t("connectedFallback")}${t("disconnectedReconnectHintShort")}`}
        />
      )}

      {loaded && session?.configured !== false && !active && (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void connect();
          }}
        >
          <p className="text-xs text-muted-foreground">{t("whatsappQrSetupHint")}</p>
          <Button type="submit" size="sm" disabled={busy} aria-busy={busy}>
            {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
            {pairing ? t("whatsappQrRegenerateButton") : t("whatsappQrLinkButton")}
          </Button>
        </form>
      )}

      {pairing && (
        <div className="w-full space-y-3 rounded-xl border border-border bg-muted/40 p-4 text-start">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("whatsappQrScanTitle")}
          </div>
          {session?.qr ? (
            <div className="flex justify-center rounded-lg bg-white p-3">
              <QrCode value={session.qr} px={192} label={t("whatsappQrScanTitle")} />
            </div>
          ) : (
            <p className="flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              {bridgeDown ? t("whatsappQrReconnecting") : t("whatsappQrWaiting")}
            </p>
          )}
          <p className="text-xs text-muted-foreground">{t("whatsappQrScanHint")}</p>
        </div>
      )}

      {active && (
        <>
          <div className="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-muted px-3 py-2.5">
            <div className="min-w-0">
              <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
                {t("connectedLabel")}
              </div>
              <div className="truncate font-mono text-xs text-foreground">
                {session?.phoneNumber || t("connectedFallback")}
              </div>
            </div>
            <Button type="button" variant="outline" size="sm" onClick={disconnect} disabled={busy}>
              {t("whatsappQrUnlinkButton")}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">{t("whatsappQrPolicyHint")}</p>
          <TestSendCard
            send={(to, body) => api.sendWhatsAppQrMessage({ to, body })}
            busy={busy}
            setError={setError}
            setSuccess={setSuccess}
            t={t}
            toPlaceholder={t("whatsappQrToPlaceholder")}
          />
          <ChannelHealthCard channelKey="whatsappqr" t={t} />
        </>
      )}

      {loaded && session?.configured !== false && (
        <InboundRoutingCard
          agents={agents}
          value={defaultAgentId}
          savedValue={savedAgentId}
          loaded={statusLoaded}
          onChange={setDefaultAgentIdState}
          onSave={saveDefaultAgent}
          busy={busy}
          t={t}
        />
      )}
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["whatsappqr"], Inspector: WhatsAppQrInspector };
