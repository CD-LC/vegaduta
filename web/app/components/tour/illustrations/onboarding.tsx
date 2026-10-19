import type { ReactElement } from "react";
import { LINE, KNOCKOUT, ACCENT, ACCENT_MID, ACCENT_SOFT, ACCENT_FAINT, FILL, FILL_STRONG, dashFlowCss, Canvas, ArrowRight, AgentGlyph, type IllustrationProps } from "./shared";

/* ------------------------------------------------------------------ *
 * 1. recipe - the build order, in four beats
 * ------------------------------------------------------------------ */

/**
 * Step 0's picture, and the only one in the set that encodes a *sequence*: four
 * beats left to right with all three connectors lit - author an agent, give it
 * your documents, try it in the sandbox, then put it where your customers
 * already are.
 *
 * Every beat is a miniature of the drawing that step will later show at full
 * size: the agent glyph from `agents`, the three-deep document stack from
 * `knowledge`, the dashed frame and its straddling flask badge from
 * `playground`, the tailed speech bubble from `channels`. That is deliberate
 * and worth more than four fresh ideas would be - the reader meets each part
 * here as part of a plan and recognises it later as a place.
 *
 * The pips under each beat are how you write "1, 2, 3, 4" in a file that is
 * allowed no text (see the no-text rule up top): a count, the same way the bars
 * elsewhere are words. They are not decoration. An inline SVG is not mirrored in
 * an RTL locale - the geometry is fixed by the viewBox - so "leftmost is first"
 * is exactly the assumption that does not survive this pipeline, and an ordinal
 * is the part of the diagram that does.
 *
 * Note what is NOT drawn: workflows, tools, a second channel, monitoring. This
 * is the shortest path to one working agent, not a map of the console. The
 * eight steps after it are the map.
 */
export function RecipeIllustration({ className }: IllustrationProps): ReactElement {
  const pipRows = [[28], [85, 91], [145, 151, 157], [204, 210, 216, 222]];

  return (
    <Canvas name="recipe" className={className} css={dashFlowCss("recipe", "5 7", 24, "2.6s")}>
      {/* Beat 1 - the agent you author. */}
      <AgentGlyph cx={28} cy={53} />

      {/* Beat 2 - the documents you hand it. Front sheet knocked out so the
          stack reads as depth rather than three stacked outlines. */}
      <rect x={79} y={32} width={26} height={34} rx={4} fill={FILL} stroke={LINE} strokeWidth={1.2} />
      <rect x={75} y={36} width={26} height={34} rx={4} fill={FILL} stroke={LINE} strokeWidth={1.2} />
      <rect x={71} y={40} width={26} height={34} rx={4} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.2} />
      <path d="M89 40V48H97" fill="none" stroke={LINE} strokeWidth={1.2} strokeLinejoin="round" />
      <rect x={76} y={53} width={15} height={3.2} rx={1.6} fill={FILL_STRONG} />
      <rect x={76} y={60} width={11} height={3.2} rx={1.6} fill={FILL_STRONG} />

      {/* Beat 3 - the sandbox. Dashes mean "not real traffic"; the flask badge
          straddles the frame exactly as it does in `playground`. */}
      <rect
        x={130}
        y={31}
        width={42}
        height={44}
        rx={8}
        fill="none"
        stroke={LINE}
        strokeWidth={1.4}
        strokeDasharray="5 5"
      />
      <rect x={142} y={41} width={24} height={10} rx={5} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.3} />
      <rect x={136} y={57} width={28} height={10} rx={5} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.3} />
      {[143, 150, 157].map((cx) => (
        <circle key={cx} cx={cx} cy={62} r={1.6} fill={FILL_STRONG} />
      ))}
      <circle cx={146} cy={31} r={9} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.3} />
      {/* `playground`'s flask, re-centred on the origin so a transform can place
          it at any beat size. strokeWidth is pre-divided by the scale so the
          line weight still matches everything drawn at 1:1. */}
      <g transform="translate(146 31) scale(0.78)">
        <path
          d="M-3.5 -5.5H3.5M-2 -5.5V-1L-6.5 6H6.5L2 -1V-5.5"
          fill="none"
          stroke={ACCENT}
          strokeWidth={1.9}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </g>

      {/* Beat 4 - where the customer already is. Tail first, bubble over it, so
          there is no seam at the join (same order as `channels`). */}
      <path d="M203 61L203 69L212 61" fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} strokeLinejoin="round" />
      <rect x={197} y={39} width={32} height={22} rx={7} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <rect x={203} y={45} width={20} height={3.4} rx={1.7} fill={ACCENT_MID} />
      <rect x={203} y={52} width={13} height={3.4} rx={1.7} fill={ACCENT_SOFT} />

      {/* All three connectors lit. This is a route you are being asked to walk,
          not a diagram of what already exists - so none of them is drawn quiet
          the way the un-taken branch in `workflows` is. */}
      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M46 53H63" />
        <path d="M107 53H121" />
        <path d="M174 53H188" />
      </g>
      <ArrowRight x={69} y={53} fill={ACCENT} />
      <ArrowRight x={127} y={53} fill={ACCENT} />
      <ArrowRight x={194} y={53} fill={ACCENT} />

      {/* Ordinals: one pip, two pips, three, four. */}
      {pipRows.map((row, i) => (
        <g key={i}>
          {row.map((cx) => (
            <circle key={cx} cx={cx} cy={98} r={2.4} fill={ACCENT_MID} />
          ))}
        </g>
      ))}
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 2. welcome - the premise: your agent reaches your customers
 * ------------------------------------------------------------------ */

/**
 * The emblem is the real /logo-mark.png artwork placed with <image>, not an
 * SVG redrawing of it. Standing brand rule: the mark is ALWAYS the winged
 * human-in-helmet emblem, one artwork at every size - an earlier component in
 * this repo was rejected for approximating it with abstract SVG shapes, and a
 * tour slide is the worst possible place to repeat that. Everything around the
 * emblem (the signal arcs, the beams, the channel dots) is ours to draw.
 */
export function WelcomeIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="welcome" className={className} css={dashFlowCss("welcome", "5 7", 24, "2.6s")}>
      {/* Accent disc behind the emblem: gives the PNG a ground of its own so it
          does not float on either card colour. */}
      <circle cx={54} cy={60} r={31} fill={ACCENT_FAINT} />
      <image href="/logo-mark.png" x={28} y={34} width={52} height={52} preserveAspectRatio="xMidYMid meet" />

      {/* Two signal arcs: the emblem is broadcasting, not just sitting there. */}
      <path
        d="M85.1 38.2A38 38 0 0 1 85.1 81.8"
        fill="none"
        stroke={ACCENT_SOFT}
        strokeWidth={1.6}
        strokeLinecap="round"
      />
      <path
        d="M91.7 33.6A46 46 0 0 1 91.7 86.4"
        fill="none"
        stroke={ACCENT_FAINT}
        strokeWidth={1.6}
        strokeLinecap="round"
      />

      {/* Three beams out to three channel endpoints. The dash flow lives on the
          group; see dashFlowCss. */}
      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M100 60C128 60 140 30 172 30" />
        <path d="M100 60H172" />
        <path d="M100 60C128 60 140 90 172 90" />
      </g>

      {[30, 60, 90].map((cy) => (
        <g key={cy}>
          <circle cx={188} cy={cy} r={11} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.6} />
          <circle cx={188} cy={cy} r={3.4} fill={ACCENT} />
        </g>
      ))}
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 13. complete - the same four beats, done, and now running
 * ------------------------------------------------------------------ */

/**
 * The same four beats as `recipe`, every one of them ticked, and then a return
 * path from the channel back to the agent.
 *
 * What was here before was a checkmark in a ring with three rays - a mark that
 * would fit any product on earth - sitting on the one card in the entire tour
 * that states the build order in words ("Build an agent, give it your knowledge
 * and tools, test it in the Playground, then connect it to a channel"). The
 * picture illustrated none of that sentence. This one is that sentence.
 *
 * The loop back is the part that is not just a recap: finishing here is not a
 * completed checklist, it is a thing that now runs without you and keeps
 * answering, so the last beat returns to the first instead of stopping at an
 * endpoint. It is the only animated element in the drawing, which is the right
 * emphasis - the strip is done, the loop is not.
 *
 * Confetti was the obvious move and is still wrong: finishing a product tour is
 * not an achievement, and a celebration here would set the wrong expectation for
 * the actual first-agent milestone, which has its own celebration surface.
 */
export function CompleteIllustration({ className }: IllustrationProps): ReactElement {
  /* Tick badge, overhanging its tile's bottom-right corner. KNOCKOUT first so
     the tint underneath does not muddy the accent disc. */
  const done = (bx: number) => (
    <g key={bx}>
      <circle cx={bx} cy={64} r={8} fill={KNOCKOUT} />
      <circle cx={bx} cy={64} r={8} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.6} />
      <path
        d={`M${bx - 3.6} 64L${bx - 1} 66.8L${bx + 3.8} 61.2`}
        fill="none"
        stroke={ACCENT}
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </g>
  );

  return (
    <Canvas name="complete" className={className} css={dashFlowCss("complete", "5 7", 24, "2.8s")}>
      {[10, 69, 128, 187].map((x) => (
        <rect key={x} x={x} y={22} width={42} height={46} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.4} />
      ))}

      {/* Beat 1 - the agent. */}
      <path d="M31 31V26" stroke={ACCENT} strokeWidth={1.4} strokeLinecap="round" />
      <circle cx={31} cy={24.6} r={1.8} fill={ACCENT} />
      <rect x={20} y={31} width={22} height={19} rx={6} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.4} />
      <circle cx={27} cy={39} r={1.9} fill={ACCENT} />
      <circle cx={35} cy={39} r={1.9} fill={ACCENT} />
      <path d="M27 45H35" stroke={ACCENT} strokeWidth={1.4} strokeLinecap="round" />

      {/* Beat 2 - the knowledge. */}
      <rect x={84} y={30} width={20} height={26} rx={3} fill={FILL} stroke={LINE} strokeWidth={1.2} />
      <rect x={78} y={35} width={20} height={26} rx={3} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.2} />
      <path d="M92 35V41H98" fill="none" stroke={LINE} strokeWidth={1.2} strokeLinejoin="round" />
      <rect x={82} y={45} width={11} height={3} rx={1.5} fill={FILL_STRONG} />
      <rect x={82} y={51} width={8} height={3} rx={1.5} fill={FILL} />

      {/* Beat 3 - the sandbox run. */}
      <rect
        x={134}
        y={30}
        width={30}
        height={26}
        rx={6}
        fill="none"
        stroke={LINE}
        strokeWidth={1.4}
        strokeDasharray="4 4"
      />
      <rect x={142} y={34} width={18} height={8} rx={4} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.2} />
      <rect x={138} y={45} width={20} height={8} rx={4} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.2} />

      {/* Beat 4 - the channel. */}
      <path d="M199 50L199 57L207 50" fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.4} strokeLinejoin="round" />
      <rect x={193} y={30} width={30} height={20} rx={6} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.4} />
      <rect x={198} y={35} width={18} height={3.2} rx={1.6} fill={ACCENT_MID} />
      <rect x={198} y={41} width={11} height={3.2} rx={1.6} fill={ACCENT_SOFT} />

      {/* Forward connectors, static: this part is finished. */}
      <g fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M54 45H60" />
        <path d="M113 45H119" />
        <path d="M172 45H178" />
      </g>
      <ArrowRight x={67} y={45} fill={ACCENT} />
      <ArrowRight x={126} y={45} fill={ACCENT} />
      <ArrowRight x={185} y={45} fill={ACCENT} />

      {done(48)}
      {done(107)}
      {done(166)}
      {done(225)}

      {/* The loop back: answers keep arriving, and they land on the same agent. */}
      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M208 70C208 92 176 98 119 98C62 98 31 92 31 72" />
      </g>
      <path d="M31 70L26.6 77.6H35.4Z" fill={ACCENT} />
    </Canvas>
  );
}

