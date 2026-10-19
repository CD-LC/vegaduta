import type { LucideIcon } from "lucide-react";
import {
  Anchor,
  Ban,
  Building2,
  Database,
  Eye,
  Globe,
  KeyRound,
  Pencil,
  RefreshCw,
  ShieldCheck,
  Upload,
  Footprints,
} from "lucide-react";

/**
 * Tool classification taxonomy — the one place the console decides how a
 * tool's `trust × sensitivity × determinism` declaration is named, explained
 * and coloured. Build-agenda item 3 ("First-class tool classification
 * registry ... non-nullable").
 *
 * WHY this is a separate, JSX-free module rather than constants inside the
 * panel: three different surfaces need exactly the same wording and the same
 * hue for a given tier — the table cell, the legend that explains the tier,
 * and the filter chip that selects for it. Any drift between those three is
 * a correctness bug on a safety screen, not a cosmetic one: a legend that
 * says "EGRESS means data leaves the platform" next to a pill rendered in the
 * same colour as READ_PUBLIC teaches the reader the opposite of the truth.
 * Keeping the copy and the severity in one table makes that drift impossible.
 *
 * WHY the copy is this plain: the audience is explicitly a non-expert tenant
 * admin deciding whether an agent should be allowed to call a tool. "EGRESS"
 * means nothing to them; "sends data out of the platform — email, message or
 * webhook" does. The enum code is still shown, in monospace, because it is
 * what an engineer will grep for and what a support conversation will quote.
 *
 * The enum values below are the wire contract agreed with the backend work
 * (`tool_classification(trust, sensitivity, determinism, required_scope,
 * irreversible, needs_tool_context)`). The backend is being built in
 * parallel; nothing here invents a classification for a tool — every tier
 * rendered comes from a row the API returned.
 */

/** Where a tool's *output* comes from, i.e. how much the platform can trust
 *  the bytes it puts back into the model's context. */
export type ToolTrust = "TRUSTED_INTERNAL" | "TENANT_SCOPED" | "UNTRUSTED_EXTERNAL";

/** What the tool is allowed to touch when it runs. */
export type ToolSensitivity = "READ_PUBLIC" | "READ_TENANT" | "WRITE_TENANT" | "CREDENTIALED" | "EGRESS";

/** What happens if the same call is made twice — the retry-safety axis. */
export type ToolDeterminism = "PURE" | "SIDE_EFFECT" | "DURABLE";

/**
 * Pill hue. Deliberately mapped onto the app's `--state-*` tokens rather than
 * raw Tailwind colours so a classification pill reads the same hue as every
 * other status indicator in the console (design-system contract §3), and so
 * both themes are handled by the token ladder instead of per-component
 * `dark:` classes. Contract §2 also forbids colour as the *only* cue, which
 * is why every tier below carries an icon as well.
 */
export type TierSeverity = "safe" | "neutral" | "caution" | "alarm";

export const SEVERITY_STATE_TOKEN: Record<TierSeverity, string> = {
  safe: "var(--state-success)",
  neutral: "var(--state-idle)",
  caution: "var(--state-warning)",
  alarm: "var(--state-error)",
};

export interface TierMeta {
  /** The enum value exactly as the backend stores it. Shown in monospace. */
  code: string;
  /** Two-to-four words; this is what someone scanning the table reads. */
  label: string;
  /** One sentence of plain language: what this tier means *for the reader*. */
  meaning: string;
  severity: TierSeverity;
  icon: LucideIcon;
}

/** Axis headings, phrased as the question the axis actually answers. */
export const AXIS_QUESTIONS = {
  trust: "Where does what it returns come from?",
  sensitivity: "What is it allowed to touch?",
  determinism: "What happens if it runs twice?",
} as const;

export const TRUST_META: Record<ToolTrust, TierMeta> = {
  TRUSTED_INTERNAL: {
    code: "TRUSTED_INTERNAL",
    label: "Platform-internal",
    meaning:
      "Runs inside VegaDūta and returns platform data. Nobody outside your workspace can choose the words it puts in front of the model.",
    severity: "safe",
    icon: ShieldCheck,
  },
  TENANT_SCOPED: {
    code: "TENANT_SCOPED",
    label: "Your own systems",
    meaning:
      "Talks to systems and accounts that belong to you. Its output is your own data, so it is only as trustworthy as whatever put that data there.",
    severity: "neutral",
    icon: Building2,
  },
  UNTRUSTED_EXTERNAL: {
    code: "UNTRUSTED_EXTERNAL",
    label: "Outside content",
    meaning:
      "Returns content from outside your workspace — a web page, a third-party API, someone else's MCP server. Anything it returns must be read as data, never as instructions to follow.",
    severity: "alarm",
    icon: Globe,
  },
};

export const SENSITIVITY_META: Record<ToolSensitivity, TierMeta> = {
  READ_PUBLIC: {
    code: "READ_PUBLIC",
    label: "Reads public info",
    meaning: "Only reads information that is already public. Nothing of yours is read and nothing of yours leaves.",
    severity: "safe",
    icon: Eye,
  },
  READ_TENANT: {
    code: "READ_TENANT",
    label: "Reads your data",
    meaning: "Reads your workspace's own data. It changes nothing, but whatever it reads ends up in the agent's context.",
    severity: "neutral",
    icon: Database,
  },
  WRITE_TENANT: {
    code: "WRITE_TENANT",
    label: "Changes your data",
    meaning: "Creates, edits or deletes data in your workspace. A wrong call leaves a wrong record behind.",
    severity: "caution",
    icon: Pencil,
  },
  CREDENTIALED: {
    code: "CREDENTIALED",
    label: "Uses your credentials",
    meaning:
      "Acts with a credential you stored — so it can do anything that credential is allowed to do in that system, including spending money if the credential can.",
    severity: "alarm",
    icon: KeyRound,
  },
  EGRESS: {
    code: "EGRESS",
    label: "Sends data out",
    meaning:
      "Sends data out of the platform — an email, a message, a webhook, a call to someone else's API. Once it has run, the data is outside your control.",
    severity: "alarm",
    icon: Upload,
  },
};

// Wording and severities transcribed from core/agents' ToolDeterminism enum
// (each constant's own javadoc, plus replayMustUseJournaledResponse()), NOT
// inferred from the tier names. Getting this axis backwards is the documented
// first mistake everyone makes, and an earlier draft of this table made it:
// DURABLE is the tier where repeating the call is a REAL-WORLD DUPLICATE (a
// second invoice, a second email to a real person) and a replay must be served
// from the effect journal without reaching the provider at all. SIDE_EFFECT is
// the milder tier and the honest default for most reads - a re-call is
// permitted there, merely wasteful and possibly divergent. So DURABLE is the
// alarming one, which also means it must outrank SIDE_EFFECT in rowRisk below.
export const DETERMINISM_META: Record<ToolDeterminism, TierMeta> = {
  PURE: {
    code: "PURE",
    label: "Safe to retry",
    meaning: "Same input, same answer, nothing left behind. The platform can retry it automatically after a failure.",
    severity: "safe",
    icon: RefreshCw,
  },
  SIDE_EFFECT: {
    code: "SIDE_EFFECT",
    label: "Repeat is recoverable",
    meaning:
      "Repeating it will not do real-world damage, but it is not guaranteed to give the same answer twice — a retry costs something and can quietly disagree with the first attempt.",
    severity: "neutral",
    icon: Footprints,
  },
  DURABLE: {
    code: "DURABLE",
    label: "Never call twice",
    meaning:
      "Its effect leaves the platform and cannot be taken back — a message sent, a file uploaded, a payment link created. A second call means a second real-world action, so a retry must replay the recorded answer instead of calling again.",
    severity: "alarm",
    icon: Anchor,
  },
};

/**
 * The unclassified state, which is a *blocked* state. It gets its own meta
 * entry (rather than being rendered as three empty cells) because the whole
 * point of the registry is that it fails closed: a tool nobody has declared
 * is a tool agents may not call. Three blank cells read as "fine, nothing to
 * see"; this reads as the refusal it is.
 */
export const UNCLASSIFIED_META: TierMeta = {
  code: "UNCLASSIFIED",
  label: "Unclassified — blocked",
  meaning:
    "Nobody has declared what this tool can do, so agents are not allowed to call it. This is deliberate: an undeclared tool fails closed rather than being trusted by default.",
  severity: "alarm",
  icon: Ban,
};

/** Defensive lookups. The backend may grow a tier this build of the console
 *  has never heard of; returning null lets the caller render "reported as X,
 *  not recognised by this console" instead of crashing on an undefined
 *  `.label`. Never silently coerce an unknown tier into a known one — that
 *  would be the UI inventing a safety claim. */
export function trustMeta(value: ToolTrust | string | null | undefined): TierMeta | null {
  return value ? TRUST_META[value as ToolTrust] ?? null : null;
}

export function sensitivityMeta(value: ToolSensitivity | string | null | undefined): TierMeta | null {
  return value ? SENSITIVITY_META[value as ToolSensitivity] ?? null : null;
}

export function determinismMeta(value: ToolDeterminism | string | null | undefined): TierMeta | null {
  return value ? DETERMINISM_META[value as ToolDeterminism] ?? null : null;
}

/**
 * One tool as the classification endpoint reports it. Every field past
 * `toolName` is optional/nullable on purpose: a core that predates part of
 * the migration can legitimately return a row with, say, no `determinism`
 * yet, and that row must render as *blocked*, not as partially fine.
 */
export interface ToolClassificationRow {
  /** Exact literal name the agent runtime registers and `Agent.allowedToolNames`
   *  would contain (e.g. `google_gmail`). The stable identity for a row. */
  toolName: string;
  /** Human label, when the backend has one. Falls back to `toolName`. */
  displayName?: string | null;
  /** Which registry the tool came from — see `ORIGIN_LABELS`. */
  origin?: string | null;
  /** Set for MCP-discovered tools so the row can point at the owning server. */
  serverId?: string | null;
  serverName?: string | null;
  trust?: ToolTrust | string | null;
  sensitivity?: ToolSensitivity | string | null;
  determinism?: ToolDeterminism | string | null;
  /** Declared "this cannot be undone" flag, independent of the three axes. */
  irreversible?: boolean | null;
  /** OAuth scope / permission the tool needs, when it needs a named one. */
  requiredScope?: string | null;
  /** Whether the tool needs the per-call ToolContext (tenant/user identity). */
  needsToolContext?: boolean | null;
  /** The backend's own verdict that this tool is currently refused. */
  blocked?: boolean | null;
  blockedReason?: string | null;
  classifiedAt?: string | null;
  classifiedBy?: string | null;
}

export interface ToolClassificationListResponse {
  /** `false` means the registry is recording classifications but not yet
   *  refusing unclassified tools — a materially different (and more
   *  dangerous) state than enforcement being on, so the panel says so. Left
   *  undefined by a core that has no such flag; the panel then says nothing
   *  about enforcement rather than guessing. */
  enforced?: boolean | null;
  tools?: ToolClassificationRow[] | null;
  /** Client-side marker: the running core has no classification endpoint
   *  (HTTP 404), mirroring the `{ unsupported: true }` convention
   *  `api.testMcpServer` / `api.acknowledgeMcpServer` already use. */
  unsupported?: boolean;
}

/**
 * Fails closed, in the UI too. A row counts as unclassified unless all three
 * axes are present AND recognised, or the backend explicitly said it is
 * blocked. A half-filled row is not "mostly classified" — the guard that
 * consumes this data needs all three axes to make a decision, so the console
 * must not present a partial row as a cleared one.
 */
export function isUnclassified(row: ToolClassificationRow): boolean {
  if (row.blocked === true) return true;
  return !trustMeta(row.trust) || !sensitivityMeta(row.sensitivity) || !determinismMeta(row.determinism);
}

/** Highest severity across a row's three axes — drives sort order so the
 *  rows someone needs to look at are the rows at the top. */
const SEVERITY_RANK: Record<TierSeverity, number> = { safe: 0, neutral: 1, caution: 2, alarm: 3 };

export function rowRisk(row: ToolClassificationRow): number {
  // Unclassified outranks everything: it is the one state that stops an
  // agent dead, so it is the one state that must never be below the fold.
  if (isUnclassified(row)) return 100;
  const metas = [trustMeta(row.trust), sensitivityMeta(row.sensitivity), determinismMeta(row.determinism)];
  const worst = metas.reduce((acc, meta) => (meta ? Math.max(acc, SEVERITY_RANK[meta.severity]) : acc), 0);
  return worst + (row.irreversible ? 0.5 : 0);
}

export function sortByRisk(rows: ToolClassificationRow[]): ToolClassificationRow[] {
  return [...rows].sort((a, b) => {
    const delta = rowRisk(b) - rowRisk(a);
    if (delta !== 0) return delta;
    return (a.displayName || a.toolName).localeCompare(b.displayName || b.toolName);
  });
}

/** Where the tool is registered. Unknown values fall through to the raw
 *  string — the backend owns this vocabulary and may add to it. */
export const ORIGIN_LABELS: Record<string, string> = {
  BUILT_IN: "Built-in",
  GOOGLE: "Google Workspace",
  MCP_SERVER: "MCP server",
  USER_SCRIPT: "Custom code tool",
  WORKFLOW: "Workflow",
};

export function originLabel(origin: string | null | undefined): string | null {
  if (!origin) return null;
  return ORIGIN_LABELS[origin] ?? origin;
}

/**
 * Filter chips. Each one is a question a tenant admin actually arrives with
 * ("which of my tools can reach the internet?"), not a restatement of an
 * enum name — the enum view is the table itself. Counts are rendered on the
 * chips, which is also how this screen gets its at-a-glance summary without
 * a second row of invented statistics.
 */
export interface ClassificationFilter {
  id: string;
  label: string;
  /** Tooltip-grade explanation of exactly which rows this selects. */
  hint: string;
  match: (row: ToolClassificationRow) => boolean;
}

export const CLASSIFICATION_FILTERS: ClassificationFilter[] = [
  {
    id: "blocked",
    label: "Blocked",
    hint: "Tools with no complete classification. Agents cannot call these at all.",
    match: isUnclassified,
  },
  {
    id: "external",
    label: "Reads outside content",
    hint: "Tools whose output comes from outside your workspace, so it has to be treated as untrusted data.",
    match: (row) => row.trust === "UNTRUSTED_EXTERNAL",
  },
  {
    id: "credentialed",
    label: "Uses your credentials",
    hint: "Tools that act with a credential you stored — they can do whatever that credential can do.",
    match: (row) => row.sensitivity === "CREDENTIALED",
  },
  {
    id: "egress",
    label: "Sends data out",
    hint: "Tools that send data out of the platform — email, message, webhook, third-party API call.",
    match: (row) => row.sensitivity === "EGRESS",
  },
  {
    id: "retry-unsafe",
    label: "Unsafe to retry",
    // DURABLE, not SIDE_EFFECT: per core/agents' ToolDeterminism, a re-call of
    // a SIDE_EFFECT tool is permitted (wasteful, possibly divergent), while a
    // re-call of a DURABLE one is a duplicate in the real world. This chip
    // exists to answer "which of my tools can double-send", so it must select
    // the second set.
    hint: "Tools whose effect leaves the platform — a repeat is a second real-world action, so a retry has to replay the recorded answer instead of calling again.",
    match: (row) => row.determinism === "DURABLE",
  },
  {
    id: "irreversible",
    label: "Cannot be undone",
    hint: "Tools declared irreversible — once the call lands there is no undo.",
    match: (row) => row.irreversible === true,
  },
];
