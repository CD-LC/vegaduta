"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Check, Copy, Eye, EyeOff, Loader2, TriangleAlert } from "lucide-react";
import { api } from "../../../lib/api";
import { API_BASE } from "../../../lib/api/core";
import { errorMessage } from "../../../lib/errors";
import { useTranslations } from "../../../lib/i18n";
import Select from "../../Select";
import { Button } from "../../ui/button";
import { Checkbox } from "../../ui/checkbox";
import { Label } from "../../ui/label";
import { Textarea } from "../../ui/textarea";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import { useSimpleChannel } from "../useSimpleChannel";
import type { ChannelAdapter, ChannelCtx } from "./types";

// iMessage through the operator's own BlueBubbles server (core/omnichannel-social imessage, V610). Apple has no
// API for iMessage: this needs a Mac that stays awake and signed in to Messages, so the inspector says so up front.
// Connect (server address + password, proven server-side before anything is stored), the webhook URL to paste
// into BlueBubbles, and who may message the agent (allow-list, groups, unknown-sender policy, inbound mode).

interface ImessageCredential {
  status: string;
  serverUrl: string;
  inboundMode: "WEBHOOK" | "POLLING";
  allowedSenders: string[];
  allowGroups: boolean;
  unknownSenderPolicy: "PAIR" | "IGNORE";
  webhookPath: string;
  webhookToken: string;
}

function publicBase(): string {
  return API_BASE ? API_BASE.replace(/\/+$/, "") : typeof window !== "undefined" ? window.location.origin : "";
}

export function ImessageInspector({ ctx }: { ctx: ChannelCtx }) {
  const { t, busy, setBusy, agents, cardsByKey, statusLoaded, setError, setSuccess, refreshStatus } = ctx;
  const ti = useTranslations("channelsImessage");
  const imessage = useSimpleChannel({
    getCredential: api.getImessageCredential,
    activate: api.activateImessageCredential,
    setDefaultAgent: api.setImessageDefaultAgent,
    disconnect: api.disconnectImessageCredential,
    t,
    setError,
    setSuccess,
    setBusy,
    refreshStatus,
  });
  const credential = imessage.credential as ImessageCredential | null;
  const savedAgent = cardsByKey.get("imessage")?.defaultAgentId;
  const { setDefaultAgentIdState } = imessage;
  useEffect(() => {
    if (savedAgent) setDefaultAgentIdState(savedAgent);
  }, [savedAgent, setDefaultAgentIdState]);

  const [serverUrl, setServerUrl] = useState("");
  const [password, setPassword] = useState("");
  const [connectMode, setConnectMode] = useState("WEBHOOK");

  const active = !!credential && credential.status === "ACTIVE";

  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="imessage"
        title={ti("title")}
        subtitle={ti("subtitle")}
        credential={credential}
        loaded={imessage.loaded}
        connectedLabel={credential?.serverUrl ?? ""}
        busy={busy}
        agents={agents}
        defaultAgentId={imessage.defaultAgentId}
        savedDefaultAgentId={savedAgent}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={setDefaultAgentIdState}
        onSaveDefaultAgent={imessage.handleSetDefault}
        testSend={
          active && (
            <TestSendCard
              send={(to, body) => api.sendImessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
              toPlaceholder={ti("testSendToPlaceholder")}
            />
          )
        }
        onDisconnect={imessage.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!serverUrl.trim() || !password) {
            setError(ti("requiredFieldsError"));
            return;
          }
          imessage
            .handleActivate({ serverUrl: serverUrl.trim(), password, inboundMode: connectMode })
            .then(() => {
              setServerUrl("");
              setPassword("");
            })
            .catch(() => {});
        }}
      >
        <div
          role="note"
          className="flex gap-2.5 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-xs text-foreground"
        >
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-amber-600" aria-hidden="true" />
          <div className="space-y-1">
            <div className="font-semibold">{ti("honestyTitle")}</div>
            <p className="m-0 text-muted-foreground">{ti("honestyBody")}</p>
          </div>
        </div>
        <ol className="m-0 list-decimal space-y-1 ps-5 text-xs text-muted-foreground">
          <li>{ti("step1")}</li>
          <li>{ti("step2")}</li>
          <li>{ti("step3")}</li>
        </ol>
        <LabeledInput
          id="imessage-server-url"
          label={ti("serverUrlLabel")}
          placeholder={ti("serverUrlPlaceholder")}
          value={serverUrl}
          onChange={(e) => setServerUrl(e.target.value)}
        />
        <LabeledInput
          id="imessage-server-password"
          type="password"
          autoComplete="off"
          label={ti("serverPasswordLabel")}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <div className="space-y-1.5">
          <Label htmlFor="imessage-inbound-mode" className="text-xs text-muted-foreground">
            {ti("inboundModeLabel")}
          </Label>
          <Select
            id="imessage-inbound-mode"
            className="select-inline"
            value={connectMode}
            onChange={(e) => setConnectMode(e.target.value)}
            options={[
              { value: "WEBHOOK", label: ti("modeWebhook") },
              { value: "POLLING", label: ti("modePolling") },
            ]}
          />
        </div>
        <p className="m-0 text-xs text-muted-foreground">{ti("privateHostHint")}</p>
      </SimpleConnectPanel>
      {active && credential && (
        <>
          <WebhookCard credential={credential} />
          <AccessCard
            key={credential.webhookPath}
            credential={credential}
            busy={busy}
            setBusy={setBusy}
            setError={setError}
            setSuccess={setSuccess}
            refresh={refreshStatus}
          />
        </>
      )}
    </>
  );
}

function WebhookCard({ credential }: { credential: ImessageCredential }) {
  const ti = useTranslations("channelsImessage");
  const [reveal, setReveal] = useState(false);
  const [copied, setCopied] = useState(false);
  // The credential read carries a masked token ("••••xxxx"); only the activate response and the
  // explicit reveal call return the full one, so lazily fetch it the first time it's needed.
  const [revealedToken, setRevealedToken] = useState<string | null>(
    credential.webhookToken.startsWith("••••") ? null : credential.webhookToken
  );
  const base = `${publicBase()}${credential.webhookPath}`;
  const shown = `${base}?token=${reveal && revealedToken ? revealedToken : "••••••••"}`;
  async function ensureToken(): Promise<string | null> {
    if (revealedToken) return revealedToken;
    try {
      const r = await api.revealImessageWebhookToken();
      const token = typeof r?.webhookToken === "string" ? r.webhookToken : null;
      if (token) setRevealedToken(token);
      return token;
    } catch {
      return null;
    }
  }
  async function toggleReveal() {
    if (!reveal && !(await ensureToken())) return;
    setReveal((v) => !v);
  }
  async function copy() {
    const token = await ensureToken();
    if (!token) return;
    navigator.clipboard?.writeText(`${base}?token=${token}`).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      (): void => {
        // clipboard blocked: the URL is selectable in the page, nothing else to do
      }
    );
  }
  return (
    <div className="space-y-2 rounded-xl border border-border p-4" data-testid="imessage-webhook">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{ti("webhookTitle")}</div>
      {credential.inboundMode === "POLLING" ? (
        <p className="m-0 text-xs text-muted-foreground">{ti("pollingNote")}</p>
      ) : (
        <>
          <div className="flex items-start gap-2">
            <code className="block min-w-0 flex-1 break-all rounded-lg border border-border bg-muted/40 px-3 py-2 font-mono text-xs">
              {shown}
            </code>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={toggleReveal}
              aria-label={reveal ? ti("hideSecret") : ti("showSecret")}
            >
              {reveal ? <EyeOff size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={copy} aria-label={ti("copyWebhook")}>
              {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            </Button>
          </div>
          <p className="m-0 text-xs text-muted-foreground">{ti("webhookHint")}</p>
        </>
      )}
    </div>
  );
}

function AccessCard({
  credential,
  busy,
  setBusy,
  setError,
  setSuccess,
  refresh,
}: {
  credential: ImessageCredential;
  busy: boolean;
  setBusy: (v: boolean) => void;
  setError: (v: string | null) => void;
  setSuccess: (v: string | null) => void;
  refresh: () => void;
}) {
  const ti = useTranslations("channelsImessage");
  const [allowed, setAllowed] = useState(credential.allowedSenders.join("\n"));
  const [groups, setGroups] = useState(credential.allowGroups);
  const [policy, setPolicy] = useState<string>(credential.unknownSenderPolicy);
  const [mode, setMode] = useState<string>(credential.inboundMode);

  async function save(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.updateImessageSettings({
        inboundMode: mode,
        allowedSenders: allowed
          .split(/[\n,;]+/)
          .map((s) => s.trim())
          .filter(Boolean),
        allowGroups: groups,
        unknownSenderPolicy: policy,
      });
      setSuccess(ti("accessSaved"));
      refresh();
    } catch (err) {
      setError(errorMessage(err, ti("accessSaveError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="space-y-3 rounded-xl border border-border p-4" onSubmit={save} data-testid="imessage-access">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{ti("accessTitle")}</div>
      <div className="space-y-1.5">
        <Label htmlFor="imessage-allowed" className="text-xs text-muted-foreground">
          {ti("allowedLabel")}
        </Label>
        <Textarea
          id="imessage-allowed"
          rows={4}
          value={allowed}
          placeholder={ti("allowedPlaceholder")}
          onChange={(e) => setAllowed(e.target.value)}
        />
        <p className="m-0 text-xs text-muted-foreground">{ti("allowedHint")}</p>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="imessage-policy" className="text-xs text-muted-foreground">
          {ti("policyLabel")}
        </Label>
        <Select
          id="imessage-policy"
          className="select-inline"
          value={policy}
          onChange={(e) => setPolicy(e.target.value)}
          options={[
            { value: "PAIR", label: ti("policyPair") },
            { value: "IGNORE", label: ti("policyIgnore") },
          ]}
        />
      </div>
      <div className="flex items-center gap-2">
        <Checkbox
          id="imessage-groups"
          checked={groups}
          onCheckedChange={setGroups}
          aria-label={ti("allowGroupsLabel")}
        />
        <Label htmlFor="imessage-groups" className="text-xs text-foreground">
          {ti("allowGroupsLabel")}
        </Label>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="imessage-mode" className="text-xs text-muted-foreground">
          {ti("inboundModeLabel")}
        </Label>
        <Select
          id="imessage-mode"
          className="select-inline"
          value={mode}
          onChange={(e) => setMode(e.target.value)}
          options={[
            { value: "WEBHOOK", label: ti("modeWebhook") },
            { value: "POLLING", label: ti("modePolling") },
          ]}
        />
      </div>
      <Button type="submit" size="sm" disabled={busy} aria-busy={busy}>
        {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
        {ti("saveAccess")}
      </Button>
    </form>
  );
}

export const adapter: ChannelAdapter = { keys: ["imessage"], Inspector: ImessageInspector };
