// Tour step type: the catalogue row shape. Split out of TourProvider.tsx so the
// step data (./steps/*) and the engine can both import it without a cycle.
import type { TourAccess, TourAudience } from "./audience";
import type { TourIllustrationName } from "./TourIllustrations";

export type TourStep = {
  id: string;
  /** en.json `tour` namespace stem: `${titleKey}Title` / `${titleKey}Body` /
   * `${titleKey}Point1` / `${titleKey}Point2` are the rendered strings.
   *
   * `title`/`body`/`points` below are a MIRROR of those four English strings,
   * nothing else — no code path renders them. They exist so a reviewer can
   * read the tour's actual wording here rather than paging through a 6000-line
   * en.json, and so a step added without its keys is visibly incomplete.
   *
   * They drifted once already and it cost a completeness review to catch
   * (2026-09): in-code the first step said "Let's create your first agent —
   * free, no API key needed." while the key rendered "Welcome to VegaDūta",
   * and most of the other eight disagreed too, so this block was actively
   * misleading about what ships. If you change a string here you MUST change
   * the matching en.json key in the same edit, and vice versa. tour.test.tsx
   * asserts BYTE EQUALITY between these fields and en.json, which is the
   * thing that stops the drift for good. */
  titleKey: string;
  title: string;
  body: string;
  /** The two bullets under the body. Mirror only — see `titleKey`. Rendered
   * from `${titleKey}Point1` / `${titleKey}Point2`. */
  points: [string, string];
  /** Matches an element's data-tour="<target>" attribute, found via
   * querySelector and re-checked on route change / DOM mutation, so a step
   * whose target lives on a different route than the previous one still
   * resolves once the user (or a step's own action) navigates there.
   * `null` means the step has no anchor and renders as a centered hero.
   *
   * A non-null target is ALSO the reachability test (selectSteps' rule 4):
   * a step whose anchor is not in the DOM when the tour starts is dropped
   * for this visitor, so the tour can never spotlight a row they do not
   * have. That is what retired the old "Show me" dead end on Channels. */
  target: string | null;
  placement: "bottom" | "top" | "right" | "left" | "center";
  illustration: TourIllustrationName;
  /** Optional route the step's "Show me" button navigates to. Only set where
   * the destination is a real, verified route that the step is genuinely
   * about — a "Show me" that lands somewhere the copy did not promise is
   * worse than no button at all. */
  href?: string;
  /** Which audiences this step is for. Resolved from Keycloak roles alone
   * (see resolveAudience): "platform-admin" is the cross-tenant super-admin,
   * "tenant-admin" holds ADMIN_ROLE inside one tenant, "member" is everyone
   * else. Never empty — a step nobody is tagged for is a step nobody sees. */
  audiences: TourAudience[];
  /** Real entitlement gate, evaluated against GET /api/tenant/settings (via
   * AppChromeProvider's single shared fetch). Present ONLY where a flag on
   * that response genuinely decides whether the feature this step describes
   * exists for this tenant.
   *
   * Absent from every step except "edge" (2026-09-19: gated on the build-time
   * NEXT_PUBLIC_EDGE_AGENTS flag, which is not a tenantSettings field at all),
   * and for the tenantSettings flags that is a finding
   * rather than an omission — every flag the response carries was checked
   * against what the steps actually describe:
   *   byokAllowed      — gates bring-your-own-key. No step mentions BYOK;
   *                      the start step says the opposite (a hosted model is
   *                      included), which is true on every plan.
   *   whatsappAllowed  — gates one CHANNEL, not the Channels screen. A
   *                      tenant without it still has Telegram, Slack, email,
   *                      Instagram and Discord, so gating the whole step on
   *                      it would hide a section they can reach. The bullet
   *                      that names WhatsApp is a COPY problem, handled by
   *                      keying the rendered string off access, not by
   *                      deleting the step.
   *   liveVisionAllowed / whiteLabel — no step describes either feature.
   *   maxAgents / maxWorkflows / maxKbCollections / maxUsers — capacity
   *                      caps, never zero on any plan (features/page.tsx's
   *                      matrix: FREE is 5/3/1/2), so none of them makes a
   *                      screen unreachable.
   * Add one the moment a step describes something a plan can switch OFF.
   * Do NOT invent a gate to give this field something to do. */
  requires?: (access: TourAccess) => boolean;
  /** Survives SIMPLE experience mode. When the tenant (or this visitor's
   * workspace) runs experienceMode SIMPLE, selectSteps keeps ONLY steps
   * marked true. SIMPLE does not hide nav rows — AppShell filters on
   * isAdmin, not on mode — so this is an editorial judgement, not a
   * reachability one: the steps left out (Integrations' MCP servers and code
   * tools, the audit log, the admin control room) are precisely the screens
   * SIMPLE mode exists to not put in front of someone. Workflows are IN the
   * simple walk — the launch story (agent -> workflow -> app/channel)
   * needs them. */
  simple?: boolean;
  /** INLINE COPY (2026-09-19). `true` means this step has NO en.json keys yet
   * and `title`/`body`/`points` above are what the visitor actually reads -
   * for this step they are the render source, not a mirror.
   *
   * Why this exists at all: en.json is written by the locale pipeline and by
   * several sessions at once, and the session that added the Edge AI step did
   * not own it. A step whose keys do not exist renders "tour.edgeTitle" on the
   * first screen a new signup sees, so the step ships its English here instead.
   * TourOverlay still asks for `${titleKey}Title` FIRST and only falls back to
   * these strings when the key is missing, so the day the four keys land in
   * en.json the translated copy wins with no code change - and tour.test.tsx
   * then holds these fields byte-identical to the keys, exactly as it does
   * for every other step. Do not set this on a step whose keys exist. */
  inline?: boolean;
};
