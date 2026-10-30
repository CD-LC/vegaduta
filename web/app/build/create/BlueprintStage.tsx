"use client";

import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleAlert,
  GitBranch,
  Plug,
  Rocket,
  ShieldCheck,
  Sparkles,
  XCircle,
} from "lucide-react";
import Link from "next/link";
import { AnimatePresence, motion } from "motion/react";
import { cn } from "../../lib/utils";
import { Button } from "../../components/ui/button";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { FlowEditor, LevelPicker, SettingsSections } from "../BlueprintView";
import PoweredByChip from "../../components/PoweredByChip";
import BlueprintJsonTools from "../BlueprintJsonTools";
import { radioGroupKeyDown } from "../radioNav";
import GithubConnectCard from "../components/GithubConnectCard";
import type { AppBuilderGithubStatus } from "../lib";
import type { Blueprint, Level, OutputKind, ReadinessItem } from "../../lib/build/blueprint";
import type { DraftEngine } from "./createShared";
import { useTranslations } from "../../lib/i18n";

/** Stage 2: review and edit the blueprint, then build. */
export default function BlueprintStage({
  bp,
  setBp,
  level,
  setLevel,
  switchOutput,
  draftEngine,
  draftNote,
  validation: v,
  showProblems,
  problemsRef,
  reduceMotion,
  onImport: importBlueprint,
  gh,
  onGhStatusChange,
  modelItem,
  factsLoaded,
  addOns,
  modelBlocked,
  providerLabel,
  onBack,
  onBuild: tryBuild,
}: {
  bp: Blueprint;
  setBp: (next: Blueprint) => void;
  level: Level;
  setLevel: (level: Level) => void;
  switchOutput: (out: OutputKind) => void;
  draftEngine: DraftEngine | null;
  draftNote: string | null;
  validation: { errors: string[]; warnings: string[] };
  showProblems: boolean;
  problemsRef: React.RefObject<HTMLDivElement | null>;
  reduceMotion: boolean;
  onImport: (next: Blueprint) => void;
  gh: AppBuilderGithubStatus | null;
  onGhStatusChange: (status: AppBuilderGithubStatus | null) => void;
  modelItem: ReadinessItem | undefined;
  factsLoaded: boolean;
  addOns: ReadinessItem[];
  modelBlocked: boolean;
  providerLabel: string;
  onBack: () => void;
  onBuild: () => void;
}) {
    const tHC = useTranslations("hcBuildCreateBlueprintStage");
  return (
    <motion.div
      key="blueprint"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -6 }}
      transition={{ duration: 0.2 }}
      className="space-y-5"
    >
      {/* Summary: the whole thing in one glance */}
      <div className="relative overflow-hidden rounded-2xl border border-border bg-card p-5">
        <div
          className="pointer-events-none absolute -right-12 -top-12 size-44 rounded-full bg-primary/10 blur-3xl"
          aria-hidden
        />
        <div className="relative flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-semibold uppercase tracking-wide text-primary">{tHC("s18")}</p>
            <h2 className="mt-0.5 truncate text-2xl font-semibold tracking-tight text-foreground">
              {bp.output === "agent" ? bp.agent.name || "Your agent" : bp.app.name || "Your app"}
            </h2>
            {bp.goal && <p className="mt-1 max-w-2xl text-sm text-muted-foreground">&ldquo;{bp.goal}&rdquo;</p>}
            <div className="mt-3 flex flex-wrap gap-1.5">
              {bp.output === "agent" ? (
                <>
                  <Badge variant="accent">
                    <ShieldCheck aria-hidden="true" /> {tHC("s17")}
                  </Badge>
                  <Badge variant="secondary">{bp.agent.tone} tone</Badge>
                  {bp.agent.knowledgeBase && <Badge variant="secondary">{tHC("s16")}</Badge>}
                  {bp.workflow && <Badge variant="secondary">{bp.workflow.steps.length}{tHC("s15")}</Badge>}
                </>
              ) : (
                <Badge variant="accent">
                  <GitBranch aria-hidden="true" /> {tHC("s14")}
                </Badge>
              )}
              {/* Which engine wrote this blueprint - on-device model
               * (free, private) or the deterministic recipes. Same
               * disclosure the draftNote Alert explains in words. */}
              {draftEngine && <PoweredByChip source={draftEngine.kind} model={draftEngine.model} prefix="Drafted by" />}
            </div>
          </div>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group */}
            <div
              className="inline-flex rounded-lg border border-border p-0.5 text-xs"
              role="radiogroup"
              aria-label="Output"
              onKeyDown={radioGroupKeyDown}
            >
              {(["agent", "app"] as const).map((o) => (
                <button
                  key={o}
                  type="button"
                  role="radio"
                  aria-checked={bp.output === o}
                  tabIndex={bp.output === o ? 0 : -1}
                  onClick={() => switchOutput(o)}
                  className={cn(
                    "rounded-md px-2.5 py-1 transition-colors",
                    bp.output === o
                      ? "bg-primary text-primary-foreground"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  {o === "agent" ? "Agent" : "App"}
                </button>
              ))}
            </div>
            <LevelPicker level={level} output={bp.output} onChange={setLevel} />
          </div>
        </div>
      </div>

      {draftNote && (
        <Alert>
          <Sparkles aria-hidden="true" className="size-4" />
          <AlertDescription>{draftNote}</AlertDescription>
        </Alert>
      )}

      {/* Blocking problems: shown when Build is pressed (and always if present after that) */}
      <div ref={problemsRef}>
        <AnimatePresence>
          {v.errors.length > 0 && showProblems && (
            <motion.div
              initial={{ opacity: 0, x: -6 }}
              animate={{ opacity: 1, x: reduceMotion ? 0 : [0, -6, 6, -4, 4, 0] }}
              exit={{ opacity: 0 }}
              transition={{ duration: reduceMotion ? 0 : 0.4 }}
            >
              <Alert variant="destructive">
                <XCircle aria-hidden="true" className="size-4" />
                <AlertDescription>
                  <p className="font-semibold">
                    Fix {v.errors.length === 1 ? "this" : `these ${v.errors.length} things`} {tHC("s13")}
                  </p>
                  <ul className="mt-1 list-disc space-y-0.5 ps-4">
                    {v.errors.map((e, i) => (
                      <li key={i}>{e}</li>
                    ))}
                  </ul>
                </AlertDescription>
              </Alert>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      {v.warnings.length > 0 && (
        <Alert>
          <CircleAlert aria-hidden="true" className="size-4" />
          <AlertDescription>
            <ul className="list-disc space-y-0.5 ps-4">
              {v.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      <BlueprintJsonTools bp={bp} onImport={importBlueprint} />

      <SettingsSections bp={bp} level={level} onChange={setBp} />
      {bp.output === "agent" && <FlowEditor bp={bp} onChange={setBp} />}

      {/* Apps are committed to the person's own repository, so GitHub is the one required connection here. */}
      {bp.output === "app" && (
        <section className="space-y-3 rounded-2xl border border-border bg-card p-4" aria-label={tHC("s12")}>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-foreground">{tHC("s12")}</h3>
            <Badge variant={gh?.connected ? "accent" : "outline"}>
              {gh?.connected ? "Connected" : "Required for apps"}
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground">
            {tHC("s11")} <span className="font-medium text-foreground">{tHC("s10")}</span> {tHC("s9")}
          </p>
          <GithubConnectCard onStatusChange={onGhStatusChange} />
        </section>
      )}

      {/* Readiness: one thing is required (a model). Everything else is optional and says so. */}
      {bp.output === "agent" && modelItem && (
        <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
          <h3 className="text-sm font-semibold text-foreground">{tHC("s8")}</h3>

          <div
            className={cn(
              "flex items-start gap-3 rounded-xl border p-3",
              modelItem.state === "blocked" && factsLoaded
                ? "border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_10%,transparent)]"
                : "border-[color-mix(in_srgb,var(--state-success)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_10%,transparent)]"
            )}
          >
            {modelItem.state === "blocked" && factsLoaded ? (
              <CircleAlert aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-[var(--state-warning)]" />
            ) : (
              <Check aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-[var(--state-success)]" />
            )}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-foreground">
                {factsLoaded ? modelItem.label : "Checking for an AI model…"}
                <Badge variant="outline" className="ms-2 align-middle">
                  Required
                </Badge>
              </p>
              <p className="text-xs text-muted-foreground">{factsLoaded ? modelItem.why : "One moment."}</p>
            </div>
            {modelItem.state === "blocked" && factsLoaded && modelItem.href && (
              <Button asChild size="sm">
                <Link href={modelItem.href}>{tHC("s7")}</Link>
              </Button>
            )}
          </div>

          {addOns.length > 0 && (
            <div>
              <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {tHC("s6")}{" "}
                <span className="font-normal normal-case">{tHC("s5")}</span>
              </p>
              <ul className="grid gap-2 sm:grid-cols-2">
                {addOns.map((it) => (
                  <li key={it.id} className="flex items-start gap-2.5 rounded-xl border border-border p-3">
                    <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                      <Plug aria-hidden="true" className="size-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-medium text-foreground">{it.label}</span>
                      <span className="block text-xs leading-relaxed text-muted-foreground">{it.why}</span>
                      {it.href && (
                        <Link
                          href={it.href}
                          className="mt-1 inline-flex items-center gap-1 text-xs font-medium text-primary underline-offset-2 hover:underline"
                        >
                          {tHC("s4")} <ArrowRight aria-hidden="true" className="size-3" />
                        </Link>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {/* Sticky action bar - the primary action is always in reach */}
      <div className="sticky bottom-3 z-10 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card/95 p-3 shadow-lg backdrop-blur">
        <Button variant="ghost" onClick={() => onBack()}>
          <ArrowLeft aria-hidden="true" className="size-4" /> Back
        </Button>
        <div className="flex items-center gap-3">
          <span className="hidden text-xs text-muted-foreground sm:inline">
            {bp.output === "agent"
              ? providerLabel || "Model chosen when it is built"
              : "Lands in your Git as a pull request"}
          </span>
          <Button size="lg" onClick={tryBuild}>
            {bp.output === "agent" ? (
              <Rocket aria-hidden="true" className="size-4" />
            ) : (
              <GitBranch aria-hidden="true" className="size-4" />
            )}
            {bp.output === "agent" ? "Build my agent" : "Build my app"}
          </Button>
        </div>
      </div>
      {modelBlocked && (
        <p className="text-center text-xs text-muted-foreground">
          {tHC("s3")}{" "}
          <Link href="/providers" className="text-primary underline-offset-2 hover:underline">
            {tHC("s2")}
          </Link>{" "}
          {tHC("s1")}
        </p>
      )}
    </motion.div>
  );
}
