"use client";

import Link from "next/link";
import { ArrowRight, Check, Copy, Globe, Loader2, Play, Share2 } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { useJourney } from "../../../lib/journey";
import { sendLandingEvent } from "../../demo-landing/landingBeacon";
import { Button } from "../../ui/button";
import { absoluteImageUrl, type MagicPlan, type MagicRunResult } from "../magicClient";

/** Run + share: run the planned workflow, publish its public link, and show the run's result. */
export default function RunSharePanel({
  plan,
  running,
  busy,
  onRun,
  publishing,
  onPublish,
  shareUrl,
  runCap,
  copied,
  copyText,
  result,
}: {
  plan: MagicPlan;
  running: boolean;
  busy: boolean;
  onRun: () => void;
  publishing: boolean;
  onPublish: () => void;
  shareUrl: string | null;
  runCap: number;
  copied: "link" | "mermaid" | null;
  copyText: (text: string, which: "link" | "mermaid") => void;
  result: MagicRunResult | null;
}) {
  const t = useTranslations("landing");
  // Conversion seam for the publish-success CTA below (2026-09-12).
  const journey = useJourney();
  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-2xl border border-border/60 bg-card/70 p-5 backdrop-blur">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground">{plan.title}</h3>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button onClick={onRun} disabled={busy} className="gap-2">
            {running ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin shrink-0" aria-hidden />
                {t("magic_running")}
              </>
            ) : (
              <>
                <Play className="h-4 w-4 shrink-0" aria-hidden />
                {t("magic_runNow")}
              </>
            )}
          </Button>
          <Button variant="outline" onClick={onPublish} disabled={publishing} className="gap-2">
            {publishing ? (
              <Loader2 className="h-4 w-4 animate-spin shrink-0" aria-hidden />
            ) : (
              <Share2 className="h-4 w-4 shrink-0" aria-hidden />
            )}
            {t("magic_getPublicLink")}
          </Button>
        </div>

        {shareUrl ? (
          <div className="mt-4 rounded-xl border border-primary/25 bg-primary/5 p-3">
            <p className="flex items-center gap-1.5 text-xs font-medium text-primary">
              <Globe className="h-3.5 w-3.5 shrink-0" aria-hidden />
              {t("magic_anyoneCanRun")}
            </p>
            <div className="mt-2 flex items-center gap-2">
              <code className="min-w-0 flex-1 truncate rounded-lg bg-background/70 px-2.5 py-1.5 text-xs">
                {shareUrl}
              </code>
              <button
                type="button"
                onClick={() => copyText(shareUrl, "link")}
                aria-label={t("magic_copyLinkAria")}
                className="rounded-lg border-0 bg-transparent p-1.5 text-muted-foreground transition hover:text-foreground"
              >
                {copied === "link" ? (
                  <Check className="h-4 w-4 shrink-0" aria-hidden />
                ) : (
                  <Copy className="h-4 w-4 shrink-0" aria-hidden />
                )}
              </button>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">{t("magic_limitedNote", { cap: runCap })}</p>
            {/* Conversion exit (2026-09-12). This is the highest-intent
                moment anywhere on the landing page — the visitor has
                just described an outcome, watched a real plan get built,
                run it, and published a working public link — and until
                now it ended here, with no way to keep any of it. The
                published workflow lives in the shared demo tenant and
                the link is capped at {runCap} runs, so "keep it" is a
                real thing the account does, not a slogan.
                journey.signupHref carries the ?demo=<token> seam, so if
                this browser also holds a /try sandbox its agents come
                across too (see lib/journey.ts). */}
            <Link
              href={journey.signupHref}
              onClick={() => sendLandingEvent("magic_signup_click")}
              className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-primary px-3.5 py-2 text-xs font-semibold text-primary-foreground transition-opacity hover:opacity-90"
            >
              {t("magic_keepThisCta")}
              <ArrowRight className="h-3.5 w-3.5 shrink-0" aria-hidden />
            </Link>
          </div>
        ) : null}
      </div>

      {result ? (
        <div className="rounded-2xl border border-border/60 bg-card/70 p-5 backdrop-blur">
          <h4 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
            {t("magic_resultLabel")}
            {result.simulated ? (
              <span className="ms-2 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-amber-500">
                {t("magic_sampleBadge")}
              </span>
            ) : null}
          </h4>
          {result.type === "image" && result.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- runtime API origin, not a static asset
            <img
              src={absoluteImageUrl(result.imageUrl)}
              alt={t("magic_generatedAlt")}
              className="w-full rounded-xl border border-border/50"
            />
          ) : (
            <div className="max-h-80 overflow-auto whitespace-pre-wrap text-sm leading-relaxed">{result.text}</div>
          )}
        </div>
      ) : null}
    </div>
  );
}
