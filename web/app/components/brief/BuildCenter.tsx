"use client";

// Build Center (/build). One box: say what you want done, in your own words and
// your own language. Three doors in (a brief, an existing envoy from the
// template gallery, the court tour) plus a fourth for people who want code.
// Below: the envoys you already have, with their trust rung and readiness.

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Code2, Compass, Landmark, Loader2, PenLine, Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import { useLocale, useTranslations } from "../../lib/i18n";
import { toast } from "../../lib/toastBus";
import { cn } from "../../lib/utils";
import { useTour } from "../TourProvider";
import { EmptyState, Section } from "../ax/page-kit";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Textarea } from "../ui/textarea";
import BriefWorkspace from "./BriefWorkspace";
import { Clarifications, ThinkingLines } from "./PlanStream";
import RungBadge from "./RungBadge";
import { pct } from "./ledger";
import { READINESS_STATE_TOKEN } from "./logic";
import type {
  BriefDetail,
  BriefListItem,
  ChannelKind,
  PlanContext,
  PlanResult,
  ReadinessRow,
  ReadinessState,
  ReadinessSummary,
} from "./types";

const PLACEHOLDER_COUNT = 6;
const PLACEHOLDER_MS = 4500;
const AUDIENCES: NonNullable<PlanContext["audience"]>[] = ["ME", "TEAM", "CUSTOMERS"];
const CHANNEL_CHOICES: ChannelKind[] = ["WHATSAPP", "EMAIL", "WEB", "VOICE", "SLACK"];

type Phase = "home" | "planning" | "clarifying" | "review" | "envoy";

export default function BuildCenter({
  onWalkCourt,
  onOpenAppBuilder,
  detailHref,
}: {
  /** Overrides how door 3 opens the court; defaults to the tour's own Court map. */
  onWalkCourt?: () => void;
  /** Switches /build to the App Builder tab. */
  onOpenAppBuilder: () => void;
  /** Where an applied envoy's proof, trust and tuning page lives (default /build/[id]). */
  detailHref?: (id: string) => string;
}) {
  const t = useTranslations("brief");
  const locale = useLocale();
  const tour = useTour();

  const [prompt, setPrompt] = useState("");
  const [audience, setAudience] = useState<PlanContext["audience"] | undefined>();
  const [channels, setChannels] = useState<ChannelKind[]>([]);
  const [mustNeverOn, setMustNeverOn] = useState(false);
  const [mustNever, setMustNever] = useState("");
  const [onDeviceOnly, setOnDeviceOnly] = useState(false);

  const [phase, setPhase] = useState<Phase>("home");
  const [lines, setLines] = useState<string[]>([]);
  const [result, setResult] = useState<PlanResult | null>(null);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [detail, setDetail] = useState<BriefDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [envoys, setEnvoys] = useState<BriefListItem[] | null>(null);
  const [envoysError, setEnvoysError] = useState<string | null>(null);
  const [opening, setOpening] = useState<string | null>(null);

  const [phIndex, setPhIndex] = useState(0);
  const [focused, setFocused] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (focused || prompt) return undefined;
    const id = window.setInterval(() => setPhIndex((i) => (i + 1) % PLACEHOLDER_COUNT), PLACEHOLDER_MS);
    return () => window.clearInterval(id);
  }, [focused, prompt]);

  const loadEnvoys = useCallback(() => {
    api
      .listBriefs()
      .then((list: BriefListItem[]) => {
        setEnvoys(Array.isArray(list) ? list : []);
        setEnvoysError(null);
      })
      .catch((e: Error) => {
        setEnvoys([]);
        setEnvoysError(e.message);
      });
  }, []);
  useEffect(loadEnvoys, [loadEnvoys]);
  useEffect(() => () => abortRef.current?.abort(), []);

  const buildContext = (): PlanContext | undefined => {
    const ctx: PlanContext = {};
    if (audience) ctx.audience = audience;
    if (channels.length) ctx.channels = channels;
    if (mustNeverOn && mustNever.trim()) ctx.mustNeverDo = mustNever.trim();
    if (onDeviceOnly) ctx.onDeviceOnly = true;
    return Object.keys(ctx).length ? ctx : undefined;
  };

  const plan = useCallback(
    async (text: string) => {
      abortRef.current?.abort();
      const ctl = new AbortController();
      abortRef.current = ctl;
      setError(null);
      setLines([]);
      setResult(null);
      setPhase("planning");
      try {
        const res = await api.planBriefStream(
          { prompt: text, locale, context: buildContext() },
          (e: any) => {
            if (e.type === "thinking") setLines((prev) => [...prev, e.text]);
          },
          ctl.signal
        );
        if (ctl.signal.aborted) return;
        setResult(res);
        setAnswers({});
        setPhase((res.brief.clarifications?.length ?? 0) > 0 ? "clarifying" : "review");
      } catch (e: any) {
        if (ctl.signal.aborted || e?.name === "AbortError") return;
        setError(e?.message || t("plan.failed"));
        setPhase("home");
      }
    },
    // buildContext reads the chip state; re-create only when it changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [locale, audience, channels, mustNeverOn, mustNever, onDeviceOnly, t]
  );

  const submit = () => {
    const text = prompt.trim();
    if (text.length < 10) {
      inputRef.current?.focus();
      setError(t("hero.tooShort"));
      return;
    }
    void plan(text);
  };

  // Re-plan once every clarification has an answer (or the person skips).
  const replanWith = (given: Record<number, string>) => {
    const qs = result?.brief.clarifications ?? [];
    const extra = qs
      .map((c, i) => (given[i] ? `${c.question} ${given[i]}` : null))
      .filter(Boolean)
      .join("\n");
    void plan(extra ? `${prompt.trim()}\n${extra}` : prompt.trim());
  };
  const answer = (index: number, value: string) => {
    const next = { ...answers, [index]: value };
    setAnswers(next);
    if (Object.keys(next).length >= Math.min(3, result?.brief.clarifications?.length ?? 0)) replanWith(next);
  };

  const openEnvoy = async (id: string) => {
    setOpening(id);
    try {
      const d: BriefDetail = await api.getBrief(id);
      setDetail(d);
      setResult({ brief: d.brief, readiness: d.readiness, warnings: [] });
      setPhase("envoy");
    } catch (e: any) {
      setEnvoysError(e?.message || t("envoys.openFailed"));
    } finally {
      setOpening(null);
    }
  };

  const backHome = () => {
    abortRef.current?.abort();
    setPhase("home");
    setResult(null);
    setDetail(null);
    loadEnvoys();
  };

  // Stub until admin requests exist: put a ready-to-send request on the clipboard.
  const askAdmin = (row: ReadinessRow) => {
    const text = t("rail.adminRequestText", { name: row.label });
    const done = () => toast.info(t("rail.adminRequestCopied"));
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(text).then(done, () => toast.info(text));
    else toast.info(text);
  };

  if ((phase === "review" || phase === "envoy") && result) {
    return (
      <BriefWorkspace
        brief={result.brief}
        readiness={result.readiness}
        warnings={result.warnings}
        detail={phase === "envoy" ? detail : null}
        onBack={backHome}
        onAskAdmin={askAdmin}
        detailHref={detailHref}
      />
    );
  }

  const toggleChannel = (c: ChannelKind) =>
    setChannels((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : [...prev, c]));
  const busy = phase === "planning";

  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <div className="space-y-1">
          <h2 className="text-xl font-semibold tracking-tight text-foreground">{t("hero.title")}</h2>
          <p className="max-w-2xl text-sm text-muted-foreground">{t("hero.subtitle")}</p>
        </div>

        <Textarea
          ref={inputRef}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey) && !busy) submit();
          }}
          placeholder={t(`hero.p${phIndex + 1}`)}
          aria-label={t("hero.title")}
          rows={4}
          disabled={busy}
          className="text-base"
          data-tour="build-brief"
        />

        <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("chips.label")}>
          <span className="text-xs text-muted-foreground">{t("chips.optional")}</span>
          {AUDIENCES.map((a) => (
            <Chip key={a} pressed={audience === a} onClick={() => setAudience(audience === a ? undefined : a)}>
              {t(`chips.audience.${a}`)}
            </Chip>
          ))}
          {CHANNEL_CHOICES.map((c) => (
            <Chip key={c} pressed={channels.includes(c)} onClick={() => toggleChannel(c)}>
              {t(`chips.channel.${c}`)}
            </Chip>
          ))}
          <Chip pressed={mustNeverOn} onClick={() => setMustNeverOn((v) => !v)}>
            {t("chips.mustNever")}
          </Chip>
          <Chip pressed={onDeviceOnly} onClick={() => setOnDeviceOnly((v) => !v)}>
            {t("chips.onDevice")}
          </Chip>
        </div>
        {mustNeverOn && (
          <input
            value={mustNever}
            onChange={(e) => setMustNever(e.target.value)}
            placeholder={t("chips.mustNeverPlaceholder")}
            aria-label={t("chips.mustNever")}
            className="h-9 w-full max-w-xl rounded-md border border-border bg-transparent px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          />
        )}

        <div className="flex items-center gap-3">
          <Button onClick={submit} disabled={busy}>
            {busy ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <Sparkles className="size-4" aria-hidden="true" />
            )}
            {t("hero.cta")}
          </Button>
          <span className="text-xs text-muted-foreground">{t("hero.hint")}</span>
        </div>
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {(phase === "planning" || phase === "clarifying") && (
        <div className="space-y-4">
          <ThinkingLines lines={lines} running={busy} />
          {phase === "clarifying" && result && (
            <Clarifications
              items={result.brief.clarifications ?? []}
              answers={answers}
              onAnswer={answer}
              onSkip={() => replanWith(answers)}
            />
          )}
        </div>
      )}

      <section aria-label={t("doors.label")} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Door
          icon={PenLine}
          cat="var(--cat-assistant)"
          title={t("doors.brief.title")}
          body={t("doors.brief.body")}
          onClick={() => {
            inputRef.current?.focus();
            inputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
          }}
        />
        <Door
          icon={Compass}
          cat="var(--cat-data)"
          title={t("doors.envoy.title")}
          body={t("doors.envoy.body")}
          href="/templates"
        />
        <Door
          icon={Landmark}
          cat="var(--cat-automation)"
          title={t("doors.court.title")}
          body={t("doors.court.body")}
          onClick={onWalkCourt ?? tour.openCourt}
        />
        <Door
          icon={Code2}
          cat="var(--cat-analysis)"
          title={t("doors.code.title")}
          body={t("doors.code.body")}
          onClick={onOpenAppBuilder}
        />
      </section>

      <Section title={t("envoys.title")} description={t("envoys.description")}>
        {envoysError && (
          <Alert variant="destructive">
            <AlertDescription>{envoysError}</AlertDescription>
          </Alert>
        )}
        {envoys === null ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            {t("envoys.loading")}
          </div>
        ) : envoys.length === 0 ? (
          <EmptyState icon={Compass} title={t("envoys.emptyTitle")} description={t("envoys.emptyBody")} />
        ) : (
          <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {envoys.map((e) => (
              <li key={e.id}>
                <EnvoyCard envoy={e} opening={opening === e.id} onOpen={() => openEnvoy(e.id)} />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

function Chip({ pressed, onClick, children }: { pressed: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
        pressed
          ? "border-primary bg-primary text-primary-foreground"
          : "border-border bg-card text-foreground hover:bg-accent"
      )}
    >
      {children}
    </button>
  );
}

function Door({
  icon: Icon,
  cat,
  title,
  body,
  onClick,
  href,
  soon = false,
}: {
  icon: typeof Code2;
  cat: string;
  title: string;
  body: string;
  onClick?: () => void;
  href?: string;
  soon?: boolean;
}) {
  const t = useTranslations("brief");
  // Only the title is the real interactive control (a link or a button), with
  // a stretched pseudo-element making the whole card clickable — instead of
  // wrapping the whole card, incl. flow content, inside a <button> (which
  // permits only phrasing content, and gives assistive tech an accessible
  // name built from the entire card's text).
  return (
    <Card cat={cat} interactive className={cn("relative h-full gap-2 p-4")}>
      <div className="flex items-center justify-between gap-2">
        <span className="cat-chip" aria-hidden="true">
          <Icon className="size-4" aria-hidden="true" />
        </span>
        {soon && (
          <span className="state-pill" style={{ ["--state" as string]: "var(--state-idle)" }}>
            {t("doors.soon")}
          </span>
        )}
      </div>
      {href ? (
        <Link
          href={href}
          className="text-sm font-semibold text-foreground after:absolute after:inset-0 after:content-['']"
        >
          {title}
        </Link>
      ) : (
        <button
          type="button"
          onClick={onClick}
          disabled={!onClick}
          className="border-0 bg-transparent p-0 text-start text-sm font-semibold text-foreground shadow-none after:absolute after:inset-0 after:content-[''] disabled:cursor-not-allowed"
        >
          {title}
        </button>
      )}
      <p className="text-xs leading-relaxed text-muted-foreground">{body}</p>
    </Card>
  );
}

const DOT_ORDER: { key: keyof ReadinessSummary; state: ReadinessState }[] = [
  { key: "ready", state: "READY" },
  { key: "missing", state: "MISSING" },
  { key: "waitingOnAdmin", state: "WAITING_ON_ADMIN" },
  { key: "gated", state: "GATED" },
  { key: "simulated", state: "SIMULATED" },
];

function EnvoyCard({ envoy, opening, onOpen }: { envoy: BriefListItem; opening: boolean; onOpen: () => void }) {
  const t = useTranslations("brief");
  const delta = envoy.lastDelta;
  // Same stretched-title pattern as Door above: only the title is a real
  // <button>, not the whole card (which holds a <dl> and other flow content
  // that a <button> may not legally contain).
  return (
    <Card interactive className="relative h-full gap-3 p-4">
      <div className="flex items-start justify-between gap-2">
        <button
          type="button"
          onClick={onOpen}
          disabled={opening}
          className="min-w-0 border-0 bg-transparent p-0 text-start text-sm font-semibold text-foreground shadow-none after:absolute after:inset-0 after:content-['']"
        >
          {envoy.title}
        </button>
        <RungBadge rung={envoy.rung} className="shrink-0" />
      </div>
      <dl className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <dt className="text-muted-foreground">{t("envoys.baseline")}</dt>
          <dd className="tabular-nums text-foreground">
            {envoy.baseline == null ? t("envoys.noBaseline") : pct(envoy.baseline)}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">{t("envoys.lastDelta")}</dt>
          <dd className="tabular-nums text-foreground">
            {/* The list has no noise floor, so a bare number here could read as progress when it is noise; the ledger says which. */}
            {delta == null ? t("envoys.noDelta") : t("envoys.deltaInLedger")}
          </dd>
        </div>
      </dl>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1" role="group" aria-label={t("envoys.readiness")}>
        {DOT_ORDER.filter((d) => (envoy.readinessSummary?.[d.key] ?? 0) > 0).map((d) => (
          <span
            key={d.state}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground"
            title={t(`state.${d.state}`)}
          >
            <span
              className="state-dot"
              style={{ ["--state" as string]: READINESS_STATE_TOKEN[d.state] }}
              aria-hidden="true"
            />
            <span className="tabular-nums">{envoy.readinessSummary?.[d.key]}</span>
            <span className="sr-only">{t(`state.${d.state}`)}</span>
          </span>
        ))}
        {opening && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
      </div>
    </Card>
  );
}
