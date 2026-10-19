"use client";

// The "choose a model" step of the local-model wizard: this machine, the whole catalog in two groups
// ("Fits your machine" / "Needs more memory"), a detail card for the selected row, and the explicit
// warning a too-big model has to be pulled through. Pure presentation over wizardMachine's selectors.
import Link from "next/link";
import { useMemo } from "react";
import { AlertTriangle, Cpu, ExternalLink, RefreshCw } from "lucide-react";
import type { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import type { LocalModelChoice, LocalModelRecommendation, ToolCallingGrade } from "../../lib/api/localModels";
import { Alert } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { OLLAMA_INSTALL_LINKS, detectOs, fmtGb } from "./ollamaInstall";
import {
  canStart,
  exceedsRam,
  fitsMachine,
  groupChoices,
  isInstalled,
  selectedChoice,
  speedHint,
  type WizardState,
} from "./wizardMachine";

export type T = ReturnType<typeof useTranslations>;

export const GRADE_KEY: Record<ToolCallingGrade, "gradeNative" | "gradeConstrained" | "gradeNone"> = {
  native: "gradeNative",
  constrained: "gradeConstrained",
  none: "gradeNone",
};
const GRADE_HINT_KEY: Record<ToolCallingGrade, "gradeNativeHint" | "gradeConstrainedHint" | "gradeNoneHint"> = {
  native: "gradeNativeHint",
  constrained: "gradeConstrainedHint",
  none: "gradeNoneHint",
};
const SPEED_KEY = { comfortable: "speedComfortable", tight: "speedTight", over: "speedOver" } as const;

export function GradeBadge({ grade, t }: { grade: ToolCallingGrade; t: T }) {
  return (
    <Badge variant={grade === "native" ? "accent" : "secondary"} title={t(GRADE_HINT_KEY[grade])}>
      {t(GRADE_KEY[grade])}
    </Badge>
  );
}

export function ReadyPanel({
  state,
  onSelect,
  onAcknowledge,
  onStart,
  onRecheck,
  t,
}: {
  state: WizardState;
  onSelect: (tag: string) => void;
  onAcknowledge: (value: boolean) => void;
  onStart: () => void;
  onRecheck: () => void;
  t: T;
}) {
  const rec = state.recommendation!;
  const hw = rec.hardware;
  const { fits, needsMore } = groupChoices(rec);
  const selected = selectedChoice(state);
  const installed = isInstalled(rec, state.selectedTag);
  const tooBig = !!selected && exceedsRam(rec, selected);
  const os = useMemo(() => (typeof navigator === "undefined" ? null : detectOs(navigator.userAgent)), []);
  const smallestMissing = needsMore.length ? Math.min(...needsMore.map((c) => c.ramGb)) : 0;

  const rowProps = (c: LocalModelChoice) => ({
    choice: c,
    recommended: c.tag === rec.recommended.tag,
    installed: rec.ollama.installedTags.includes(c.tag),
    serving: c.tag === rec.servingModel,
    checked: c.tag === state.selectedTag,
    onSelect: () => onSelect(c.tag),
    t,
  });

  return (
    <div className="flex flex-col gap-5">
      <section aria-labelledby="lm-hw" className="flex flex-col gap-1.5">
        <h3 id="lm-hw" className="flex items-center gap-1.5 text-sm font-semibold">
          <Cpu size={14} aria-hidden="true" /> {t("hardwareHeading")}
        </h3>
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground">
          <li>{t("hardwareRam", { gb: fmtGb(hw.ramGb) })}</li>
          <li>{t("hardwareCores", { count: hw.cpuCores })}</li>
          <li>
            {hw.appleSilicon
              ? t("hardwareAppleSilicon")
              : hw.gpu
                ? t("hardwareGpu", { name: hw.gpu.name, vram: fmtGb(hw.gpu.vramGb) })
                : t("hardwareNoGpu")}
          </li>
          {rec.budgetGb > 0 && <li data-testid="lm-budget">{t("hardwareBudget", { gb: fmtGb(rec.budgetGb) })}</li>}
        </ul>
      </section>

      {!rec.ollama.reachable && <OllamaMissing rec={rec} os={os} onRecheck={onRecheck} t={t} />}

      <fieldset className="flex flex-col gap-2" data-testid="lm-choices">
        <legend className="mb-1 text-sm font-semibold">{t("choiceLegend")}</legend>
        {rec.servingModel && (
          <p className="mb-1 text-xs text-muted-foreground" data-testid="lm-serving">
            {t("servingNow", { tag: rec.servingModel })}{" "}
            <Link href="/troubleshoot" className="text-primary hover:underline">
              {t("switchOnTroubleshoot")}
            </Link>
          </p>
        )}
        {fits.length > 0 && (
          <div className="flex flex-col gap-2" data-testid="lm-group-fits">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("groupFits")}</h4>
            {fits.map((c) => <ChoiceRow key={c.tag} {...rowProps(c)} />)}
          </div>
        )}
        {needsMore.length > 0 && (
          <div className="mt-2 flex flex-col gap-2" data-testid="lm-group-needs-more">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {t("groupNeedsMore", { need: fmtGb(smallestMissing), have: fmtGb(hw.ramGb) })}
            </h4>
            {needsMore.map((c) => <ChoiceRow key={c.tag} {...rowProps(c)} />)}
          </div>
        )}
      </fieldset>

      {selected && <DetailCard rec={rec} choice={selected} t={t} />}

      {tooBig && selected && (
        <Alert variant="warning" data-testid="lm-ram-warning">
          <AlertTriangle aria-hidden="true" />
          <div className="flex flex-col gap-2">
            <p>{t("ramWarning", { need: fmtGb(selected.ramGb), have: fmtGb(hw.ramGb) })}</p>
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-0.5 accent-[var(--primary)]"
                checked={state.acknowledgedRisk}
                onChange={(e) => onAcknowledge(e.target.checked)}
                data-testid="lm-ram-acknowledge"
              />
              <span>{t("ramAcknowledge")}</span>
            </label>
          </div>
        </Alert>
      )}

      <div>
        <Button onClick={onStart} disabled={!canStart(state)}>
          {installed ? t("startInstalled") : tooBig ? t("startAnyway") : t("startDownload")}
        </Button>
      </div>
    </div>
  );
}

function OllamaMissing({
  rec,
  os,
  onRecheck,
  t,
}: {
  rec: LocalModelRecommendation;
  os: ReturnType<typeof detectOs>;
  onRecheck: () => void;
  t: T;
}) {
  return (
    <Alert variant="warning" data-testid="lm-ollama-missing">
      <AlertTriangle aria-hidden="true" />
      <div className="flex flex-col gap-2">
        <p className="font-semibold">{t("ollamaMissingTitle")}</p>
        <p>{t("ollamaMissingBody", { url: rec.ollama.baseUrl })}</p>
        <ul className="flex flex-wrap gap-2">
          {(["mac", "windows", "linux"] as const).map((key) => (
            <li key={key}>
              <Button asChild size="sm" variant={os === key ? "default" : "outline"}>
                <a href={OLLAMA_INSTALL_LINKS[key]} target="_blank" rel="noopener noreferrer">
                  {t("installFor", { os: t(key === "mac" ? "osMac" : key === "windows" ? "osWindows" : "osLinux") })}
                  {os === key ? ` (${t("installDetected")})` : ""}
                  <ExternalLink size={12} aria-hidden="true" />
                </a>
              </Button>
            </li>
          ))}
        </ul>
        <div>
          <Button size="sm" variant="outline" onClick={onRecheck}>
            <RefreshCw size={14} aria-hidden="true" /> {t("checkAgain")}
          </Button>
        </div>
      </div>
    </Alert>
  );
}

function ChoiceRow({
  choice,
  recommended,
  installed,
  serving,
  checked,
  onSelect,
  t,
}: {
  choice: LocalModelChoice;
  recommended: boolean;
  installed: boolean;
  serving: boolean;
  checked: boolean;
  onSelect: () => void;
  t: T;
}) {
  return (
    <label
      className={cn(
        "flex cursor-pointer items-start gap-3 rounded-lg border p-3 text-sm focus-within:ring-2 focus-within:ring-ring",
        checked ? "border-primary bg-accent" : "border-border",
      )}
    >
      <input
        type="radio"
        name="local-model"
        value={choice.tag}
        checked={checked}
        onChange={onSelect}
        className="mt-1 accent-[var(--primary)]"
      />
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="break-all font-mono font-medium text-foreground">{choice.tag}</span>
          {recommended && <Badge variant="default">{t("recommendedBadge")}</Badge>}
          {installed && <Badge variant="outline">{t("installedBadge")}</Badge>}
          {serving && <Badge variant="default">{t("servingBadge")}</Badge>}
          <GradeBadge grade={choice.toolCalling} t={t} />
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">
          {choice.paramsB > 0 ? `${t("paramsShort", { b: fmtGb(choice.paramsB) })} - ` : ""}
          {installed ? "" : `${t("downloadSize", { gb: fmtGb(choice.downloadGb) })} - `}
          {t("ramNeed", { gb: fmtGb(choice.ramGb) })}
        </span>
        {choice.note && <span className="text-xs text-muted-foreground">{choice.note}</span>}
      </span>
    </label>
  );
}

/** Everything the catalog knows about the selected model, against what this machine has. */
function DetailCard({ rec, choice, t }: { rec: LocalModelRecommendation; choice: LocalModelChoice; t: T }) {
  const hw = rec.hardware;
  const fits = fitsMachine(rec, choice);
  const gpu = hw.gpu && !hw.appleSilicon ? hw.gpu : null;
  const items: Array<[string, string]> = [
    [t("detailSize"), choice.paramsB > 0 ? t("detailParams", { b: fmtGb(choice.paramsB), family: choice.family }) : choice.family],
    [t("detailDownload"), t("detailDownloadValue", { gb: fmtGb(choice.downloadGb) })],
    [
      t("detailMemory"),
      t(fits ? "detailMemoryFits" : "detailMemoryShort", {
        need: fmtGb(choice.ramGb),
        have: fmtGb(hw.ramGb),
        budget: fmtGb(rec.budgetGb > 0 ? rec.budgetGb : hw.ramGb),
      }),
    ],
    [
      t("detailVram"),
      choice.vramGb > 0
        ? gpu
          ? t("detailVramGpu", { need: fmtGb(choice.vramGb), have: fmtGb(gpu.vramGb), name: gpu.name })
          : t("detailVramNoGpu", { need: fmtGb(choice.vramGb) })
        : t("detailUnknown"),
    ],
    [t("detailContext"), choice.contextK > 0 ? t("detailContextValue", { k: String(choice.contextK) }) : t("detailUnknown")],
    [t("toolCallingLabel"), `${t(GRADE_KEY[choice.toolCalling])}. ${t(GRADE_HINT_KEY[choice.toolCalling])}`],
    [t("detailThinking"), t(choice.thinking ? "detailThinkingYes" : "detailThinkingNo")],
    [t("detailLicence"), choice.licence || t("detailUnknown")],
    [t("detailSpeed"), t(SPEED_KEY[speedHint(rec, choice)])],
  ];
  if (choice.bestFor) items.push([t("detailBestFor"), choice.bestFor]);
  return (
    <section
      aria-labelledby="lm-detail"
      className="rounded-lg border border-border bg-muted/30 p-4"
      data-testid="lm-detail"
      data-tag={choice.tag}
    >
      <h3 id="lm-detail" className="mb-2 text-sm font-semibold">
        {t("detailTitle", { tag: choice.tag })}
      </h3>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-[max-content_1fr]">
        {items.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="m-0 text-foreground">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
