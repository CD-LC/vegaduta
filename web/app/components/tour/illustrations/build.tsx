import type { ReactElement } from "react";
import { LINE, KNOCKOUT, ACCENT, ACCENT_SOFT, ACCENT_FAINT, FILL, FILL_STRONG, QUIET, dashFlowCss, pulseCss, Canvas, ArrowRight, AgentGlyph, type IllustrationProps } from "./shared";

/* ------------------------------------------------------------------ *
 * 3. agents - a persona card with a model chip attached
 * ------------------------------------------------------------------ */

/**
 * Reads as: an agent is a *record you author* (avatar, name, standing
 * instructions) plus a model you bolt onto it. The chip deliberately overhangs
 * the card's bottom-right corner - "attached to", not "part of" - because
 * swapping the model without rewriting the agent is the point of the
 * hosted-model pool.
 */
export function AgentsIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="agents" className={className} css={pulseCss("agents", 0.4, "3.4s")}>
      <rect x={34} y={16} width={150} height={82} rx={12} fill={FILL} stroke={LINE} strokeWidth={1.4} />

      {/* Avatar: head + shoulders drawn to sit inside the disc, so no clipPath
          is needed (see the file docblock on ids). */}
      <circle cx={60} cy={42} r={14} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <circle cx={60} cy={38} r={4.8} fill={ACCENT} />
      <path d="M52.8 51.4A7.2 7.2 0 0 1 67.2 51.4Z" fill={ACCENT} />

      {/* Name and handle. Bars, not text - see the no-text rule up top. */}
      <rect x={84} y={34} width={64} height={8} rx={4} fill={FILL_STRONG} />
      <rect x={84} y={48} width={40} height={6} rx={3} fill={FILL} />

      <path d="M48 64H170" stroke={LINE} strokeWidth={1.2} />

      {/* The standing instructions: the long prose field that actually defines
          the agent's behaviour. */}
      <rect x={48} y={72} width={122} height={5} rx={2.5} fill={FILL_STRONG} />
      <rect x={48} y={81} width={104} height={5} rx={2.5} fill={FILL_STRONG} />
      <rect x={48} y={90} width={68} height={5} rx={2.5} fill={FILL} />

      {/* Model chip. KNOCKOUT first so it lifts cleanly off the panel underneath
          instead of showing the instruction bars through its tint. */}
      <rect x={150} y={84} width={66} height={22} rx={11} fill={KNOCKOUT} />
      <rect x={150} y={84} width={66} height={22} rx={11} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <circle className="vd-pulse" cx={163} cy={95} r={4} fill={ACCENT} />
      <rect x={173} y={90} width={34} height={4.5} rx={2.25} fill={ACCENT_SOFT} />
      <rect x={173} y={98} width={22} height={3.5} rx={1.75} fill={FILL_STRONG} />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 4. workflows - a canvas of connected steps, with a branch
 * ------------------------------------------------------------------ */

/**
 * Four nodes rather than three, because the branch is the whole idea: one step
 * splits into two paths that rejoin. Three nodes in a row would have drawn a
 * pipeline, which is the thing a workflow is NOT. The highlighted node is the
 * selected one - a workflow is edited by picking a node, the same way the real
 * canvas works.
 */
export function WorkflowsIllustration({ className }: IllustrationProps): ReactElement {
  const node = (x: number, y: number, active: boolean) => (
    <g key={`${x}-${y}`}>
      <rect
        x={x}
        y={y}
        width={48}
        height={26}
        rx={7}
        fill={active ? ACCENT_FAINT : FILL}
        stroke={active ? ACCENT : LINE}
        strokeWidth={active ? 2 : 1.4}
      />
      <circle cx={x + 12} cy={y + 13} r={3.6} fill={active ? ACCENT : FILL_STRONG} />
      <rect x={x + 22} y={y + 10.5} width={16} height={5} rx={2.5} fill={active ? ACCENT_SOFT : FILL_STRONG} />
    </g>
  );

  return (
    <Canvas name="workflows" className={className} css={dashFlowCss("workflows", "6 6", 24, "2.2s")}>
      {/* The un-taken branch, drawn quiet so the eye follows the live route. */}
      <g fill="none" stroke={QUIET} strokeWidth={1.6} strokeLinecap="round">
        <path d="M62 60C76 60 78 89 88 89" />
        <path d="M140 89C154 89 156 60 168 60" />
      </g>
      <ArrowRight x={89} y={89} fill={QUIET} />

      {/* The live route A -> B -> D. */}
      <g className="vd-flow" fill="none" stroke={ACCENT} strokeWidth={2} strokeLinecap="round">
        <path d="M62 60C76 60 78 31 88 31" />
        <path d="M140 31C154 31 156 60 168 60" />
      </g>
      <ArrowRight x={89} y={31} fill={ACCENT} />
      <ArrowRight x={169} y={60} fill={ACCENT} />

      {node(14, 47, false)}
      {node(92, 18, true)}
      {node(92, 76, false)}
      {node(174, 47, false)}
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 5. knowledge - documents become an index an agent can read
 * ------------------------------------------------------------------ */

/**
 * Three beats left to right, because the thing people get wrong about a
 * knowledge base is thinking the files *are* the knowledge: you upload
 * documents, they are turned into an index, and the agent searches the index at
 * answer time. The two lit cells are the chunks a question just matched.
 */
export function KnowledgeIllustration({ className }: IllustrationProps): ReactElement {
  const cols = [104, 122, 140, 158];
  const rows = [40, 58, 76];
  const lit = new Set(["122-40", "158-76"]);

  return (
    <Canvas name="knowledge" className={className} css={pulseCss("knowledge", 0.35, "3.2s")}>
      {/* Document stack. Only the front sheet is KNOCKOUT-filled, so the stack
          reads as depth in both modes rather than three identical rectangles. */}
      <rect x={22} y={26} width={42} height={54} rx={5} fill={FILL} stroke={LINE} strokeWidth={1.3} />
      <rect x={17} y={32} width={42} height={54} rx={5} fill={FILL} stroke={LINE} strokeWidth={1.3} />
      <rect x={12} y={38} width={42} height={54} rx={5} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.3} />
      <path d="M46 38V46H54" fill="none" stroke={LINE} strokeWidth={1.3} strokeLinejoin="round" />
      <rect x={20} y={54} width={26} height={4} rx={2} fill={FILL_STRONG} />
      <rect x={20} y={63} width={20} height={4} rx={2} fill={FILL_STRONG} />
      <rect x={20} y={72} width={24} height={4} rx={2} fill={FILL} />

      <path d="M68 65H90" stroke={QUIET} strokeWidth={1.6} strokeLinecap="round" />
      <ArrowRight x={96} y={65} fill={QUIET} />

      {/* The index. A grid, not a cylinder: what is stored is chunk vectors, and
          a database drum would have suggested rows in a table. */}
      {rows.map((y) =>
        cols.map((x) =>
          lit.has(`${x}-${y}`) ? null : (
            <rect key={`${x}-${y}`} x={x} y={y} width={13} height={13} rx={3} fill={FILL} stroke={LINE} strokeWidth={1} />
          ),
        ),
      )}
      <g className="vd-pulse">
        {Array.from(lit).map((key) => {
          const [x, y] = key.split("-").map(Number);
          return <rect key={key} x={x} y={y} width={13} height={13} rx={3} fill={ACCENT_SOFT} stroke={ACCENT} strokeWidth={1.3} />;
        })}
      </g>

      <path d="M176 64H186" stroke={QUIET} strokeWidth={1.6} strokeLinecap="round" />
      <ArrowRight x={192} y={64} fill={QUIET} />
      <AgentGlyph cx={214} cy={64} />
    </Canvas>
  );
}

