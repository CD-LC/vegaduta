import { useId } from "react";
import { motion } from "motion/react";
import { Check, X } from "lucide-react";
import { TourIllustration, type TourIllustrationName } from "../TourIllustrations";
import { Button } from "../../ui/button";
import { HOLE_PAD, type Rect } from "./geometry";

/* ------------------------------------------------------------------ */
/* Spotlight                                                           */
/* ------------------------------------------------------------------ */

/**
 * A real cut-out, not the old `box-shadow: 0 0 0 9999px` trick. The dim layer
 * is one full-viewport rect painted through a mask (white everywhere, black
 * over the target), so the spotlit element is untouched - fully opaque, fully
 * crisp - instead of being seen through a translucent sheet. The whole SVG is
 * pointer-events:none so the app underneath stays clickable.
 */
export function Spotlight({ rect, reduced }: { rect: Rect; reduced: boolean }) {
  // Unique per instance: two spotlights can be mounted at once while
  // AnimatePresence cross-fades steps, and duplicate mask ids in one document
  // resolve to whichever came first - the outgoing one would steal the hole.
  const maskId = `tour-spotlight-${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  // attrX/attrY, not x/y: motion treats bare `x`/`y` as TRANSFORM values even
  // on SVG elements (that is exactly why attrX/attrY exist), and a transformed
  // rect inside a <mask> is a different, browser-dependent thing from a rect
  // whose own geometry moved. These animate the real SVG attributes.
  const box = {
    attrX: rect.left - HOLE_PAD,
    attrY: rect.top - HOLE_PAD,
    width: rect.width + HOLE_PAD * 2,
    height: rect.height + HOLE_PAD * 2,
  };
  // The light travels between targets; under reduced motion it just lands.
  const travel = reduced
    ? ({ duration: 0 } as const)
    : ({ type: "spring", stiffness: 340, damping: 34, mass: 0.7 } as const);

  return (
    <motion.svg
      className="pointer-events-none fixed inset-0 z-[1150] h-full w-full"
      aria-hidden="true"
      initial={reduced ? false : { opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={reduced ? { duration: 0 } : { duration: 0.18 }}
    >
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="100%" height="100%">
          <rect x="0" y="0" width="100%" height="100%" fill="white" />
          <motion.rect rx={10} fill="black" initial={false} animate={box} transition={travel} />
        </mask>
      </defs>
      {/* Neutral scrim, same value the app's other modals use (bg-black/50-60);
          correct in both modes, so no token needed - and no hex literal. */}
      <rect x="0" y="0" width="100%" height="100%" fill="rgb(0 0 0 / 0.55)" mask={`url(#${maskId})`} />
      {/* Soft glow, then the crisp 1.5px ring. Two stroked rects rather than a
          filter: the app's overlay perf contract is "no blur() filters". */}
      <motion.rect
        rx={10}
        fill="none"
        stroke="var(--primary)"
        strokeWidth={7}
        strokeOpacity={0.18}
        initial={false}
        animate={box}
        transition={travel}
      />
      <motion.rect
        rx={10}
        fill="none"
        stroke="var(--primary)"
        strokeWidth={1.5}
        initial={false}
        animate={box}
        transition={travel}
      />
    </motion.svg>
  );
}

/* ------------------------------------------------------------------ */
/* Card parts                                                          */
/* ------------------------------------------------------------------ */

/**
 * The illustration header of a tour card.
 *
 * TWO BUGS ARE FIXED HERE, and both are easy to reintroduce:
 *
 * 1. SIZE. This used to be a fixed-height box (`h-28`/`h-36`) with
 *    `className="h-full w-auto max-w-full"` pushed onto the illustration - but
 *    TourIllustration's root <svg> already carries `block h-auto w-full`, so
 *    four conflicting utilities landed on one element. Tailwind resolves those
 *    by EMISSION ORDER, not by which one was written last (`.h-auto` then
 *    `.h-full`, `.w-auto` then `.w-full`), so `h-full w-full` won, and
 *    `preserveAspectRatio="xMidYMid meet"` letterboxed the 2:1 drawing inside
 *    a 320x112 box: every illustration rendered at 224x112 with ~68px of dead
 *    tint down each side. The fix is to stop fighting the svg at all - pass it
 *    no size classes, give it a width, and let the 2:1 viewBox set the height
 *    (240x120 viewBox -> a 320px-wide card draws 320x160). `max-w` caps the
 *    width because the drawings go thin-stroked above ~420px (see
 *    TourIllustration's sizing contract) and because a docked bottom sheet on
 *    a 1000px-wide tablet would otherwise ask for a 500px-tall picture.
 *
 * 2. GROUND. There used to be a `linear-gradient(160deg, primary 16% ...)`
 *    behind the drawing. TourIllustrations' colour vocabulary states that
 *    `--card` is used ONLY as a knockout, on the assumption that the drawing
 *    sits on `--card` - so every `fill={CARD}` shape (knowledge's front sheet,
 *    channels' widget and email panels, agents' model chip, monitor's data
 *    points) painted an opaque untinted patch over that gradient, worst in the
 *    upper-left where the 160deg ramp was strongest. The band also mixed
 *    `in srgb` while the illustrations mix `in oklab`, so the two accents
 *    followed different hue ramps. The ground is now plain `--card` - the card
 *    shell's own `bg-card`, no background of its own - which is exactly what
 *    the drawings are drawn for. If a tint is ever wanted back it belongs
 *    INSIDE the illustration, behind the knockouts, in oklab.
 */
export function IllustrationBand({ name, tall = false }: { name: TourIllustrationName; tall?: boolean }) {
  return (
    <div className={"overflow-hidden " + (tall ? "px-6 pt-6" : "px-5 pt-4")}>
      <div className={"mx-auto w-full " + (tall ? "max-w-[400px]" : "max-w-[360px]")}>
        <TourIllustration name={name} />
      </div>
    </div>
  );
}

export function Points({ one, two }: { one: string; two: string }) {
  return (
    <ul className="mt-3 space-y-1.5">
      {[one, two]
        .filter((point) => point.trim() !== "")
        .map((point, i) => (
          <li key={i} className="flex items-start gap-2 text-[13px] leading-snug text-foreground">
            <span
              className="mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full text-primary"
              style={{ background: "color-mix(in srgb, var(--primary) 18%, transparent)" }}
              aria-hidden="true"
            >
              <Check className="size-2.5" aria-hidden="true" />
            </span>
            <span>{point}</span>
          </li>
        ))}
    </ul>
  );
}

/**
 * Segmented progress rail. Replaces v1's bare <span> dots, which were
 * invisible and unreachable to keyboard and assistive tech. Every segment is
 * a real button: both visited and unvisited steps are jumpable, because this
 * is a tour, not a wizard - nothing downstream depends on step order.
 */
export function ProgressRail({
  total,
  index,
  onJump,
  label,
  jumpLabel,
}: {
  total: number;
  index: number;
  onJump: (i: number) => void;
  label: string;
  jumpLabel: (n: number) => string;
}) {
  return (
    <nav aria-label={label} className="flex items-center gap-1">
      {Array.from({ length: total }).map((_, i) => (
        <button
          key={i}
          type="button"
          onClick={() => onJump(i)}
          aria-label={jumpLabel(i + 1)}
          aria-current={i === index ? "step" : undefined}
          // border-0/bg-transparent/p-0/shadow-none neutralize the global
          // `button {}` chrome in globals.css; the bar itself is the child.
          className="flex h-5 cursor-pointer items-center rounded-full border-0 bg-transparent p-0 shadow-none outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
        >
          <span
            className={
              "block h-1.5 rounded-full transition-all " +
              (i === index ? "w-5 bg-primary" : i < index ? "w-2 bg-primary/45" : "w-2 bg-border")
            }
          />
        </button>
      ))}
    </nav>
  );
}

export function CloseButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={label}
      onClick={onClick}
      className="absolute right-2 top-2 size-7 text-muted-foreground hover:text-foreground"
    >
      <X className="size-4" aria-hidden="true" />
    </Button>
  );
}

