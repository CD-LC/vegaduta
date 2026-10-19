"use client";

// The shared, static "How this works" diagram: an ordered run of steps drawn
// as HTML nodes joined by connectors, with optional branch outcomes on a step
// (allow / block / needs approval). Same idea as capabilities/AgenticLoop -
// real text nodes, decorative connectors - minus the animation.
//
//   - It lives inside a native <details>, closed by default, so it costs a
//     page one line until someone asks.
//   - The steps are an <ol> and outcomes a nested <ul>; connectors and
//     numbers are aria-hidden, so a screen reader hears the steps in order
//     as a plain numbered list.
//   - Layout follows the CONTAINER, not the viewport: a vertical stepper by
//     default, a horizontal flow once the explainer itself is >= 42rem wide.
//   - Nothing moves except the disclosure chevron, and that transition is
//     dropped under prefers-reduced-motion.
//   - Every string is passed in already translated; the only built-in one is
//     the "How this works" disclosure label (charts.howThisWorks).
import type { ReactElement, ReactNode } from "react";
import { ChevronRight } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { stateColor, type ChartState } from "../charts/chartMath";

export interface FlowOutcome {
  /** Stable React key; defaults to the label. */
  key?: string;
  /** e.g. "Allow", "Block", "Needs approval". Always shown as text. */
  label: string;
  /** What happens next on this branch. */
  description?: string;
  /** Tints the outcome's marker. Default "idle" (neutral). */
  state?: ChartState;
}

export interface FlowStep {
  /** Stable React key; defaults to the index. */
  id?: string;
  title: string;
  description?: string;
  /** Decorative icon shown in the step's node instead of its number. */
  icon?: ReactNode;
  /** Branches leaving this step. */
  outcomes?: FlowOutcome[];
}

export interface FlowExplainerProps {
  steps: FlowStep[];
  /** Disclosure label override (already translated). Default: "How this works". */
  title?: string;
  /** Optional sentence above the steps. */
  intro?: ReactNode;
  defaultOpen?: boolean;
  /** Force the vertical stepper at every width. Default false. */
  vertical?: boolean;
  className?: string;
}

/** Renders nothing when there are no steps. */
export default function FlowExplainer({
  steps,
  title,
  intro,
  defaultOpen = false,
  vertical = false,
  className,
}: FlowExplainerProps): ReactElement | null {
  const t = useTranslations("charts");
  const list = Array.isArray(steps) ? steps : [];
  if (list.length === 0) return null;
  const heading = title ?? t("howThisWorks");
  const wide = !vertical;

  return (
    <details open={defaultOpen || undefined} className={cn("group/flow min-w-0 rounded-lg border border-border bg-card/40", className)}>
      <summary className="flex cursor-pointer list-none items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium text-foreground [&::-webkit-details-marker]:hidden">
        <ChevronRight
          aria-hidden="true"
          className="size-4 shrink-0 text-muted-foreground transition-transform duration-150 group-open/flow:rotate-90 motion-reduce:transition-none"
        />
        {heading}
      </summary>
      <div className="@container border-t border-border px-3 py-3">
        {intro && <p className="mb-3 text-sm text-muted-foreground">{intro}</p>}
        <ol aria-label={heading} className={cn("m-0 flex list-none flex-col p-0", wide && "@2xl:flex-row @2xl:gap-0")}>
          {list.map((step, index) => {
            const last = index === list.length - 1;
            return (
              <li
                key={step.id ?? index}
                data-flow-step=""
                className={cn("flex min-w-0 gap-3", wide && "@2xl:flex-1 @2xl:basis-0 @2xl:flex-col @2xl:gap-2")}
              >
                {/* Rail: the numbered node and the connector to the next step. Decorative. */}
                <div aria-hidden="true" className={cn("flex shrink-0 flex-col items-center", wide && "@2xl:flex-row")}>
                  <span className="flex size-7 shrink-0 items-center justify-center rounded-full border border-border bg-background text-xs font-semibold tabular-nums text-foreground">
                    {step.icon ?? index + 1}
                  </span>
                  {!last && (
                    <span
                      data-flow-connector=""
                      className={cn(
                        "my-1 w-px flex-1 bg-border",
                        wide && "@2xl:mx-1 @2xl:my-0 @2xl:h-px @2xl:w-auto"
                      )}
                    />
                  )}
                </div>
                <div className={cn("min-w-0 flex-1 pb-4", last && "pb-0", wide && "@2xl:pb-0 @2xl:pr-4")}>
                  <p className="pt-1 text-sm font-medium leading-snug text-foreground">{step.title}</p>
                  {step.description && <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{step.description}</p>}
                  {step.outcomes && step.outcomes.length > 0 && (
                    <ul className="m-0 mt-2 list-none space-y-1.5 p-0">
                      {step.outcomes.map((outcome) => (
                        <li key={outcome.key ?? outcome.label} data-flow-outcome="" className="flex min-w-0 items-start gap-2 text-xs">
                          <span
                            aria-hidden="true"
                            className="mt-1 inline-block h-2 w-3 shrink-0 rounded-sm border-l-2"
                            style={{ borderColor: stateColor(outcome.state), background: `color-mix(in srgb, ${stateColor(outcome.state)} 22%, transparent)` }}
                          />
                          <span className="min-w-0 leading-relaxed text-muted-foreground">
                            <span className="font-semibold text-foreground">{outcome.label}</span>
                            {outcome.description ? ` - ${outcome.description}` : ""}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      </div>
    </details>
  );
}
