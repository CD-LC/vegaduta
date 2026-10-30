"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { motion } from "motion/react";
import Link from "next/link";
import { RefreshCw, FlaskConical, TrendingUp, Activity, Sliders, Table2, Info } from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { toast } from "../lib/toastBus";
import { PageHint, SectionCard } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { fmtAvg, fmtDateTime, fmtInt, fmtRate } from "./sandboxFormat";
import { DailyChart, NumberField, StatTile, StatusBadge, Toggle } from "./SandboxWidgets";
import { useTranslations } from "../../lib/i18n";

// Demo Sandbox - controls + analytics for the anonymous "try it without
// registering" surface. Backed by DemoSandboxController (core/platform):
// GET/PUT /api/platform/demo-settings and GET /api/platform/demo-metrics.
//
// The hero number is the CONVERSION RATE, because that is the only question
// this feature exists to answer: of the strangers who took a free sandbox, how
// many came back and registered. Everything else on the page is context for
// that number.
//
// Honesty rules this screen follows, since every figure here is a real
// aggregate and none of it is seeded or illustrative:
//   - a rate with a zero denominator renders "n/a", never "0%";
//   - a metric the backend reports as null (because the migration behind it
//     hasn't landed in this deployment) renders its server-supplied reason,
//     never a 0;
//   - a control that is stored but not yet read by any code says so inline.
// The chart is hand-rolled SVG - no charting dependency is worth adding for one
// two-series bar chart, and a new npm dep is explicitly out of scope here.

export default function DemoSandboxPage() {
    const tHC = useTranslations("hcPlatformadminDemosandboxPage");
  const [metrics, setMetrics] = useState<any>(null);
  const [metricsError, setMetricsError] = useState<string | null>(null);
  const [settings, setSettings] = useState<any>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [draft, setDraft] = useState<any>({});
  const [days, setDays] = useState(30);
  const [refreshing, setRefreshing] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadMetrics = useCallback(async (windowDays: number) => {
    try {
      const data = await api.getDemoMetrics(windowDays, 20);
      setMetrics(data);
      setMetricsError(null);
    } catch (err: any) {
      setMetrics(null);
      setMetricsError(err?.message || "Could not load demo metrics.");
    }
  }, []);

  const loadSettings = useCallback(async () => {
    try {
      const data = await api.getDemoSettings();
      setSettings(data);
      setDraft({ ...data.effective });
      setSettingsError(null);
    } catch (err: any) {
      setSettings(null);
      setSettingsError(err?.message || "Could not load demo settings.");
    }
  }, []);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    await Promise.all([loadMetrics(days), loadSettings()]);
    setRefreshing(false);
  }, [days, loadMetrics, loadSettings]);

  useEffect(() => {
    refresh().catch((e) => console.error("Demo sandbox refresh failed", e));
    // Intentionally keyed on `days` only: changing the range re-reads both, and
    // re-running on every new `refresh` identity would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days]);

  const effective = settings?.effective;
  const defaults = settings?.defaults;
  const keys = settings?.settingKeys ?? {};

  // Only send what actually changed - the PUT is a partial update, so an
  // untouched field stays untouched rather than being rewritten with the value
  // this tab happened to load minutes ago.
  const changed = useMemo(() => {
    if (!effective) return {};
    const out: any = {};
    for (const field of Object.keys(effective)) {
      const current = effective[field];
      let next = draft[field];
      if (typeof current === "number") {
        if (next === "" || next === null || next === undefined) continue;
        next = Number(next);
        if (Number.isNaN(next)) continue;
      }
      if (next !== current) out[field] = next;
    }
    return out;
  }, [draft, effective]);

  const dirty = Object.keys(changed).length > 0;

  async function handleSave() {
    if (!dirty) return;
    setSaving(true);
    try {
      const updated = await api.updateDemoSettings(changed);
      setSettings(updated);
      setDraft({ ...updated.effective });
      toast.success("Demo sandbox settings saved — live on the next demo request, no restart.");
    } catch (err: any) {
      // The backend's 400 names the offending field and its allowed range, and
      // toJson has already toasted it; this only covers the no-message case.
      if (!err?.message) toast.error("Could not save demo sandbox settings.");
    } finally {
      setSaving(false);
    }
  }

  const hero = metrics?.last30Days;
  const engagement = metrics?.engagement;
  const unavailable = metrics && metrics.available === false;

  return (
    <AdminShell
      title={tHC("s43")}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Button asChild variant="outline" size="sm">
            <Link href="/platform-admin/demo-sandboxes">{tHC("s42")}</Link>
          </Button>
          <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing} title="Refresh">
            <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
          </Button>
        </div>
      }
    >
      <PageHint>
        {tHC("s41")}{" "}
        <code className="font-mono text-xs">control_plane.demo_session</code> {tHC("s40")}
      </PageHint>

      {metricsError && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{metricsError}</AlertDescription>
        </Alert>
      )}

      {unavailable && (
        <Alert className="mb-5">
          <Info />
          <AlertDescription>
            {tHC("s39")}{" "}
            <code className="font-mono text-xs">control_plane.demo_session</code> {tHC("s38")}
          </AlertDescription>
        </Alert>
      )}

      {metrics?.available && (
        <>
          {/* Hero: the number the whole feature exists to move. */}
          <motion.div
            className="mb-5"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
          >
            <Card className="gap-0 py-0">
              <CardContent className="flex flex-wrap items-end justify-between gap-6 p-6">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 text-xs uppercase tracking-[0.14em] text-muted-foreground">
                    <TrendingUp size={14} className="text-primary" />
                    {tHC("s37")}
                  </div>
                  <div className="mt-2 font-mono text-5xl font-semibold tabular-nums text-primary">
                    {fmtRate(hero?.conversionRate)}
                  </div>
                  <p className="mt-2 max-w-xl text-xs leading-relaxed text-muted-foreground">
                    {fmtInt(hero?.converted)} of the {fmtInt(hero?.created)} {tHC("s36")}
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-4 sm:gap-6">
                  <div>
                    <div className="text-xs text-muted-foreground">{tHC("s35")}</div>
                    <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-foreground">
                      {fmtRate(metrics?.last7Days?.conversionRate)}
                    </div>
                    <div className="text-[11px] text-muted-foreground">
                      {fmtInt(metrics?.last7Days?.converted)} / {fmtInt(metrics?.last7Days?.created)}
                    </div>
                  </div>
                  <div>
                    <div className="text-xs text-muted-foreground">{tHC("s34")}</div>
                    <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-foreground">
                      {fmtRate(metrics?.allTime?.conversionRate)}
                    </div>
                    <div className="text-[11px] text-muted-foreground">
                      {fmtInt(metrics?.allTime?.converted)} / {fmtInt(metrics?.allTime?.created)}
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>
          </motion.div>

          <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatTile label={tHC("s33")} value={fmtInt(metrics?.today?.created)} hint="UTC day" />
            <StatTile label={tHC("s32")} value={fmtInt(metrics?.last7Days?.created)} />
            <StatTile label={tHC("s31")} value={fmtInt(metrics?.last30Days?.created)} />
            <StatTile label={tHC("s30")} value={fmtInt(metrics?.allTime?.created)} />
            <StatTile
              label={tHC("s29")}
              value={fmtInt(metrics?.liveCount)}
              hint="Not retired and not past expiry"
              emphasis
            />
            <StatTile label="Expired" value={fmtInt(metrics?.expiredCount)} hint="Retired or past expiry" />
            <StatTile
              label={tHC("s28")}
              value={fmtInt(metrics?.allTime?.converted)}
              hint="converted_tenant_id is set"
            />
            <StatTile
              label="Engaged"
              value={fmtInt(engagement?.engagedSandboxes)}
              hint={`${fmtRate(engagement?.engagementRate)} sent at least one message`}
            />
          </div>

          <SectionCard
            title={tHC("s27")}
            subtitle="UTC days. Created is bucketed by creation date; converted by the date the visitor actually registered."
            icon={Activity}
            actions={
              <div
                className="flex items-center gap-0.5 rounded-md border border-border bg-secondary/40 p-0.5"
                role="group"
              >
                {[14, 30].map((n) => (
                  <Button
                    key={n}
                    size="sm"
                    variant={days === n ? "default" : "ghost"}
                    aria-pressed={days === n}
                    onClick={() => setDays(n)}
                    className="h-7"
                  >
                    {n}d
                  </Button>
                ))}
              </div>
            }
            delay={0.03}
          >
            <DailyChart series={metrics?.dailySeries ?? []} />
          </SectionCard>

          <SectionCard
            title="Engagement"
            subtitle="Did the sandboxes we handed out actually get used?"
            icon={FlaskConical}
            delay={0.06}
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile label={tHC("s26")} value={fmtInt(engagement?.totalMessages)} />
              <StatTile
                label={tHC("s25")}
                value={fmtAvg(engagement?.averageMessagesPerSandbox)}
                hint="Across every sandbox, including the ones that never sent a turn"
              />
              <StatTile
                label="Bounced"
                value={fmtInt(engagement?.bouncedSandboxes)}
                hint="Created but never sent a message"
              />
              <StatTile
                label={tHC("s24")}
                value={
                  engagement?.imagesGenerated === null || engagement?.imagesGenerated === undefined
                    ? "not available"
                    : fmtInt(engagement?.imagesGenerated)
                }
                hint={
                  engagement?.imageMetricsUnavailableReason ||
                  `${fmtInt(engagement?.sandboxesWithImages)} sandbox(es) generated at least one`
                }
              />
            </div>
            <div className="mt-3 rounded-lg border border-border p-3 text-sm">
              {engagement?.channelMetricsUnavailableReason ? (
                <span className="text-muted-foreground">{engagement.channelMetricsUnavailableReason}</span>
              ) : (
                <span className="text-foreground">
                  <span className="font-mono tabular-nums">{fmtInt(engagement?.channelLinks)}</span> {tHC("s23")}
                  {engagement?.channelLinks === 1 ? "" : "s"} {tHC("s22")}{" "}
                  <span className="font-mono tabular-nums">{fmtInt(engagement?.sandboxesWithChannelLink)}</span>{" "}
                  sandbox(es); <span className="font-mono tabular-nums">{fmtInt(engagement?.linkedChannelLinks)}</span>{" "}
                  {tHC("s21")}
                </span>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title={tHC("s20")}
            subtitle="The 20 most recent, newest first. Tokens and creator IPs are deliberately not exposed here."
            icon={Table2}
            delay={0.09}
          >
            {(metrics?.recent ?? []).length === 0 ? (
              <p className="text-sm text-muted-foreground">{tHC("s19")}</p>
            ) : (
              <div className="-mx-2 overflow-x-auto">
                <table className="w-full min-w-[52rem] border-collapse text-sm">
                  <thead>
                    <tr className="border-b border-border text-start text-xs uppercase tracking-wide text-muted-foreground">
                      <th className="px-2 py-2 font-medium">Workspace</th>
                      <th className="px-2 py-2 font-medium">Status</th>
                      <th className="px-2 py-2 font-medium">Created</th>
                      <th className="px-2 py-2 font-medium">Expires</th>
                      <th className="px-2 py-2 text-end font-medium">Messages</th>
                      <th className="px-2 py-2 font-medium">Email</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(metrics?.recent ?? []).map((row: any) => (
                      <tr key={row.id} className="border-b border-border/60 last:border-0">
                        <td className="px-2 py-2">
                          <div className="font-medium text-foreground">{row.tenantName || "—"}</div>
                          <code className="font-mono text-[11px] text-muted-foreground">{row.tenantSchema}</code>
                        </td>
                        <td className="px-2 py-2">
                          <StatusBadge status={row.status} />
                        </td>
                        <td className="px-2 py-2 whitespace-nowrap text-xs text-muted-foreground">
                          {fmtDateTime(row.createdAt)}
                        </td>
                        <td className="px-2 py-2 whitespace-nowrap text-xs text-muted-foreground">
                          {fmtDateTime(row.expiresAt)}
                        </td>
                        <td className="px-2 py-2 text-end font-mono tabular-nums">{fmtInt(row.messageCount)}</td>
                        <td className="px-2 py-2 text-xs text-muted-foreground">{row.email || "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>
        </>
      )}

      {settingsError && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{settingsError}</AlertDescription>
        </Alert>
      )}

      {effective && (
        <SectionCard
          title="Controls"
          subtitle="Every knob below is read live by the demo services — a save applies to the next demo request, no restart."
          icon={Sliders}
          delay={0.12}
          actions={
            <Button size="sm" disabled={!dirty || saving} onClick={handleSave}>
              {saving ? "Saving…" : dirty ? `Save ${Object.keys(changed).length} change(s)` : "Save changes"}
            </Button>
          }
        >
          {(settings?.notes ?? []).length > 0 && (
            <Alert className="mb-1">
              <Info />
              <AlertDescription>
                <ul className="list-disc space-y-1 ps-4">
                  {(settings?.notes ?? []).map((note: string) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}

          <Toggle
            label={tHC("s18")}
            description="Master kill switch. Off refuses new sandboxes; existing ones keep working until they expire."
            settingKey={keys.enabled}
            value={!!draft.enabled}
            onChange={(next) => setDraft((d: any) => ({ ...d, enabled: next }))}
          />

          <div className="grid gap-3 sm:grid-cols-2">
            <NumberField
              label={tHC("s17")}
              description="How many unexpired sandboxes one IP may hold at once. 1 = one demo per visitor."
              settingKey={keys.maxLiveSessionsPerIp}
              fallback={defaults?.maxLiveSessionsPerIp}
              value={draft.maxLiveSessionsPerIp}
              onChange={(v) => setDraft((d: any) => ({ ...d, maxLiveSessionsPerIp: v }))}
            />
            <NumberField
              label={tHC("s16")}
              description="Paired with the cooldown window below: 1 per 60 minutes means one attempt, then wait an hour."
              settingKey={keys.maxSessionsPerCooldown}
              fallback={defaults?.maxSessionsPerCooldown}
              value={draft.maxSessionsPerCooldown}
              onChange={(v) => setDraft((d: any) => ({ ...d, maxSessionsPerCooldown: v }))}
            />
            <NumberField
              label={tHC("s15")}
              description="How long an IP waits before it may create another sandbox. 1–10080."
              settingKey={keys.cooldownMinutes}
              fallback={defaults?.cooldownMinutes}
              value={draft.cooldownMinutes}
              onChange={(v) => setDraft((d: any) => ({ ...d, cooldownMinutes: v }))}
            />
            <NumberField
              label={tHC("s14")}
              description="TTL an ACTIVE sandbox gets once real activity happens (a chat turn, an image) - each activity slides this deadline forward again. 1–30."
              settingKey={keys.ttlDays}
              fallback={defaults?.ttlDays}
              value={draft.ttlDays}
              onChange={(v) => setDraft((d: any) => ({ ...d, ttlDays: v }))}
            />
            <NumberField
              label={tHC("s13")}
              description="A sandbox that's NEVER been touched retires after this many hours instead of the full lifetime above. 'Retired' suspends it (visitor's data stays, they just can't chat) - deleting it is a manual operator step, not automatic. 1–168."
              settingKey={keys.inactivityTimeoutHours}
              fallback={defaults?.inactivityTimeoutHours}
              value={draft.inactivityTimeoutHours}
              onChange={(v) => setDraft((d: any) => ({ ...d, inactivityTimeoutHours: v }))}
            />
            <NumberField
              label={tHC("s12")}
              description="Chat turns a single sandbox may spend. 1–500."
              settingKey={keys.messageLimit}
              fallback={defaults?.messageLimit}
              value={draft.messageLimit}
              onChange={(v) => setDraft((d: any) => ({ ...d, messageLimit: v }))}
            />
            <NumberField
              label={tHC("s11")}
              description="When the one-time 'your sandbox is ending' email goes out. Must be less than the lifetime."
              settingKey={keys.reminderHoursBeforeExpiry}
              fallback={defaults?.reminderHoursBeforeExpiry}
              value={draft.reminderHoursBeforeExpiry}
              onChange={(v) => setDraft((d: any) => ({ ...d, reminderHoursBeforeExpiry: v }))}
            />
          </div>

          {/* IP allowlist — rendered only when the backend reports the key
              (an older core doesn't send it), same posture as the Magic
              Studio block below. Free text rather than NumberField: the
              value is a comma-separated address list. */}
          {keys.exemptIps && (
            <div className="rounded-lg border border-border p-3">
              <Label htmlFor="demo-exempt-ips" className="text-sm font-medium text-foreground">
                {tHC("s10")}
              </Label>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {tHC("s9")}
              </p>
              <Input
                id="demo-exempt-ips"
                className="mt-2 font-mono text-xs"
                placeholder={tHC("s8")}
                value={draft.exemptIps ?? ""}
                onChange={(e) => setDraft((d: any) => ({ ...d, exemptIps: e.target.value }))}
              />
              <code className="mt-1.5 block font-mono text-[11px] text-muted-foreground/70">{keys.exemptIps}</code>
            </div>
          )}

          <Toggle
            label={tHC("s7")}
            description="Whether a sandbox may generate images at all."
            settingKey={keys.imageEnabled}
            value={!!draft.imageEnabled}
            onChange={(next) => setDraft((d: any) => ({ ...d, imageEnabled: next }))}
          />
          <NumberField
            label={tHC("s6")}
            description="Total images one sandbox may generate. 0 stops generation without flipping the toggle above."
            settingKey={keys.imagePerSessionLimit}
            fallback={defaults?.imagePerSessionLimit}
            value={draft.imagePerSessionLimit}
            onChange={(v) => setDraft((d: any) => ({ ...d, imagePerSessionLimit: v }))}
          />
          <Toggle
            label={tHC("s5")}
            description="Kill switch for demo Telegram channel linking. See the note above — this is stored but not yet enforced."
            settingKey={keys.telegramEnabled}
            value={!!draft.telegramEnabled}
            onChange={(next) => setDraft((d: any) => ({ ...d, telegramEnabled: next }))}
          />

          {/* Magic Studio — the landing "say it, watch it build" flow and its
              published /w/<shareId> public run pages. Rendered only when the
              backend reports the knobs (an older core simply doesn't send
              them), so this card never shows dead controls. */}
          {keys.magicEnabled && (
            <>
              <Toggle
                label={tHC("s4")}
                description="Kill switch for the landing workflow-builder AND every published public run link (/w/…)."
                settingKey={keys.magicEnabled}
                value={!!draft.magicEnabled}
                onChange={(next) => setDraft((d: any) => ({ ...d, magicEnabled: next }))}
              />
              <div className="grid gap-3 sm:grid-cols-3">
                <NumberField
                  label={tHC("s3")}
                  description="Distinct visitors allowed to run one published workflow link, for its whole life. 1–100."
                  settingKey={keys.magicRunCap}
                  fallback={defaults?.magicRunCap}
                  value={draft.magicRunCap}
                  onChange={(v) => setDraft((d: any) => ({ ...d, magicRunCap: v }))}
                />
                <NumberField
                  label={tHC("s2")}
                  description="Runs one IP gets on one link. 1 = once per person. 1–10."
                  settingKey={keys.magicPerIpRuns}
                  fallback={defaults?.magicPerIpRuns}
                  value={draft.magicPerIpRuns}
                  onChange={(v) => setDraft((d: any) => ({ ...d, magicPerIpRuns: v }))}
                />
                <NumberField
                  label={tHC("s1")}
                  description="Magic workflows one sandbox may create. 1–50."
                  settingKey={keys.magicPerSessionLimit}
                  fallback={defaults?.magicPerSessionLimit}
                  value={draft.magicPerSessionLimit}
                  onChange={(v) => setDraft((d: any) => ({ ...d, magicPerSessionLimit: v }))}
                />
              </div>
            </>
          )}
        </SectionCard>
      )}
    </AdminShell>
  );
}
