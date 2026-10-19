"use client";

import { Bot, MessageSquare, Database, BarChart3, Workflow, Sparkles, LayoutGrid, GitBranch } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { CommunityTemplateKind, CommunityTemplateStatus } from "../../lib/api";

// -------------------------------------------------------------------------
// The single source of truth that pairs each template category with its
// design-system accent token AND a mandatory icon. Per DESIGN-SYSTEM-CONTRACT
// §2's one hard rule, colour is NEVER the only cue - every category carries an
// icon so it survives greyscale/CVD. Category ids mirror the six --cat-* hues.
// -------------------------------------------------------------------------
export interface TemplateCategoryMeta {
  id: string;
  label: string;
  icon: LucideIcon;
  /** `--cat-*` token, e.g. "var(--cat-data)". */
  cat: string;
  description: string;
}

export const TEMPLATE_CATEGORIES: TemplateCategoryMeta[] = [
  {
    id: "assistant",
    label: "Assistants",
    icon: Bot,
    cat: "var(--cat-assistant)",
    description: "Conversational and general-purpose agents",
  },
  {
    id: "comms",
    label: "Channels & messaging",
    icon: MessageSquare,
    cat: "var(--cat-comms)",
    description: "WhatsApp, chat and delivery flows",
  },
  {
    id: "data",
    label: "Knowledge & data",
    icon: Database,
    cat: "var(--cat-data)",
    description: "Retrieval, storage and knowledge bases",
  },
  {
    id: "analysis",
    label: "Analysis & quality",
    icon: BarChart3,
    cat: "var(--cat-analysis)",
    description: "Evals, reporting and quality checks",
  },
  {
    id: "automation",
    label: "Automation",
    icon: Workflow,
    cat: "var(--cat-automation)",
    description: "Triggers, schedules and multi-step workflows",
  },
  {
    id: "creative",
    label: "Creative",
    icon: Sparkles,
    cat: "var(--cat-creative)",
    description: "Image, voice and video generation",
  },
];

const BY_ID = new Map(TEMPLATE_CATEGORIES.map((c) => [c.id, c]));

// A category id the web app doesn't know yet (the backend can add categories
// without a web deploy) still renders a valid chip - neutral icon, assistant
// accent, a humanised label - rather than crashing or showing a bare id.
export const FALLBACK_CATEGORY: TemplateCategoryMeta = {
  id: "other",
  label: "Other",
  icon: LayoutGrid,
  cat: "var(--cat-assistant)",
  description: "Uncategorised",
};

function prettyLabel(id: string): string {
  return id.replace(/[-_]/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

export function categoryMeta(id: string | null | undefined): TemplateCategoryMeta {
  if (!id) return FALLBACK_CATEGORY;
  return BY_ID.get(id) ?? { ...FALLBACK_CATEGORY, id, label: prettyLabel(id) };
}

// -------------------------------------------------------------------------
// Kind (agent vs workflow) - also icon-backed, small metadata used in filters
// and on cards.
// -------------------------------------------------------------------------
export interface TemplateKindMeta {
  id: CommunityTemplateKind;
  label: string;
  icon: LucideIcon;
}
// ids are the UPPERCASE wire values (Java enum), not display strings.
export const TEMPLATE_KINDS: TemplateKindMeta[] = [
  { id: "AGENT", label: "Agents", icon: Bot },
  { id: "WORKFLOW", label: "Workflows", icon: GitBranch },
];
export function kindMeta(kind: CommunityTemplateKind): TemplateKindMeta {
  return TEMPLATE_KINDS.find((k) => k.id === kind) ?? TEMPLATE_KINDS[0];
}

// -------------------------------------------------------------------------
// Submission lifecycle status -> state hue. Mapping is fixed by the templates
// contract: pending=queued, approved=success, rejected=error, draft=idle.
// State is expressed as a .state-pill (shape+hue), never colour alone.
// -------------------------------------------------------------------------
export interface StatusMeta {
  label: string;
  /** `--state-*` token. */
  state: string;
  description: string;
}
// Keys are the UPPERCASE wire values (Java enum), not display strings.
export const STATUS_META: Record<CommunityTemplateStatus, StatusMeta> = {
  DRAFT: {
    label: "Draft",
    state: "var(--state-idle)",
    description: "Not submitted yet - only you can see this.",
  },
  PENDING: {
    label: "In review",
    state: "var(--state-queued)",
    description: "Submitted - waiting for a moderator to review it.",
  },
  APPROVED: {
    label: "Approved",
    state: "var(--state-success)",
    description: "Live in the public gallery.",
  },
  REJECTED: {
    label: "Rejected",
    state: "var(--state-error)",
    description: "Needs changes before it can go live.",
  },
  // --state-idle, deliberately NOT --state-warning: a withdrawn template was
  // legitimately public and then pulled, which is a neutral lifecycle fact
  // rather than a problem the owner caused. --state-warning stays reserved
  // for states that actually need the owner's attention (per coordinator
  // direction, 2026-07-30).
  WITHDRAWN: {
    label: "Withdrawn",
    state: "var(--state-idle)",
    description: "No longer public - this template was pulled from the gallery.",
  },
};

export function statusMeta(status: CommunityTemplateStatus): StatusMeta {
  return STATUS_META[status] ?? STATUS_META.DRAFT;
}
