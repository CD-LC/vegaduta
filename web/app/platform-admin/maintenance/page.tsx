"use client";

import { useCallback, useEffect, useState } from "react";
import { Construction, Megaphone, RefreshCw, ExternalLink, KeyRound } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote, SwitchToggle } from "../components/sections/common";
import MasterKeyPrompt from "../components/tenants/MasterKeyPrompt";
import { ToneChip } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Textarea } from "../components/ui/textarea";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { opsApi, type AnnouncementState, type MaintenanceState } from "../lib/adminApi/operations";
import { toast } from "../lib/toastBus";
import { formatExact, formatRelative } from "../lib/platformFormat";
import {
  MAINTENANCE_CONFIRM_WORD,
  announcementLevelClasses,
  announcementLive,
  announcementProblems,
  isoToLocalInput,
  localInputToIso,
  maintenanceConfirmSatisfied,
} from "../lib/opsFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { Select } from "../../components/ui/select";

// Maintenance mode + the global announcement banner.
//
// Maintenance (GET/PUT /api/platform/maintenance): while on, core answers every
// non-platform-admin API call with 503 {error:"maintenance"} and the tenant app
// (web/) swaps to its maintenance screen; platform admins keep full access so
// this console keeps working. The PUT is master-key gated (X-Master-Key, held
// in memory for this browser session only) and enabling needs the typed word.
//
// Announcement (GET/PUT /api/platform/announcement): a dismissible banner every
// user of the tenant app sees, read from the public /api/public/status. No
// master key - it changes text, not access.

const EMPTY_ANNOUNCEMENT: AnnouncementState = {
  enabled: false,
  message: "",
  level: "info",
  linkUrl: null,
  expiresAt: null,
};

function MaintenanceSection() {
  const askConfirm = useConfirm();
  const [current, setCurrent] = useState<MaintenanceState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [message, setMessage] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [typed, setTyped] = useState("");
  const [saving, setSaving] = useState(false);
  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [keyPromptOpen, setKeyPromptOpen] = useState(false);
  const [pendingSave, setPendingSave] = useState(false);

  const load = useCallback(async () => {
    try {
      const m = await opsApi.maintenance();
      setCurrent(m);
      setEnabled(!!m.enabled);
      setMessage(m.message || "");
      setEndsAt(isoToLocalInput(m.endsAt));
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not read maintenance state.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const wasEnabled = !!current?.enabled;
  const confirmOk = maintenanceConfirmSatisfied(wasEnabled, enabled, typed);
  const dirty =
    !!current &&
    (enabled !== wasEnabled || message !== (current.message || "") || endsAt !== isoToLocalInput(current.endsAt));

  async function save(key: string) {
    setSaving(true);
    try {
      const next = await opsApi.setMaintenance(
        { enabled, message: message.trim(), endsAt: localInputToIso(endsAt) },
        key
      );
      setCurrent(next);
      setEnabled(!!next.enabled);
      setMessage(next.message || "");
      setEndsAt(isoToLocalInput(next.endsAt));
      setTyped("");
      toast.success(next.enabled ? "Maintenance mode is ON." : "Maintenance mode is off.");
    } catch (err: any) {
      // A rejected key is almost certainly wrong, not transient - forget it so
      // the next attempt re-prompts (same posture as tenant delete).
      if (err?.status === 401 || err?.status === 403) {
        setMasterKey(null);
      }
      // toJson already toasted the reason.
    } finally {
      setSaving(false);
    }
  }

  async function requestSave() {
    if (!confirmOk || !dirty) return;
    if (
      enabled &&
      !wasEnabled &&
      !(await askConfirm(
        "Turn maintenance mode ON? Every tenant user gets a maintenance screen until you turn it off."
      ))
    ) {
      return;
    }
    if (!masterKey) {
      setPendingSave(true);
      setKeyPromptOpen(true);
      return;
    }
    void save(masterKey);
  }

  return (
    <SectionCard
      title="Maintenance mode"
      subtitle="While on, every non-platform-admin API call gets 503 and the tenant app shows its maintenance screen. Platform admins keep access, so this console keeps working."
      icon={Construction}
      actions={
        <Button
          type="button"
          size="icon"
          variant="outline"
          aria-label="Refresh maintenance state"
          onClick={() => void load()}
        >
          <RefreshCw />
        </Button>
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {!current && !error && <Skeleton className="h-32 w-full" />}
      {current && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span>Currently</span>
            {wasEnabled ? <ToneChip tone="bad" label="ON" /> : <ToneChip tone="ok" label="Off" />}
            {wasEnabled && current.startedAt && (
              <span className="text-xs text-muted-foreground" title={formatExact(current.startedAt)}>
                since {formatRelative(current.startedAt)}
              </span>
            )}
            {wasEnabled && current.endsAt && (
              <span className="text-xs text-muted-foreground">· planned end {formatExact(current.endsAt)}</span>
            )}
          </div>

          <div className="flex items-center gap-2 text-sm">
            <SwitchToggle
              on={enabled}
              label="Maintenance mode"
              disabled={saving}
              onClick={() => setEnabled(!enabled)}
            />
            <span>{enabled ? "Maintenance on" : "Maintenance off"}</span>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="maint-message" className="text-xs text-muted-foreground">
              Message shown to users
            </Label>
            <Textarea
              id="maint-message"
              rows={3}
              value={message}
              maxLength={500}
              placeholder="We're upgrading the platform. Back by 14:00 UTC."
              onChange={(e: any) => setMessage(e.target.value)}
              disabled={saving}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="maint-ends" className="text-xs text-muted-foreground">
              Expected end (optional, your local time)
            </Label>
            <Input
              id="maint-ends"
              type="datetime-local"
              className="w-64"
              value={endsAt}
              onChange={(e) => setEndsAt(e.target.value)}
              disabled={saving}
            />
            <p className="text-[11px] text-muted-foreground">
              Shown to users as an estimate. Maintenance does not switch itself off at this time.
            </p>
          </div>

          {enabled && !wasEnabled && (
            <div className="space-y-1.5 rounded-lg border border-destructive/30 bg-destructive/5 p-3">
              <Label htmlFor="maint-confirm" className="text-xs">
                Type <code className="font-mono">{MAINTENANCE_CONFIRM_WORD}</code> to confirm
              </Label>
              <Input
                id="maint-confirm"
                className="w-64 font-mono"
                autoComplete="off"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                disabled={saving}
              />
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              variant={enabled && !wasEnabled ? "destructive" : "default"}
              disabled={!dirty || !confirmOk || saving}
              onClick={requestSave}
            >
              {saving
                ? "Saving..."
                : enabled && !wasEnabled
                  ? "Turn maintenance ON"
                  : !enabled && wasEnabled
                    ? "Turn maintenance off"
                    : "Save"}
            </Button>
            <span className="inline-flex items-center gap-1 text-[11px] text-muted-foreground">
              <KeyRound size={12} /> {masterKey ? "Master key unlocked for this session" : "Requires the master key"}
            </span>
          </div>
        </div>
      )}

      <MasterKeyPrompt
        open={keyPromptOpen}
        submitting={saving}
        description="Unlocks maintenance-mode changes for the rest of this browser session. It is re-checked on every change, not just this once."
        onCancel={() => {
          setKeyPromptOpen(false);
          setPendingSave(false);
        }}
        onSubmit={(key) => {
          setMasterKey(key);
          setKeyPromptOpen(false);
          if (pendingSave) {
            setPendingSave(false);
            void save(key);
          }
        }}
      />
    </SectionCard>
  );
}

function AnnouncementPreview({ a }: { a: AnnouncementState }) {
  if (!a.message.trim()) {
    return <p className="text-xs text-muted-foreground">Type a message to see the banner.</p>;
  }
  return (
    <div
      className={`flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-sm ${announcementLevelClasses(a.level)}`}
      role="note"
      aria-label="Announcement preview"
    >
      <Megaphone size={14} className="shrink-0" aria-hidden="true" />
      <span className="min-w-0 flex-1">{a.message}</span>
      {a.linkUrl && (
        <span className="inline-flex items-center gap-1 text-xs font-medium underline underline-offset-2">
          Learn more <ExternalLink size={11} />
        </span>
      )}
      <span className="text-xs opacity-70" aria-hidden="true">
        Dismiss ×
      </span>
    </div>
  );
}

function AnnouncementSection() {
  const [current, setCurrent] = useState<AnnouncementState | null>(null);
  const [draft, setDraft] = useState<AnnouncementState>(EMPTY_ANNOUNCEMENT);
  const [expiresLocal, setExpiresLocal] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const a = await opsApi.announcement();
      const normalized: AnnouncementState = {
        enabled: !!a?.enabled,
        message: a?.message || "",
        level: (a?.level as any) || "info",
        linkUrl: a?.linkUrl || null,
        expiresAt: a?.expiresAt || null,
      };
      setCurrent(normalized);
      setDraft(normalized);
      setExpiresLocal(isoToLocalInput(normalized.expiresAt));
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not read the announcement.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const candidate: AnnouncementState = {
    ...draft,
    message: draft.message,
    linkUrl: draft.linkUrl?.trim() ? draft.linkUrl.trim() : null,
    expiresAt: localInputToIso(expiresLocal),
  };
  const problems = announcementProblems(candidate);
  const dirty = !!current && JSON.stringify(candidate) !== JSON.stringify(current);

  async function save() {
    if (problems.length || !dirty) return;
    setSaving(true);
    try {
      const next = await opsApi.setAnnouncement({ ...candidate, message: candidate.message.trim() });
      toast.success(next?.enabled ? "Announcement published." : "Announcement saved (off).");
      await load();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setSaving(false);
    }
  }

  return (
    <SectionCard
      title="Announcement banner"
      subtitle="A dismissible banner at the top of the tenant app for every signed-in and public visitor. Users who dismiss it see it again only if the message changes."
      icon={Megaphone}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {!current && !error && <Skeleton className="h-32 w-full" />}
      {current && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span>Currently</span>
            {announcementLive(current) ? (
              <ToneChip tone="warn" label="Live" />
            ) : (
              <ToneChip tone="muted" label="Not showing" />
            )}
            {current.enabled && current.expiresAt && !announcementLive(current) && (
              <span className="text-xs text-muted-foreground">expired {formatRelative(current.expiresAt)}</span>
            )}
          </div>

          <div className="flex items-center gap-2 text-sm">
            <SwitchToggle
              on={draft.enabled}
              label="Announcement enabled"
              disabled={saving}
              onClick={() => setDraft({ ...draft, enabled: !draft.enabled })}
            />
            <span>{draft.enabled ? "Show the banner" : "Banner hidden"}</span>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="ann-message" className="text-xs text-muted-foreground">
              Message
            </Label>
            <Textarea
              id="ann-message"
              rows={2}
              maxLength={500}
              value={draft.message}
              onChange={(e: any) => setDraft({ ...draft, message: e.target.value })}
              disabled={saving}
            />
          </div>

          <div className="flex flex-wrap gap-4">
            <div className="space-y-1.5">
              <Label htmlFor="ann-level" className="text-xs text-muted-foreground">
                Level
              </Label>
              <Select
                id="ann-level"
                className="w-40"
                value={draft.level}
                onValueChange={(v) => setDraft({ ...draft, level: v as typeof draft.level })}
                disabled={saving}
                options={[
                  { value: "info", label: "Info" },
                  { value: "warning", label: "Warning" },
                  { value: "critical", label: "Critical" },
                ]}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ann-link" className="text-xs text-muted-foreground">
                Link (optional)
              </Label>
              <Input
                id="ann-link"
                className="w-72"
                placeholder="https://vegaduta.ai/changelog"
                value={draft.linkUrl || ""}
                onChange={(e) => setDraft({ ...draft, linkUrl: e.target.value })}
                disabled={saving}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ann-expires" className="text-xs text-muted-foreground">
                Expires (optional, local time)
              </Label>
              <Input
                id="ann-expires"
                type="datetime-local"
                className="w-64"
                value={expiresLocal}
                onChange={(e) => setExpiresLocal(e.target.value)}
                disabled={saving}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Live preview</p>
            <AnnouncementPreview a={candidate} />
          </div>

          {problems.length > 0 && (
            <ul className="list-disc space-y-0.5 pl-5 text-xs text-destructive">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}

          <Button size="sm" disabled={!dirty || problems.length > 0 || saving} onClick={save}>
            {saving ? "Saving..." : "Save announcement"}
          </Button>
        </div>
      )}
    </SectionCard>
  );
}

export default function MaintenancePage() {
  return (
    <AdminShell title="Maintenance & announcements">
      <PageHint>
        Planned downtime and platform-wide notices. Maintenance blocks tenant traffic (platform admins excepted); the
        announcement only informs.
      </PageHint>
      <InfoNote>
        The tenant app reads both from the public <code>/api/public/status</code> every five minutes, so a change can
        take up to five minutes to reach an open tab. Any API call that hits maintenance switches that tab immediately.
      </InfoNote>
      <MaintenanceSection />
      <AnnouncementSection />
    </AdminShell>
  );
}
