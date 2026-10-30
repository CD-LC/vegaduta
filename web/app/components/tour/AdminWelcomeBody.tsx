"use client";

// The welcome popup a workspace ADMIN sees on first login (TourOverlay swaps it
// in for the generic welcome hero when audience === "tenant-admin").
//
// Why it exists: almost everyone who signs up lands as the admin of their own
// workspace without knowing it. They need three facts before anything else -
// you are in charge, you can bring your team in by email, and the fastest way
// to get value is to start building right now - and one honest way out: take the
// tour, or start. So this is the tour's front door, not a second popup: the
// tour's own start/skip handlers drive it, so persistence, replay and the "?"
// re-open hint all keep working.
//
// Team invite is REAL: it calls the same endpoint as the Access page
// (api.createAdminUser, invite-by-email path: the person gets a set-password
// email; no password is ever typed here). The realm's User Profile needs first
// and last name, hence three fields. Plan seat limits and permission checks are
// enforced by the server; its message is shown verbatim on failure.
//
// Copy is inline English on purpose (same reason as the tour's `inline` steps:
// messages/en.json is written by other sessions, and a missing key would show a
// key path on the first screen a new admin sees).

import { useState, type FormEvent } from "react";
import { Bot, Check, ChevronDown, Loader2, Mail, ShieldCheck, Smartphone, Workflow } from "lucide-react";
import { api } from "../../lib/api";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { isLocalAuth } from "../../lib/authMode";
import { useTranslations } from "../../lib/i18n";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

const POINTS = [
  {
    icon: Bot,
    title: "The agent comes first",
    text: "Set its instructions, model, tone, knowledge, tools and guardrails before anyone meets it.",
  },
  {
    icon: Workflow,
    title: "Workflows multiply its reach",
    text: "Connect triggers, approvals and repeatable work around the agent.",
  },
  {
    icon: Smartphone,
    title: "Apps put it in people's hands",
    text: "Build an AI-powered experience that uses your custom agents and workflows.",
  },
] as const;

export default function AdminWelcomeBody({
  titleId,
  bodyId,
  onStart,
  onBuild,
  onSkip,
}: {
  titleId: string;
  bodyId: string;
  onStart: () => void;
  onBuild: () => void;
  onSkip: () => void;
}) {
    const tHC = useTranslations("hcComponentsTourAdminWelcomeBody");
  const [inviteOpen, setInviteOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [first, setFirst] = useState("");
  const [last, setLast] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [invited, setInvited] = useState<string[]>([]);

  const canSend = EMAIL_RE.test(email.trim()) && first.trim() !== "" && last.trim() !== "";

  async function invite(e: FormEvent) {
    e.preventDefault();
    if (!canSend || busy) return;
    setBusy(true);
    setError(null);
    const address = email.trim().toLowerCase();
    try {
      await api.createAdminUser({
        username: address,
        email: address,
        firstName: first.trim(),
        lastName: last.trim(),
        temporaryPassword: null,
        inviteByEmail: true,
        initialRoles: [],
        initialPermissions: [],
      });
      setInvited((prev) => [...prev, address]);
      setEmail("");
      setFirst("");
      setLast("");
    } catch (err: unknown) {
      setError(err instanceof Error && err.message ? err.message : "The invitation could not be sent.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-h-[min(78vh,640px)] overflow-y-auto p-5 sm:p-6">
      <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/30 bg-primary/10 px-2.5 py-0.5 text-[11px] font-semibold text-primary">
        <ShieldCheck size={12} aria-hidden="true" /> {tHC("s13")}
      </span>
      <h2 id={titleId} className="mt-2.5 text-xl font-semibold tracking-tight text-foreground">
        {tHC("s12")}
      </h2>
      <p id={bodyId} className="mt-1.5 text-sm leading-relaxed text-muted-foreground">
        {tHC("s11")}
      </p>

      <ul className="mt-4 space-y-2.5">
        {POINTS.map(({ icon: Icon, title, text }) => (
          <li key={title} className="flex items-start gap-3 rounded-xl border border-border bg-muted/30 p-3">
            <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Icon size={16} />
            </span>
            <span className="min-w-0">
              <span className="block text-sm font-semibold text-foreground">{title}</span>
              <span className="block text-xs leading-relaxed text-muted-foreground">{text}</span>
            </span>
          </li>
        ))}
      </ul>

      {/* Personal install (local auth): one owner, no mail server - no invite-by-email nudge. */}
      {!isLocalAuth() && (
        <div className="mt-3 rounded-xl border border-border">
          <button
            type="button"
            onClick={() => setInviteOpen((o) => !o)}
            aria-expanded={inviteOpen}
            className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-start"
          >
            <span className="flex items-center gap-2 text-sm font-medium text-foreground">
              <Mail size={14} className="text-primary" aria-hidden="true" /> {tHC("s10")}
              {invited.length > 0 && (
                <span className="rounded-full bg-primary/10 px-1.5 text-[10px] font-semibold text-primary">
                  {invited.length} sent
                </span>
              )}
            </span>
            <ChevronDown
              size={14}
              className={cn("text-muted-foreground transition-transform", inviteOpen && "rotate-180")}
              aria-hidden="true"
            />
          </button>
          {inviteOpen && (
            <form onSubmit={(e) => void invite(e)} className="space-y-2 border-t border-border p-3">
              <p className="text-xs text-muted-foreground">
                {tHC("s9")}
              </p>
              <div className="grid gap-2 sm:grid-cols-2">
                <Input
                  value={first}
                  onChange={(e) => setFirst(e.target.value)}
                  placeholder={tHC("s8")}
                  aria-label={tHC("s8")}
                  autoComplete="off"
                />
                <Input
                  value={last}
                  onChange={(e) => setLast(e.target.value)}
                  placeholder={tHC("s7")}
                  aria-label={tHC("s7")}
                  autoComplete="off"
                />
              </div>
              <Input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="teammate@company.com"
                aria-label={tHC("s6")}
                autoComplete="off"
              />
              {error && (
                <p role="alert" className="text-xs text-destructive">
                  {error}
                </p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                <Button type="submit" size="sm" disabled={!canSend || busy}>
                  {busy ? (
                    <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Mail size={14} aria-hidden="true" />
                  )}{" "}
                  {tHC("s5")}
                </Button>
                {invited.length > 0 && (
                  <span className="text-xs text-muted-foreground">
                    <Check size={11} className="me-1 inline text-primary" aria-hidden="true" />
                    Invited: {invited.join(", ")}
                  </span>
                )}
              </div>
            </form>
          )}
        </div>
      )}

      <div className="mt-5 flex flex-wrap items-center gap-2.5">
        <Button onClick={onStart} size="sm">
          <Bot size={14} aria-hidden="true" /> {tHC("s4")}
        </Button>
        <Button onClick={onBuild} variant="outline" size="sm">
          {tHC("s3")}
        </Button>
        <Button onClick={onSkip} variant="ghost" size="sm" className="ms-auto text-muted-foreground">
          {tHC("s2")}
        </Button>
      </div>
      <p className="mt-3 text-[11px] text-muted-foreground">
        {tHC("s1")}
      </p>
    </div>
  );
}
