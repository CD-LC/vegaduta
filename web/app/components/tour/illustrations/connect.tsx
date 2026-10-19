import type { ReactElement } from "react";
import { LINE, KNOCKOUT, ACCENT, ACCENT_MID, ACCENT_SOFT, ACCENT_FAINT, FILL, FILL_STRONG, QUIET, dashFlowCss, pulseCss, Canvas, ArrowRight, AgentGlyph, type IllustrationProps } from "./shared";

/* ------------------------------------------------------------------ *
 * 6. channels - one agent, several places customers already are
 * ------------------------------------------------------------------ */

/**
 * Fan-out from one glyph, not three separate agent-plus-channel pairs: the
 * thing worth understanding is that the same agent answers everywhere, so
 * connecting a second channel is not a second build.
 *
 * The three endpoints are a speech bubble (a messaging app), a browser window
 * with a launcher bubble (the embeddable web widget) and an envelope (email).
 * Generic on purpose - see the no-logos note in the file docblock.
 */
export function ChannelsIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="channels" className={className} css={dashFlowCss("channels", "5 7", 24, "2.4s")}>
      <AgentGlyph cx={34} cy={60} />

      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M56 60C90 60 104 28 148 28" />
        <path d="M56 60H148" />
        <path d="M56 60C90 60 104 99 148 99" />
      </g>
      <ArrowRight x={156} y={28} fill={ACCENT} />
      <ArrowRight x={156} y={60} fill={ACCENT} />
      <ArrowRight x={156} y={99} fill={ACCENT} />

      {/* Messaging app. The tail is an open path: its closing edge is filled but
          not stroked, so there is no seam where it meets the bubble. */}
      <path d="M168 42L168 52L180 42" fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} strokeLinejoin="round" />
      <rect x={160} y={12} width={56} height={30} rx={9} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <rect x={170} y={20} width={36} height={4} rx={2} fill={ACCENT_MID} />
      <rect x={170} y={29} width={24} height={4} rx={2} fill={ACCENT_SOFT} />

      {/* Web widget: a page with the chat launcher parked in its corner. */}
      <rect x={160} y={44} width={56} height={34} rx={8} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.4} />
      <path d="M160 54H216" stroke={LINE} strokeWidth={1.2} />
      {[167, 173, 179].map((cx) => (
        <circle key={cx} cx={cx} cy={49} r={1.7} fill={QUIET} />
      ))}
      <rect x={188} y={59} width={22} height={13} rx={6.5} fill={ACCENT_SOFT} />

      {/* Email. */}
      <rect x={160} y={84} width={52} height={30} rx={6} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.4} />
      <path d="M160 88L186 102L212 88" fill="none" stroke={LINE} strokeWidth={1.4} strokeLinejoin="round" />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 7. tools - the agent plugged into systems it does not own
 * ------------------------------------------------------------------ */

/**
 * Plug-and-socket rather than arrows, because a tool connection is a two-sided
 * fit: the external service exposes an interface and the agent has to match it.
 * The key hanging off the cable bundle is the credential half of that - tool
 * secrets are stored once in the vault and handed to the connection, never
 * pasted into the agent's instructions.
 */
export function ToolsIllustration({ className }: IllustrationProps): ReactElement {
  const tile = (cy: number) => (
    <g key={cy}>
      {/* Socket face on the tile's left edge. */}
      <rect x={166} y={cy - 9} width={12} height={18} rx={3} fill={FILL} stroke={LINE} strokeWidth={1.2} />
      <circle cx={172} cy={cy - 4} r={1.7} fill={QUIET} />
      <circle cx={172} cy={cy + 4} r={1.7} fill={QUIET} />
      {/* Plug on the cable, prongs bridging into the socket. */}
      <path d={`M164 ${cy - 4}h6M164 ${cy + 4}h6`} stroke={ACCENT} strokeWidth={1.6} strokeLinecap="round" />
      <rect x={150} y={cy - 8} width={14} height={16} rx={3.5} fill={KNOCKOUT} stroke={ACCENT} strokeWidth={1.6} />
      {/* The external service itself. */}
      <rect x={178} y={cy - 14} width={52} height={28} rx={8} fill={FILL} stroke={LINE} strokeWidth={1.4} />
      <circle cx={190} cy={cy} r={4} fill={FILL_STRONG} />
      <rect x={200} y={cy - 2.5} width={22} height={5} rx={2.5} fill={FILL_STRONG} />
    </g>
  );

  return (
    <Canvas name="tools" className={className} css={dashFlowCss("tools", "5 7", 24, "2.4s")}>
      <AgentGlyph cx={30} cy={60} />

      <g className="vd-flow" fill="none" stroke={ACCENT_MID} strokeWidth={2} strokeLinecap="round">
        <path d="M48 60C92 60 100 26 150 26" />
        <path d="M48 60H150" />
        <path d="M48 60C92 60 100 94 150 94" />
      </g>

      {[26, 60, 94].map(tile)}

      {/* Secrets: a key on a short lead off the bundle. Drawn last so it sits
          over the cables it belongs to. */}
      <path d="M92 92L100 74" stroke={QUIET} strokeWidth={1.3} strokeDasharray="3 3" strokeLinecap="round" />
      <circle cx={86} cy={100} r={7.5} fill={KNOCKOUT} stroke={ACCENT} strokeWidth={2} />
      <circle cx={86} cy={100} r={2.6} fill={ACCENT} />
      <path
        d="M93.5 100H116M106 100V106M113 100V105"
        fill="none"
        stroke={ACCENT}
        strokeWidth={2}
        strokeLinecap="round"
      />
    </Canvas>
  );
}

/* ------------------------------------------------------------------ *
 * 8. playground - a private room to try the agent in
 * ------------------------------------------------------------------ */

/**
 * The dashed frame does the entire job: dashes mean "this boundary is not
 * real traffic". The flask badge sits astride the border, knocked out with
 * KNOCKOUT so it interrupts the dash - a label on the enclosure rather than a
 * sticker inside it. The typing dots are in the agent's reply, so the picture
 * shows a turn in progress rather than a finished transcript.
 */
export function PlaygroundIllustration({ className }: IllustrationProps): ReactElement {
  return (
    <Canvas name="playground" className={className} css={pulseCss("playground", 0.3, "2.4s")}>
      <rect
        x={16}
        y={12}
        width={208}
        height={94}
        rx={14}
        fill="none"
        stroke={LINE}
        strokeWidth={1.6}
        strokeDasharray="6 6"
      />

      {/* Your message. */}
      <path d="M194 58L200 67L184 58" fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} strokeLinejoin="round" />
      <rect x={110} y={30} width={96} height={28} rx={10} fill={ACCENT_FAINT} stroke={ACCENT} strokeWidth={1.5} />
      <rect x={122} y={38} width={62} height={5} rx={2.5} fill={ACCENT_MID} />
      <rect x={122} y={47} width={40} height={5} rx={2.5} fill={ACCENT_SOFT} />

      {/* The agent, mid-answer. */}
      <path d="M48 94L42 103L60 94" fill={FILL} stroke={LINE} strokeWidth={1.5} strokeLinejoin="round" />
      <rect x={36} y={66} width={92} height={28} rx={10} fill={FILL} stroke={LINE} strokeWidth={1.5} />
      <g className="vd-pulse">
        {[58, 72, 86].map((cx) => (
          <circle key={cx} cx={cx} cy={80} r={3.4} fill={FILL_STRONG} />
        ))}
      </g>

      {/* Sandbox badge, straddling the frame. */}
      <circle cx={44} cy={12} r={11} fill={KNOCKOUT} stroke={LINE} strokeWidth={1.4} />
      <path
        d="M40.5 6.5H47.5M42 6.5V11L37.5 18H50.5L46 11V6.5"
        fill="none"
        stroke={ACCENT}
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Canvas>
  );
}

