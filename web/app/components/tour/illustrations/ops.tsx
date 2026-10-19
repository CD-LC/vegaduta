import type { ReactElement, ReactNode } from "react";
import { LINE, KNOCKOUT, ACCENT, ACCENT_MID, ACCENT_SOFT, ACCENT_FAINT, FILL, FILL_STRONG, QUIET, dashFlowCss, pulseCss, Canvas, MiniAgent, type IllustrationProps } from "./shared";

/* ------------------------------------------------------------------ *
 * 9. monitor - the Playground, the files, and the admin log
 * ------------------------------------------------------------------ */

/**
 * Three tiles, because the step names three things and not one: the Playground
 * you keep re-testing in, the files your agents produced, and - for admins only
 * - live activity down an audit spine.
 *
 * This replaces a sparkline with a dashed threshold and a red alert dot. That
 * drawing claimed, in its own docblock, to show "which run to go and look at",
 * and it was a promise the page does not keep: there is no time series and no
 * alerting behind Test and Monitor, so the picture was selling observability
 * the step never describes. Nothing in here is red any more, and `--destructive`
 * left the palette with it.
 *
 * The padlock straddling the third tile is load-bearing, not chrome: it is the
 * only way to say "this column is not on your screen unless you are an admin",
 * which is otherwise the single most confusing thing about this page for a
 * member account.
 */
export function MonitorIllustration({ className }: IllustrationProps): ReactElement {
  /* One produced file: a type badge, then the name and the size as bars. */
  const fileRow = (cy: number, badge: ReactNode, wide: number, narrow: number) => (
    <g key={cy}>
      <rect x={86} y={cy - 11} width={68} height={22} rx={5} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.1} />
      <rect x={90} y={cy - 8} width={16} height={16} rx={4} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.2} />
      {badge}
      <rect x={112} y={cy - 5} width={wide} height={4} rx={2} fill={FILL_STRONG} />
      <rect x={112} y={cy + 2} width={narrow} height={3.4} rx={1.7} fill={FILL} />
    </g>
  );

  /* Audit entries. The first one is live; the rest are history. */
  const logRows: Array<{ cy: number; w: number; live?: boolean }> = [
    { cy: 42, w: 36, live: true },
    { cy: 60, w: 28 },
    { cy: 78, w: 40 },
    { cy: 96, w: 30 },
  ];

  return (
    <Canvas name="monitor" className={className} css={pulseCss("monitor", 0.25, "2.4s")}>
      {/* 1. The Playground, which does not go away when the tour does. */}
      <rect x={8} y={14} width={64} height={92} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.4} />
      {/* `playground`'s flask again, same origin-centred path as `recipe`. */}
      <g transform="translate(40 40) scale(1.5)">
        <path
          d="M-3.5 -5.5H3.5M-2 -5.5V-1L-6.5 6H6.5L2 -1V-5.5"
          fill="none"
          stroke={ACCENT}
          strokeWidth={1}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </g>
      <rect x={26} y={60} width={32} height={11} rx={5.5} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.3} />
      <rect x={18} y={78} width={38} height={11} rx={5.5} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.3} />
      {[27, 34, 41].map((cx) => (
        <circle key={cx} cx={cx} cy={83.5} r={1.8} fill={FILL_STRONG} />
      ))}

      {/* 2. What the agents produced. Three rows, three kinds of artefact -
             image, audio, document - because "files" on its own reads as
             uploads, and these are outputs. */}
      <rect x={80} y={14} width={80} height={92} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.4} />
      {fileRow(
        34,
        <g key="image">
          <circle cx={102} cy={30} r={1.8} fill={ACCENT} />
          <path d="M92 40L97 33L102.5 40Z" fill={ACCENT_SOFT} />
        </g>,
        38,
        20,
      )}
      {fileRow(
        62,
        <g key="audio">
          {([
            [93, 3],
            [96.5, 6],
            [100, 4.5],
            [103.5, 2],
          ] as Array<[number, number]>).map(([x, h]) => (
            <rect key={x} x={x} y={62 - h} width={2.2} height={h * 2} rx={1.1} fill={ACCENT} />
          ))}
        </g>,
        30,
        26,
      )}
      {fileRow(
        90,
        <g key="doc">
          <rect x={93} y={86} width={10} height={2.2} rx={1.1} fill={ACCENT_SOFT} />
          <rect x={93} y={89.4} width={10} height={2.2} rx={1.1} fill={ACCENT_SOFT} />
          <rect x={93} y={92.8} width={6} height={2.2} rx={1.1} fill={ACCENT_SOFT} />
        </g>,
        34,
        16,
      )}

      {/* 3. Admin only: live activity at the head of an audit trail. */}
      <rect x={168} y={14} width={64} height={92} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.4} />
      <path d="M176 34V102" stroke={LINE} strokeWidth={1.2} />
      {logRows.map(({ cy, w, live }) => (
        <g key={cy}>
          <circle cx={176} cy={cy} r={2.6} fill={live ? ACCENT : QUIET} />
          <rect x={184} y={cy - 2} width={w} height={4} rx={2} fill={live ? ACCENT_SOFT : FILL_STRONG} />
        </g>
      ))}
      {/* The one live entry, and the drawing's one animated element. */}
      <circle className="vd-pulse" cx={176} cy={42} r={6.5} fill="none" stroke={ACCENT_SOFT} strokeWidth={1.8} />

      <circle cx={216} cy={14} r={9} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.3} />
      <path
        d="M213.5 13.5V11.2A2.5 2.5 0 0 1 218.5 11.2V13.5"
        fill="none"
        stroke={ACCENT}
        strokeWidth={1.3}
        strokeLinecap="round"
      />
      <rect x={211.5} y={13.5} width={9} height={7} rx={2} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.3} />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 10. help - the docs, and this tour again whenever you want it
 * ------------------------------------------------------------------ */

/**
 * A book rather than a lifebuoy: a lifebuoy reads as "something has gone
 * wrong", and Help here is reference material you read on purpose. The arrow
 * orbiting it is the replay affordance, which is the one thing about the Help
 * page a first-time reader would otherwise never discover - the tour cannot be
 * re-entered from anywhere else.
 */
export function HelpIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="help" className={className} css={dashFlowCss("help", "7 7", 28, "3s")}>
      {/* Replay orbit: a 320-degree arc, so the gap reads as a loop with a
          beginning rather than a closed ring. */}
      <g className="vd-flow" fill="none" stroke={ACCENT} strokeWidth={2.2} strokeLinecap="round">
        <path d="M104.3 101.2A46 46 0 1 1 135.7 101.2" />
      </g>
      <path d="M135.7 101.2L142.5 93.4L145.9 102.8Z" fill={ACCENT} />

      {/* Open book. */}
      <path
        d="M120 44C110 36 96 34 86 36V76C96 74 110 76 120 82Z"
        fill={ACCENT_FAINT}
        stroke={ACCENT}
        strokeWidth={1.8}
        strokeLinejoin="round"
      />
      <path
        d="M120 44C130 36 144 34 154 36V76C144 74 130 76 120 82Z"
        fill={ACCENT_FAINT}
        stroke={ACCENT}
        strokeWidth={1.8}
        strokeLinejoin="round"
      />
      <path d="M120 44V82" stroke={ACCENT} strokeWidth={1.8} strokeLinecap="round" />
      <rect x={94} y={48} width={18} height={3.4} rx={1.7} fill={ACCENT_SOFT} />
      <rect x={94} y={57} width={22} height={3.4} rx={1.7} fill={ACCENT_SOFT} />
      <rect x={130} y={48} width={18} height={3.4} rx={1.7} fill={ACCENT_SOFT} />
      <rect x={130} y={57} width={22} height={3.4} rx={1.7} fill={ACCENT_SOFT} />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 10b. edge - a model that runs on the reader's own device (2026-09-19)
 * ------------------------------------------------------------------ */

/**
 * Drawn FOR the Edge AI step, which did not exist until the first-login audit
 * of 2026-09-19 found the tour never said where on-device AI lives. Borrowing
 * `tools` or `playground` here would have repeated the mistake this file's
 * header records twice already - a step illustrated with a picture of a
 * different feature.
 *
 * The composition is the one idea the step has to land: the model comes DOWN
 * into the device and the agent then sits INSIDE it. So the only thing that
 * moves is the download line, the hosted side is a single quiet disc cropped
 * by the top edge (it is where the model came from, not the subject), and the
 * processor die around the agent's head is what says "this runs here". No
 * cloud glyph: a cloud would make the hosted model the hero of a drawing about
 * not using it.
 */
export function EdgeIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="edge" className={className} css={dashFlowCss("edge", "5 6", 22, "2.4s")}>
      {/* Where the model comes from: quiet, and cropped by the top edge. */}
      <circle cx={120} cy={2} r={13} fill={FILL} stroke={LINE} strokeWidth={1.4} />

      {/* The download - the one animated element. */}
      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M120 17V30" />
      </g>
      <path d="M120 37L115.8 29.6H124.2Z" fill={ACCENT} />

      {/* The device: a laptop, lid and base. */}
      <rect x={70} y={38} width={100} height={60} rx={7} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.8} />
      <path d="M56 106H184" stroke={ACCENT} strokeWidth={2.4} strokeLinecap="round" />
      <path d="M108 102H132" stroke={ACCENT_SOFT} strokeWidth={2} strokeLinecap="round" />

      {/* Processor die, with pins, and the agent running on it. */}
      <rect x={97} y={48} width={46} height={40} rx={6} fill={KNOCKOUT} stroke={ACCENT_MID} strokeWidth={1.4} />
      <g stroke={ACCENT_SOFT} strokeWidth={1.6} strokeLinecap="round">
        <path d="M90 58H97M90 68H97M90 78H97" />
        <path d="M143 58H150M143 68H150M143 78H150" />
      </g>
      <MiniAgent x={112.5} y={63} />
    </Canvas>
  );
}

