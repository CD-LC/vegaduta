import type { ReactElement } from "react";
import { LINE, KNOCKOUT, ACCENT, ACCENT_MID, ACCENT_SOFT, ACCENT_FAINT, FILL, FILL_STRONG, QUIET, dashFlowCss, Canvas, ArrowRight, MiniAgent, type IllustrationProps } from "./shared";

/* ------------------------------------------------------------------ *
 * 11. shared-with-me - work that arrives already built (member only)
 * ------------------------------------------------------------------ */

/**
 * The member tour's own step, and the only drawing in the set where the agent
 * is NOT something the reader authored. A member is handed agents and workflows
 * by a teammate at a permission level; the whole of /shared-with-me is that
 * inbound list.
 *
 * So the composition is deliberately the reverse of `channels` and `tools`,
 * which fan OUT of the agent glyph the reader owns. Here one flow runs INTO a
 * list, from a person, and the agent marks are inside the rows - the reader is
 * the destination, not the source. A member who never opens the agent builder
 * still has a working product, and this is the picture of that.
 *
 * ONE GRANTOR, NOT A GALLERY. Sharing is named-invite only - there is no
 * directory of other people's private resources to browse (see
 * app/shared-with-me/page.tsx and core/sharing). A grid of tiles would have
 * drawn a marketplace and promised discovery that does not exist, so this draws
 * a person handing into a list instead. The second, quieter disc behind the
 * avatar is the only nod to "grants come from more than one teammate".
 *
 * The rows are two kinds on purpose - a `MiniAgent` and the branch node from
 * `workflows` - because both resource kinds arrive on this page, and the pill on
 * the right of each row is the permission level, the field that decides whether
 * an opened row is read-only. The third row is drawn quiet: a list, not a set of
 * three.
 */
export function SharedWithMeIllustration({ className }: IllustrationProps): ReactElement {
  const row = (y: number, active: boolean) => (
    <g key={y}>
      <rect x={104} y={y} width={116} height={24} rx={7} fill={KNOCKOUT} />
      <rect
        x={104}
        y={y}
        width={116}
        height={24}
        rx={7}
        fill={active ? ACCENT_FAINT : "none"}
        stroke={active ? ACCENT : LINE}
        strokeWidth={active ? 1.5 : 1.3}
      />
      {/* Permission pill. Knocked out first so it lifts off the row tint. */}
      <rect x={180} y={y + 6} width={36} height={12} rx={6} fill={KNOCKOUT} />
      <rect
        x={180}
        y={y + 6}
        width={36}
        height={12}
        rx={6}
        fill={active ? ACCENT_FAINT : "none"}
        stroke={active ? ACCENT_MID : LINE}
        strokeWidth={1.3}
      />
    </g>
  );

  return (
    <Canvas name="shared-with-me" className={className} css={dashFlowCss("shared-with-me", "5 7", 24, "2.6s")}>
      {/* The other teammates a grant can come from, kept faint. */}
      <circle cx={26} cy={47} r={11} fill={FILL} stroke={LINE} strokeWidth={1.2} />

      {/* The teammate who shared: the same avatar construction as `agents`, so
          the person reads as a colleague inside the product, not a visitor. */}
      <circle cx={40} cy={60} r={15} fill={KNOCKOUT} />
      <circle cx={40} cy={60} r={15} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <circle cx={40} cy={55.7} r={5.1} fill={ACCENT} />
      <path d="M32.3 70.1A7.7 7.7 0 0 1 47.7 70.1Z" fill={ACCENT} />

      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M58 60H84" />
      </g>
      <ArrowRight x={91} y={60} fill={ACCENT} />

      {/* The inbound list. */}
      <rect x={96} y={6} width={132} height={108} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.3} />

      {row(16, true)}
      {row(50, false)}
      {row(84, false)}

      {/* Row 1 - a shared agent. */}
      <MiniAgent x={110} y={23} />
      <rect x={131} y={22} width={40} height={5.5} rx={2.75} fill={FILL_STRONG} />
      <rect x={131} y={32.5} width={24} height={3.4} rx={1.7} fill={FILL} />

      {/* Row 2 - a shared workflow, drawn as the branch from `workflows`. */}
      <g fill="none" stroke={QUIET} strokeWidth={1.3} strokeLinecap="round">
        <path d="M113.4 62C116.2 62 115.6 57.4 117 57.4" />
        <path d="M113.4 62C116.2 62 115.6 66.6 117 66.6" />
      </g>
      <circle cx={111} cy={62} r={2.4} fill={FILL_STRONG} />
      <circle cx={119.4} cy={57.4} r={2.2} fill={FILL_STRONG} />
      <circle cx={119.4} cy={66.6} r={2.2} fill={FILL_STRONG} />
      <rect x={131} y={56} width={52} height={5.5} rx={2.75} fill={FILL_STRONG} />
      <rect x={131} y={66.5} width={20} height={3.4} rx={1.7} fill={FILL} />

      {/* Row 3 - "and more below", not a third specific grant. */}
      <MiniAgent x={110} y={91} quiet />
      <rect x={131} y={90} width={34} height={5.5} rx={2.75} fill={FILL_STRONG} />
      <rect x={131} y={100.5} width={26} height={3.4} rx={1.7} fill={FILL} />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 12. platform - the operator's controls are in another application
 * ------------------------------------------------------------------ */

/**
 * The platform-admin drawing, and the one picture in the set whose job is to
 * point OUT of this app.
 *
 * A platform-admin operates the platform across tenants and may hold no tenant
 * of their own (AuthProvider notes exactly that case), so walking them through
 * authoring a first agent would be the wrong tour. The console they actually
 * work in is not in `web/` at all - it is the separate web-admin app on the
 * `admin.` host - and the honest thing to draw is that arrangement rather than
 * an invented walkthrough of screens this application does not have.
 *
 * So: the tenants are here, on the left, drawn as workspace records rather than
 * as agents because an operator's unit of work is the tenant. The console is on
 * the right with two operator switches in it, one on and one off, and it runs
 * off the right edge of the canvas. That crop IS the message - "over there, not
 * here" - and it is why the window is not centred and given a tidy border. An
 * inline SVG is clipped by its own viewBox, so the crop needs no clipPath (see
 * the no-ids note in the file docblock).
 *
 * The flow still points left to right, tenants into console, because that is the
 * direction of the reader's attention: what you administer, and where you go to
 * administer it.
 */
export function PlatformIllustration({ className }: IllustrationProps): ReactElement {
  const tenant = (y: number) => (
    <g key={y}>
      <rect x={8} y={y} width={72} height={28} rx={8} fill={FILL} stroke={LINE} strokeWidth={1.3} />
      <circle cx={24} cy={y + 14} r={7} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.3} />
      <circle cx={24} cy={y + 14} r={2.8} fill={ACCENT_MID} />
      <rect x={38} y={y + 8} width={32} height={5} rx={2.5} fill={FILL_STRONG} />
      <rect x={38} y={y + 17} width={20} height={3.4} rx={1.7} fill={FILL} />
    </g>
  );

  /* One operator switch: label plus a track with the knob knocked out of it. */
  const toggle = (y: number, on: boolean, labelWidth: number) => (
    <g key={y}>
      <rect x={160} y={y + 4} width={labelWidth} height={5} rx={2.5} fill={on ? ACCENT_SOFT : FILL_STRONG} />
      <rect x={210} y={y} width={26} height={13} rx={6.5} fill={on ? ACCENT : FILL_STRONG} />
      <circle cx={on ? 229.5 : 216.5} cy={y + 6.5} r={4.4} fill={KNOCKOUT} />
    </g>
  );

  return (
    <Canvas name="platform" className={className} css={dashFlowCss("platform", "5 7", 24, "2.6s")}>
      {[10, 46, 82].map(tenant)}

      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M84 24C112 24 118 60 140 60" />
        <path d="M84 60H140" />
        <path d="M84 96C112 96 118 60 140 60" />
      </g>
      <ArrowRight x={148} y={60} fill={ACCENT} />

      {/* The console, running off the right edge of the canvas on purpose. */}
      <rect x={150} y={26} width={96} height={68} rx={10} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.6} />
      <path d="M150 42H246" stroke={ACCENT_MID} strokeWidth={1.2} />
      {[160, 167, 174].map((cx) => (
        <circle key={cx} cx={cx} cy={34} r={1.8} fill={ACCENT_SOFT} />
      ))}

      {toggle(50, true, 40)}
      {toggle(72, false, 28)}
    </Canvas>
  );
}

