"use client";

import { useState, type ComponentProps } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Badge } from "../ui/badge";
import { StatusDot as SharedStatusDot } from "../ui/status-dot";
import { CHANNEL_BRAND, CHANNEL_ICON } from "../BrandIcons";
import { channelOffered } from "../../lib/edition";

// The channel metadata table and the per-channel inspector building blocks
// (brand tile header, status dot, labelled input, connected card, test-send,
// test-connection, inbound-routing and the shared connect-form shell) that
// ChannelsPanel.tsx composes into its nine channel inspectors. Split out of
// ChannelsPanel.tsx so that file stays under the file-size ratchet; nothing
// here holds page state - every card is driven by the props the panel passes.
// textColor - added in the follow-up full-site audit: the icon/badge
// initials used to always render in a single hardcoded near-black
// (globals.css's .ax-ch-card-icon/.ax-ch-inbound-badge), which was
// effectively invisible against the darker brand colors (X ~1.2:1,
// Slack ~1.9:1). Each brand color below gets whichever of white/near-
// black actually has real contrast against IT specifically (computed via
// WCAG relative luminance), same idea as CHANNEL_META already picking a
// real per-channel background instead of one shared color. The colour
// values themselves live in BrandIcons.tsx (CHANNEL_BRAND) next to the
// brand marks - they are the channels' own identity colours, not theme
// tokens, which is the one place a fixed hex is correct (A13).
// Record<string, ...> (not the narrower literal-key type object-literal
// inference would give) - like CHANNEL_ICON (BrandIcons.tsx) right next to
// it, this gets indexed by arbitrary channel-key strings throughout this
// file (card keys, entry.channel off the wire, route params), not just the
// ten literal keys below.
export const CHANNEL_META: Record<string, { labelKey: string; initials: string; color: string; textColor: string }> = {
  whatsapp: { labelKey: "channelLabelWhatsapp", initials: "WA", ...brandColors("whatsapp") },
  whatsappqr: { labelKey: "channelLabelWhatsappQr", initials: "QR", ...brandColors("whatsappqr") },
  telegram: { labelKey: "channelLabelTelegram", initials: "TG", ...brandColors("telegram") },
  matrix: { labelKey: "channelLabelMatrix", initials: "MX", ...brandColors("matrix") },
  instagram: { labelKey: "channelLabelInstagram", initials: "IG", ...brandColors("instagram") },
  messenger: { labelKey: "channelLabelMessenger", initials: "MS", ...brandColors("messenger") },
  slack: { labelKey: "channelLabelSlack", initials: "SL", ...brandColors("slack") },
  discord: { labelKey: "channelLabelDiscord", initials: "DC", ...brandColors("discord") },
  x: { labelKey: "channelLabelX", initials: "X", ...brandColors("x") },
  teams: { labelKey: "channelLabelTeams", initials: "TM", ...brandColors("teams") },
  imessage: { labelKey: "channelLabelImessage", initials: "iM", ...brandColors("imessage") },
  email: { labelKey: "channelLabelEmail", initials: "@", ...brandColors("email") },
  voice: { labelKey: "channelLabelVoice", initials: "VC", ...brandColors("voice") },
  signal: { labelKey: "channelLabelSignal", initials: "SG", ...brandColors("signal") },
  "mobile-sdk": { labelKey: "channelLabelMobileSdk", initials: "SDK", ...brandColors("mobile-sdk") },
};

function brandColors(key: string): { color: string; textColor: string } {
  const brand = CHANNEL_BRAND[key];
  return { color: brand.color, textColor: brand.ink };
}
export const CHANNEL_ORDER = [
  "whatsapp",
  "whatsappqr",
  "telegram",
  "matrix",
  "instagram",
  "messenger",
  "slack",
  "discord",
  "x",
  "teams",
  "imessage",
  "email",
  "voice",
  "signal",
  "mobile-sdk",
];

/**
 * CHANNEL_ORDER minus the channels this install does not run (Slack, Discord, Teams and X on a personal
 * install; see channelOffered in lib/edition.ts). CHANNEL_ORDER and CHANNEL_META stay complete - the adapter
 * registry needs an adapter per key and a saved reference to a channel must still resolve - so the hiding
 * happens here, at render. Evaluated per call, so it follows the edition the page was served with.
 */
export function offeredChannelOrder(): string[] {
  return CHANNEL_ORDER.filter(channelOffered);
}

// Minimal shape this file actually reads off the agent list
// (GET /api/agents has no exported TS type yet - see api.ts).
export interface AgentOption {
  id: string;
  name: string;
}

// The colored brand tile + heading row that opens every inspector. Renders the
// title as a real <h3> (role=heading) so it's addressable/accessible, and pulls
// the brand mark + color straight from CHANNEL_META/CHANNEL_ICON.
export function InspectorHead({ channelKey, title, subtitle }: { channelKey: string; title: any; subtitle: any }) {
  const meta = CHANNEL_META[channelKey];
  const Icon = CHANNEL_ICON[channelKey];
  return (
    <div className="flex items-center gap-3 border-b border-border pb-4">
      <span
        className="flex size-11 shrink-0 items-center justify-center rounded-xl shadow-sm ring-1 ring-black/5"
        style={{ background: meta?.color, color: meta?.textColor }}
      >
        {Icon ? <Icon className="size-5" /> : null}
      </span>
      <div className="min-w-0">
        <h3 className="text-base font-semibold tracking-tight text-foreground">{title}</h3>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
    </div>
  );
}

// Per-connection-state status indicator on each channel card. Was raw,
// un-tokenized Tailwind palette classes (bg-emerald-500/bg-amber-500/
// bg-rose-500) - now goes through the shared StatusDot (app/components/ui/
// status-dot.tsx) so this follows the app's real --ax-green/--ax-amber/
// --ax-red/--ax-dim design tokens like every other status dot in the app,
// same size/glow/pulse behavior as before.
export function StatusDot({ state }: { state: string }) {
  const status =
    state === "CONNECTED" ? "up" : state === "PENDING" ? "pending" : state === "NEEDS_ATTENTION" ? "down" : "unknown";
  const pulse = state === "PENDING" || state === "NEEDS_ATTENTION";
  return <SharedStatusDot status={status} size={8} glow={status === "unknown" ? false : 8} pulse={pulse} />;
}

// A labelled input where the <Label htmlFor> and <Input id> are wired together,
// so the field is reachable via its accessible name (getByLabel) and screen
// readers. `help` is an optional trailing element (e.g. a CredentialHelp popover).
export function LabeledInput({
  id,
  label,
  help,
  ...inputProps
}: { id: string; label: any; help?: any } & ComponentProps<typeof Input>) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id} className="text-xs text-muted-foreground">
        {label}
        {help}
      </Label>
      <Input id={id} {...inputProps} />
    </div>
  );
}

// A compact read-only "currently connected as …" card shown above a connect form.
export function ConnectedCard({ label, value }: { label: any; value: any }) {
  return (
    <div className="rounded-lg border border-border bg-muted px-3 py-2.5">
      <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="truncate font-mono text-xs text-foreground">{value}</div>
    </div>
  );
}

// Test-send card (2026-09-23) - the one channel action the rebuild never
// wired: every message channel's SendController exists and api.ts wraps it,
// but the inspector offered no way to actually send anything, so "connected"
// was the only thing a tenant could ever verify. One shared card per channel
// inspector (gated on the credential at the call site - a disconnected
// channel can't offer a send that would 4xx). Every /send returns
// SendResult{approved, pendingRequestId} through the same approval gate an
// agent reply does, so approved=false is reported as "queued in Approvals",
// not as a failure and not as "sent". WhatsApp's body field is `message`
// (sendWhatsApp wraps that), email adds `subject`, the rest take {to, body}.
export function TestSendCard({
  send,
  busy,
  setError,
  setSuccess,
  t,
  toPlaceholder,
  withSubject,
}: {
  send: (to: string, body: string, subject?: string) => Promise<any>;
  busy: boolean;
  setError: (m: any) => void;
  setSuccess: (m: any) => void;
  t: any;
  toPlaceholder?: string;
  withSubject?: boolean;
}) {
  const [to, setTo] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [sending, setSending] = useState(false);
  async function submit(e: any) {
    e.preventDefault();
    if (!to.trim() || !body.trim() || (withSubject && !subject.trim())) return;
    setError(null);
    setSuccess(null);
    setSending(true);
    try {
      const res = await send(to.trim(), body.trim(), subject.trim() || undefined);
      setBody("");
      setSuccess(res && res.approved === false ? t("testSendQueuedNotice") : t("testSendSentSuccess"));
    } catch (err: any) {
      setError(err.message || t("testSendError"));
    } finally {
      setSending(false);
    }
  }
  return (
    <form className="w-full space-y-2 rounded-xl border border-border bg-muted/40 p-4 text-start" onSubmit={submit}>
      <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("testSendTitle")}</div>
      <div className="flex items-center gap-2">
        <Input
          className="h-8 flex-1 text-xs"
          aria-label={t("testSendToLabel")}
          placeholder={toPlaceholder || t("testSendToIdPlaceholder")}
          value={to}
          onChange={(e) => setTo(e.target.value)}
        />
        {withSubject && (
          <Input
            className="h-8 w-[130px] text-xs"
            aria-label={t("testSendSubjectLabel")}
            placeholder={t("testSendSubjectLabel")}
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
          />
        )}
      </div>
      <div className="flex items-center gap-2">
        <Input
          className="h-8 flex-1 text-xs"
          aria-label={t("testSendBodyLabel")}
          placeholder={t("testSendBodyLabel")}
          value={body}
          onChange={(e) => setBody(e.target.value)}
        />
        <Button
          type="submit"
          variant="outline"
          size="sm"
          disabled={busy || sending || !to.trim() || !body.trim() || (withSubject && !subject.trim())}
        >
          {t("testSendButton")}
        </Button>
      </div>
      <p className="text-[11px] text-muted-foreground">{t("testSendHint")}</p>
    </form>
  );
}

// "Test connection" card - POSTs ChannelHealthController's per-channel
// probe, which replays the channel's own activation-time validation against
// the stored (decrypted server-side) credential: Slack auth.test, Telegram
// getMe, Discord /users/@me, Meta WhatsApp phone-number lookup, Meta Page
// lookup, Teams Bot Framework token mint, Email IMAP login, Twilio account
// fetch, X OAuth health. Answers the question a
// green CONNECTED badge can't: "is the credential actually live right
// now?" Result is shown inline so a silently-expired token surfaces where
// the tenant manages the channel, not only as failed sends.
export function ChannelHealthCard({ channelKey, t }: { channelKey: any; t: any }) {
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState(null);
  async function run() {
    setChecking(true);
    setResult(null);
    try {
      setResult(await api.testChannelConnection(channelKey));
    } catch (err: any) {
      setResult({ ok: false, detail: err.message || t("testConnectionFailed") });
    } finally {
      setChecking(false);
    }
  }
  return (
    <div className="w-full rounded-xl border border-border bg-muted/40 p-4 text-start">
      <div className="flex items-center justify-between gap-2">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {t("testConnectionTitle")}
        </div>
        <Button type="button" variant="outline" size="sm" onClick={run} disabled={checking}>
          {checking ? t("testConnectionChecking") : t("testConnectionButton")}
        </Button>
      </div>
      {result &&
        (result.ok ? (
          <p className="mt-2 font-mono text-xs text-[var(--success-text)]">
            {t("testConnectionOk", { identity: result.identity || "OK", ms: result.latencyMs ?? "-" })}
          </p>
        ) : (
          <p className="mt-2 font-mono text-xs text-destructive">
            {t("testConnectionFailedWith", { detail: result.detail || t("testConnectionFailed") })}
          </p>
        ))}
    </div>
  );
}

// Inbound routing card - ONE standard block rendered in the same position
// (bottom of the inspector) for every inbound-capable channel, whether or not
// the channel is connected yet: ChannelSessionService.setDefaultAgent is a
// plain per-channel upsert, so routing can be configured before connecting
// and takes effect the moment the channel comes up. Replaces the old RouteBox,
// a bare "ROUTE INBOUND TO" select buried inside per-channel connect states -
// X hid it until OAuth completed.
// The header badge mirrors the SAVED value (never the in-progress select), so
// "what's in effect right now" is unambiguous, and Save stays disabled until
// the pick actually differs from what's stored. data-testid="route-box" and
// the "Save" button are kept deliberately - e2e spec 08 drives both.
export function InboundRoutingCard({
  agents,
  value,
  savedValue,
  loaded,
  onChange,
  onSave,
  busy,
  t,
}: {
  agents: AgentOption[];
  value: any;
  savedValue?: string;
  loaded: boolean;
  onChange: any;
  onSave: (e: React.FormEvent) => void;
  busy: any;
  t: any;
}) {
  const savedAgent = savedValue ? (agents || []).find((a) => a.id === savedValue) : undefined;
  const dirty = (value || "") !== (savedValue || "");
  return (
    <div className="w-full rounded-xl border border-border bg-muted/40 p-4 text-start" data-testid="route-box">
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {t("inboundRoutingTitle")}
        </div>
        {loaded &&
          (savedAgent ? (
            <Badge variant="secondary" className="max-w-[60%] truncate font-mono text-[10px]">
              {t("inboundRoutingRoutedTo", { agent: savedAgent.name })}
            </Badge>
          ) : (
            <Badge
              variant="outline"
              className="border-[var(--warning-border)] bg-[var(--warning-bg)] font-mono text-[10px] text-[var(--warning-text)]"
            >
              {t("inboundRoutingNotConfigured")}
            </Badge>
          ))}
      </div>
      <form className="space-y-2" onSubmit={onSave}>
        <Label htmlFor="route-inbound-agent" className="text-xs text-muted-foreground">
          {t("routeInboundToLabel")}
        </Label>
        <div className="flex items-center gap-2">
          <Select
            id="route-inbound-agent"
            className="select-inline"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder={t("chooseAgentPlaceholder")}
            options={[
              { value: "", label: t("chooseAgentPlaceholder") },
              ...(agents || []).map((a) => ({ value: a.id, label: a.name })),
            ]}
          />
          <Button type="submit" variant="outline" size="sm" disabled={busy || !value || !dirty}>
            {t("saveButton")}
          </Button>
        </div>
      </form>
      <p className="mt-2 text-xs text-muted-foreground">
        {loaded && !savedValue && <span className="text-[var(--warning-text)]">{t("inboundRoutingMissingHint")} </span>}
        {t("pairingApprovalHint")}{" "}
        <Link href="/admin/contacts" className="underline underline-offset-2">
          {t("pairingApprovalLink")}
        </Link>
      </p>
    </div>
  );
}

export function SimpleConnectPanel({
  channelKey,
  title,
  subtitle,
  credential,
  loaded,
  connectedLabel,
  busy,
  agents,
  defaultAgentId,
  savedDefaultAgentId,
  statusLoaded,
  onDefaultAgentChange,
  onSaveDefaultAgent,
  onSubmit,
  testSend,
  onDisconnect,
  children,
  t,
}: {
  channelKey: string;
  title: any;
  subtitle: any;
  credential: any;
  loaded: any;
  connectedLabel: any;
  busy: any;
  agents: AgentOption[];
  defaultAgentId: any;
  savedDefaultAgentId?: string;
  statusLoaded: boolean;
  onDefaultAgentChange: any;
  onSaveDefaultAgent: any;
  onSubmit: (e: React.FormEvent) => void;
  // Optional TestSendCard node - rendered between the credential form and
  // the routing card, only when the channel is actually connected (callers
  // gate on the credential object so a disconnected channel can't offer a
  // send that would 4xx).
  testSend?: any;
  // "Log out of this channel" - supplied when the channel's credential
  // endpoint supports DELETE. Rendered on the connected card itself.
  onDisconnect?: () => Promise<void> | void;
  children: any;
  t: any;
}) {
  const isActive = credential && credential.status !== "DISCONNECTED";
  return (
    <>
      <InspectorHead channelKey={channelKey} title={title} subtitle={subtitle} />
      {loaded && isActive && (
        <div className="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-muted px-3 py-2.5">
          <div className="min-w-0">
            <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
              {t("connectedLabel")}
            </div>
            <div className="truncate font-mono text-xs text-foreground">{connectedLabel || t("connectedFallback")}</div>
          </div>
          {onDisconnect && (
            <Button type="button" variant="outline" size="sm" onClick={onDisconnect} disabled={busy}>
              {t("disconnectButton")}
            </Button>
          )}
        </div>
      )}
      {loaded && credential && credential.status === "DISCONNECTED" && (
        <ConnectedCard
          label={t("disconnectedLabel")}
          value={`${connectedLabel || t("connectedFallback")}${t("disconnectedReconnectHintShort")}`}
        />
      )}
      <form className="space-y-3" onSubmit={onSubmit}>
        {children}
        <Button type="submit" size="sm" disabled={busy} aria-busy={busy}>
          {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
          {isActive ? t("replaceButton") : t("connectButton")}
        </Button>
      </form>
      {testSend}
      {isActive && <ChannelHealthCard channelKey={channelKey} t={t} />}
      {/* Always rendered (not gated on `credential`) - routing is a per-channel
          upsert, so it can be set before the channel is ever connected. Same
          position at the bottom of every inspector. */}
      <InboundRoutingCard
        agents={agents}
        value={defaultAgentId}
        savedValue={savedDefaultAgentId}
        loaded={statusLoaded}
        onChange={onDefaultAgentChange}
        onSave={onSaveDefaultAgent}
        busy={busy}
        t={t}
      />
    </>
  );
}
