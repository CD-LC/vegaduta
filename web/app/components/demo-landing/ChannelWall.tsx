"use client";

import { Check, KeyRound, Lock, Radio } from "lucide-react";
import Reveal from "../marketing/Reveal";
import BrandMark, { CHANNEL_KEYS, type BrandKey } from "./BrandMark";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";

// ChannelWall — the exact number of channels, made legible at a glance, with
// the free/paid split stated on the same screen as the number.
//
// Direct instruction (2026-09-11): the hero used to say "WhatsApp, Telegram
// and Email", naming three of nine. This band exists so the page can state
// the real figure and immediately show what it buys you.
//
// ---------------------------------------------------------------------------
// NO-FICTION LEDGER
// ---------------------------------------------------------------------------
// NINE: core/omnichannel/src/main/java/com/agenticai/core/omnichannel/session/
//   ChannelType.java:32-42 — WHATSAPP, MESSENGER, INSTAGRAM, SLACK, DISCORD,
//   X, TELEGRAM, EMAIL, VOICE. That enum is the whole list, so "nine" is a
//   count of the routed channel types, not a marketing round number. The list
//   here is not re-typed: it is CHANNEL_KEYS from BrandMark.tsx, and the third
//   lane is DERIVED by subtraction (CHANNEL_KEYS minus the two named lanes) so
//   the three lanes must always re-sum to nine. Copy elsewhere on the page has
//   already drifted twice this way — en.json:3569 still says "11 channels" and
//   en.json:4010 says "5 channels" — which is exactly the failure this
//   derive-don't-retype shape prevents here.
//
// THREE FREE (was two, 2026-09-12): "freeIncludes.channels" in en.json,
//   rendered by FreePlanIncludes.tsx — the one shipped free-plan channel
//   claim, which this band repeats in substance. Both were updated together
//   when WhatsApp joined; if you change one, change the other or the pricing
//   page and this band will disagree.
//
// NONE PLAN-GATED (changed 2026-09-12): WhatsApp used to be the one and only
//   plan-gated channel — V101__plan_limits.sql:45 seeded the FREE row with
//   whatsapp_allowed=FALSE, enforced at MetaWhatsAppCredentialService.java:70.
//   Migration V225 flips that row to TRUE, so no channel is gated by plan any
//   more. Two things worth knowing before anyone "restores" the old lane:
//     * That gate only ever covered the Meta Cloud API path. The Twilio path
//       never carried a plan check at all, so a FREE tenant could already
//       connect WhatsApp while this band said they could not. V225 closed a
//       copy-vs-code gap as much as it opened a capability.
//     * It costs the platform nothing. Every WhatsApp path is
//       bring-your-own-credential, so Meta bills the tenant, not us. See
//       V225's header comment for the full reasoning.
//   WhatsApp is FREE FOREVER, independently of the 3-month complimentary
//   UNLIMITED grant new signups receive (TenantProvisioningRecorder) — do not
//   describe it as part of that trial, because it does not expire with it.
//
// THE OTHER SIX: Messenger, Instagram, Slack, Discord, X and Voice carry NO
//   plan flag anywhere in plan_limits. They are unavailable to a brand-new
//   account for a different reason — they need credentials from the service
//   being connected (Meta, Slack, Discord, X, or telephony for VOICE via
//   Twilio Conversation Relay, TwilioVoiceWebhookController). So this band
//   must NOT call them "paid": the code does not say that. It says "connect
//   your own account", which is what the code does say. Do not sharpen this
//   lane into a paywall on a later edit — that would be a fabricated claim.
//
// ARTWORK + TRADEMARK: every mark comes from web/public/logos/*.svg, which are
//   generic glyphs authored in this repo in each brand's own colour, never a
//   reproduction of a registered logo. Full reasoning and the colour ledger
//   live in BrandMark.tsx's header. The closing footnote says so on the page
//   as well, and no partnership or endorsement is implied anywhere.
//
// Colours are theme tokens + alpha tints only — no `dark:` variants, which in
// this app follow the OS query and desync from the .dark class (see the NOTE
// in HeroUniversalAgent.tsx).
//
// TYPOGRAPHY: every text role here uses the shared marketing scale in
// globals.css (.mkt-h2, .mkt-eyebrow, .mkt-section-sub, .mkt-card-title,
// .mkt-small, .mkt-badge, .mkt-caption) rather than ad-hoc `text-*`. Those
// rules are unlayered and therefore beat Tailwind's layered size/tracking
// utilities, so adding a `text-sm` or `tracking-[…]` beside one of them will
// silently do nothing — change the class, not the utility. 12px is the floor;
// there is no 10px or 11px role on this page.
//
// Same trap for COLOUR on the eyebrows: marketing-system.css pins
// `.mkt-eyebrow { color: var(--mkt-ink-3) }` unlayered, so a `text-primary` or
// `text-muted-foreground` sitting beside .mkt-eyebrow is dead weight — it
// cannot win, and the two that used to sit here were removed rather than left
// to read as if they were doing something.
//
// SURFACES + RHYTHM come from the same system: the band is .mkt-band
// (padding + scroll-margin) with .mkt-band--ruled for the faded top hairline
// that replaced a full-bleed `border-t`, the measure is .mkt-band__inner, the
// heading cluster is .mkt-head--center (it owns the eyebrow→h2→sub spacing),
// and the panel is .mkt-card--raised. The panel is NOT .mkt-card--interactive:
// nothing in it is clickable, and that modifier's hover lift would promise an
// affordance this band does not have.
//
// The panel's INTERNAL hairlines (the rule under the counter strip, the rules
// between lane rows) are `var(--mkt-line)` — the same token .mkt-card paints
// its own edge with — rather than `border-border/60`. An internal divider at a
// different weight and hue from the card edge enclosing it is what made these
// panels read as assembled from two different kits.
//
// The two surfaces that stay hand-rolled, deliberately: the per-lane BADGE and
// the per-lane CHANNEL CHIP. Both carry a lane-coloured tint (TONE below), and
// .mkt-pill / .mkt-card pin background, border-color and color UNLAYERED, so
// either class would silently beat the tint utilities sitting next to it and
// flatten all lanes to one colour. The lane colour is the information here, so
// the tint wins and the system class stays off.
//
// The big "9" in the counter strip is a display numeral, not a text role: no
// scale class covers its SIZE, so it keeps an explicit one. Its FACE is now
// .mkt-num — the system's role for a number that is a fact — which is Geist
// Mono with tabular figures, shared with the lane counts in the meter legend
// so every count on this band reads as the same kind of thing. It is
// font-semibold, not font-bold, because layout.tsx loads Geist Mono at
// 400/500/600 only and a 700 here would be a synthesized faux-bold.

// 2026-09-12: WhatsApp moved from PLAN_GATED to FREE. `whatsapp_allowed` is
// TRUE on the FREE row as of migration V225 (product decision: "lets make
// whatsapp also free"), so there is no longer ANY plan-gated channel — see the
// updated ledger above. PLAN_GATED_KEYS is kept as an empty list rather than
// deleted: the lane machinery below still supports a gated lane, so re-gating
// a channel later is a one-word change, and an empty lane is filtered out of
// every render (VISIBLE_LANES) instead of drawing an empty block.
// 2026-09-12b: ALL NINE channels are now in the free lane, not three.
//
// This closes a contradiction this band was shipping against the hero directly
// above it. The hero's `heroUniversal_channelsNote` now states that all nine
// channels are included on every plan — which is what the schema actually says
// — while this wall still rendered a 3-chip free lane whose own
// `cw_lane1_note` interpolates `{count}` from `lane.keys.length`. So the page
// read "all nine are included" and, one scroll-stop later, "the 3 channels a
// free account can run today". Both sentences were generated from code; only
// one of them was right.
//
// The schema settles it: `whatsapp_allowed` was the ONLY channel plan-gate that
// ever existed in `plan_limits`, and migration V225 set it TRUE on the FREE
// row. There is no column, flag or check that gates any of the other eight — a
// fact confirmed independently by product decision the same day. The six
// "bring your own account" channels were never PAID; they are CREDENTIAL-gated,
// which is a different claim, and conflating the two is what produced the old
// 3/6 split. `{count}` now resolves to 9 and `cw_lane1_note` reads true.
//
// CONSEQUENCE TO KNOW ABOUT: BYO_KEYS is derived from whatever is left over, so
// it is now empty, and `VISIBLE_LANES` (the `keys.length > 0` filter below)
// drops lane 3 from the DOM entirely — taking `cw_lane3_note`'s "connect your
// own Meta/Slack/Discord/X/voice account" explanation off this band with it.
// That explanation still reaches the visitor twice, via `cw_sub`/`cw_footnote`
// here and via `heroUniversal_channelsNote` in the hero, so no claim is lost —
// but if the credential nuance should be visible ON the wall again, the right
// fix is a per-chip marker inside the single lane, NOT re-splitting the lanes,
// because re-splitting would reintroduce exactly the free-vs-paid misreading
// this change removes.
const FREE_KEYS: readonly BrandKey[] = CHANNEL_KEYS;
const PLAN_GATED_KEYS: readonly BrandKey[] = [];
// Derived, never re-typed — see the ledger above.
const BYO_KEYS: readonly BrandKey[] = CHANNEL_KEYS.filter(
  (key) => !FREE_KEYS.includes(key) && !PLAN_GATED_KEYS.includes(key)
);

const TOTAL = CHANNEL_KEYS.length;

type LaneTone = "free" | "plan" | "byo";

interface Lane {
  id: LaneTone;
  keys: readonly BrandKey[];
  icon: typeof Check;
  labelKey: string;
  /** Short uppercase pill, e.g. "Free". */
  badgeKey: string;
  /** Long form used in the meter legend, e.g. "free on the free plan". */
  legendKey: string;
  noteKey: string;
}

const LANES: Lane[] = [
  {
    id: "free",
    keys: FREE_KEYS,
    icon: Check,
    labelKey: "cw_lane1_label",
    badgeKey: "cw_lane1_badge",
    legendKey: "cw_lane1_legend",
    noteKey: "cw_lane1_note",
  },
  {
    id: "plan",
    keys: PLAN_GATED_KEYS,
    icon: Lock,
    // Free mode (lib/freeMode.ts): no 3-month grant exists to describe -
    // the lane copy becomes "free forever" instead of "free for 3 months".
    labelKey: FREE_MODE ? "cwLane2LabelFreeMode" : "cw_lane2_label",
    badgeKey: "cw_lane2_badge",
    legendKey: FREE_MODE ? "cwLane2LegendFreeMode" : "cw_lane2_legend",
    noteKey: FREE_MODE ? "cwLane2NoteFreeMode" : "cw_lane2_note",
  },
  {
    id: "byo",
    keys: BYO_KEYS,
    icon: KeyRound,
    labelKey: "cw_lane3_label",
    badgeKey: "cw_lane3_badge",
    legendKey: "cw_lane3_legend",
    noteKey: "cw_lane3_note",
  },
];

// Per-lane surface classes. Written out in full rather than interpolated so
// Tailwind's scanner sees every literal class name.
const TONE = {
  free: {
    rail: "bg-emerald-500",
    seg: "bg-emerald-500",
    badge: "border-emerald-500/30 bg-emerald-500/10 text-emerald-600",
    icon: "text-emerald-600",
    chip: "border-emerald-500/25 bg-emerald-500/[0.06]",
    dot: "bg-emerald-500",
  },
  plan: {
    rail: "bg-amber-500",
    seg: "bg-amber-500",
    badge: "border-amber-500/30 bg-amber-500/10 text-amber-600",
    icon: "text-amber-600",
    chip: "border-amber-500/25 bg-amber-500/[0.06]",
    dot: "bg-amber-500",
  },
  byo: {
    rail: "bg-primary/60",
    seg: "bg-primary/45",
    badge: "border-border bg-background/70 text-muted-foreground",
    icon: "text-muted-foreground",
    chip: "border-border/70 bg-background/60",
    dot: "bg-primary/45",
  },
} as const;

function ChannelChip({ channelKey, tone }: { channelKey: BrandKey; tone: LaneTone }) {
  const t = useTranslations("landing");
  return (
    // Padding and gap step up at sm, and the mark is 20px (2026-09-11). At
    // 390px the two-column grid gives each chip ~146px; with the previous
    // metrics (gap-2.5 / px-3 / a 35px mark tile) that left ~77px for the
    // label, and "Messenger" — the longest of the nine — ellipsised to
    // "Messeng...". A truncated brand name in a band whose whole job is naming
    // the channels is a bug, not a tight fit. These metrics reclaim ~12px,
    // clearing the longest label with room to spare. `truncate` stays as the
    // backstop for locales whose translation is longer still.
    <li
      className={`flex items-center gap-2 rounded-2xl border px-2.5 py-2.5 transition-colors hover:border-primary/40 sm:gap-2.5 sm:px-3 ${TONE[tone].chip}`}
    >
      <BrandMark brand={channelKey} size={20} decorative />
      <span className="mkt-small min-w-0 truncate font-medium text-foreground/90">
        {t(`cw_ch_${channelKey}`)}
      </span>
    </li>
  );
}

export default function ChannelWall() {
  const t = useTranslations("landing");

  // One meter segment per channel, coloured by lane, in lane order. Reading it
  // left to right is the whole story without a single number having to be
  // parsed. The split it draws is whatever FREE_KEYS / PLAN_GATED_KEYS /
  // BYO_KEYS currently are — deliberately not restated here, because a hard-
  // coded count in a comment is exactly how the copy elsewhere on this page
  // drifted (see the ledger above).
  // A lane with no channels in it renders nothing (PLAN_GATED_KEYS is empty
  // since V225) - filtered once here so the meter, the legend and the lane
  // list below can never disagree about which lanes exist.
  const visibleLanes = LANES.filter((lane) => lane.keys.length > 0);
  const segments: LaneTone[] = visibleLanes.flatMap((lane) => lane.keys.map(() => lane.id));

  return (
    <section id="channels" className="mkt-band mkt-band--ruled">
      <div className="mkt-band__inner">
        <Reveal className="mkt-head mkt-head--center">
          <p className="mkt-eyebrow inline-flex items-center gap-2">
            <Radio className="size-3.5" />
            {t("cw_eyebrow")}
          </p>
          <h2 className="mkt-h2">{t("cw_title", { count: String(TOTAL) })}</h2>
          <p className="mkt-section-sub text-pretty text-foreground/80">
            {t("cw_sub", { count: String(TOTAL) })}
          </p>
        </Reveal>

        <Reveal delay={0.08} className="mt-10">
          <div className="mkt-card mkt-card--raised overflow-hidden">
            {/* ---- Counter strip: the number, then the same number as a meter. */}
            <div className="grid gap-7 border-b border-[color:var(--mkt-line)] bg-secondary/[0.14] p-6 sm:p-8 lg:grid-cols-[auto_1fr] lg:items-center lg:gap-12">
              <div className="flex items-center gap-5">
                <span className="mkt-num text-[4.5rem] font-semibold leading-none text-primary sm:text-[5.5rem]">
                  {TOTAL}
                </span>
                <span className="min-w-0">
                  <span className="mkt-eyebrow block">{t("cw_countLabel")}</span>
                  <span className="mkt-small mt-1.5 block max-w-[16rem] text-muted-foreground">
                    {t("cw_countNote", { count: String(TOTAL) })}
                  </span>
                </span>
              </div>

              <div>
                <p className="mkt-eyebrow">{t("cw_meterLabel")}</p>
                <div className="mt-3 flex gap-1.5" aria-hidden="true">
                  {segments.map((tone, i) => (
                    <span
                      key={`${tone}-${i}`}
                      className={`h-2.5 flex-1 rounded-full ${TONE[tone].seg}`}
                    />
                  ))}
                </div>
                <ul className="mt-3.5 flex flex-wrap gap-x-5 gap-y-2">
                  {visibleLanes.map((lane) => (
                    <li key={lane.id} className="mkt-small flex items-center gap-2 text-muted-foreground">
                      <span className={`size-2.5 shrink-0 rounded-full ${TONE[lane.id].dot}`} />
                      <span>
                        <span className="mkt-num font-semibold text-foreground/90">
                          {lane.keys.length}
                        </span>{" "}
                        {t(lane.legendKey)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>

            {/* ---- One row per VISIBLE lane (empty lanes are filtered out
                    above, so this is not always three). Left rail states the
                    count and the condition; right side carries the marks. */}
            <div className="divide-y divide-[color:var(--mkt-line)]">
              {visibleLanes.map((lane) => {
                const Icon = lane.icon;
                const tone = TONE[lane.id];
                return (
                  <div
                    key={lane.id}
                    className="grid gap-5 p-6 sm:p-8 lg:grid-cols-[17rem_1fr] lg:gap-10"
                  >
                    <div className="flex gap-4">
                      <span className={`mt-1 w-1 shrink-0 rounded-full ${tone.rail}`} aria-hidden="true" />
                      <div className="min-w-0">
                        <span
                          className={`mkt-badge inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 ${tone.badge}`}
                        >
                          <Icon className={`size-3.5 ${tone.icon}`} />
                          {t(lane.badgeKey)}
                        </span>
                        <h3 className="mkt-card-title mt-3">
                          {t(lane.labelKey, { count: String(lane.keys.length) })}
                        </h3>
                        <p className="mkt-small mt-1.5 text-muted-foreground">
                          {t(lane.noteKey, { count: String(lane.keys.length) })}
                        </p>
                      </div>
                    </div>

                    <ul className="grid grid-cols-2 gap-2.5 self-start sm:grid-cols-3">
                      {lane.keys.map((channelKey) => (
                        <ChannelChip key={channelKey} channelKey={channelKey} tone={lane.id} />
                      ))}
                    </ul>
                  </div>
                );
              })}
            </div>
          </div>
        </Reveal>

        <Reveal delay={0.12} className="mx-auto mt-6 max-w-3xl">
          {/* No `leading-relaxed` here: .mkt-caption pins line-height unlayered,
              so a layered leading utility beside it cannot win — see the
              TYPOGRAPHY note in this file's header. */}
          <p className="mkt-caption text-muted-foreground">
            {t("cw_footnote", { count: String(TOTAL), byo: String(BYO_KEYS.length) })}
          </p>
        </Reveal>
      </div>
    </section>
  );
}
