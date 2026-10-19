import { isBillingEnabled } from "../billing-flag";
// Turns whatever a failed Build threw into something a first-time visitor can act on:
// what happened, the most likely reason, and what to do next - never just the raw
// server string. Pure (no React, no fetch) so every branch is unit-tested.
//
// The raw text is NOT thrown away: it is returned as `details` so support and
// curious users can still read the exact message. Nothing here is guessed as fact -
// causes are worded "most likely" unless the HTTP status alone proves them.

export type BuildErrorAction = { label: string; href: string };

export type BuildErrorExplanation = {
  /** Short, plain headline. */
  title: string;
  /** What happened, in one sentence. */
  what: string;
  /** Why it most likely happened. */
  why: string;
  /** Concrete things to try, most useful first. */
  fixes: string[];
  /** A page that resolves it, when there is one. */
  action?: BuildErrorAction;
  /** True when the fix is a paid plan: the panel then offers the in-app upgrade dialog. */
  upgrade?: boolean;
  /** Whether pressing Retry can plausibly succeed without changing anything. */
  retryable: boolean;
  /** The raw message, for the "technical details" fold-out. */
  details: string;
  kind:
    | "session"
    | "permission"
    | "limit"
    | "model"
    | "busy"
    | "offline"
    | "invalid"
    | "conflict"
    | "git"
    | "server"
    | "unknown";
};

export type RawBuildError = { message?: unknown; status?: unknown; errors?: unknown } | string | null | undefined;

function parts(raw: RawBuildError): { message: string; status: number | null } {
  if (raw == null) return { message: "", status: null };
  if (typeof raw === "string") return { message: raw, status: null };
  const message = typeof raw.message === "string" ? raw.message : "";
  const status = typeof raw.status === "number" ? raw.status : null;
  return { message, status };
}

const has = (text: string, ...needles: string[]) => needles.some((n) => text.includes(n));

/** `output` decides which side of the product the wording talks about. */
export function explainBuildError(raw: RawBuildError, output: "agent" | "app" = "agent"): BuildErrorExplanation {
  const { message, status } = parts(raw);
  const m = message.toLowerCase();
  const details = message.trim() || (status ? `HTTP ${status}` : "No message was returned.");
  const noun = output === "app" ? "app" : "agent";

  if (status === 401 || has(m, "unauthorized", "session expired", "not signed in", "token expired")) {
    return {
      kind: "session",
      title: "Your sign-in has expired",
      what: `We could not create your ${noun} because the platform no longer recognises your session.`,
      why: "Sessions end after a while, or when you sign in from another device.",
      fixes: ["Sign in again - your blueprint is kept on this page, so you will not lose it.", "Then press Build again."],
      action: { label: "Sign in again", href: "/login" },
      retryable: false,
      details,
    };
  }

  // A plan-limit refusal can arrive as 400 or 403 ("over your plan's limit of 5 agents"),
  // so it is recognised by wording before the role and validation checks claim it.
  const planLimit = /(?:over|exceed\w*|reach\w*)\s+(?:your\s+)?plan'?s?\s+limit|plan'?s\s+limit\s+of\s+\d+|plan limit/.exec(m);
  if (status === 402 || planLimit || has(m, "quota", "limit reached", "upgrade", "maximum number", "exceeded your")) {
    const cap = /limit of (\d+)\s+(\w+)/.exec(m);
    const adds = /adds (\d+)\s+(\w+?)\(?s?\)?\s+to your existing/.exec(m);
    const what = cap
      ? `Your plan allows ${cap[1]} ${cap[2]}${adds ? ` and this blueprint would add ${adds[1]} more` : ""}, so it cannot be created yet.`
      : `Your workspace cannot hold another ${noun} on its current plan.`;
    return {
      kind: "limit",
      title: "You have reached your plan limit",
      what,
      why: "Each plan allows a fixed number of agents, workflows and connected tools. Nothing was created and your blueprint is kept.",
      // Billing off (lib/billing-flag.ts): same message, no upgrade path -
      // the limit is the operator's to raise, and /usage shows where it sits.
      fixes: isBillingEnabled()
        ? ["Upgrade your plan to raise the limit - it takes a minute.", "Or delete an agent you no longer use, then press Build again.", "Or go back and remove an agent from the blueprint."]
        : ["Delete an agent you no longer use, then press Build again.", "Or go back and remove an agent from the blueprint."],
      action: isBillingEnabled() ? { label: "See plans and usage", href: "/billing" } : { label: "See usage", href: "/usage" },
      upgrade: isBillingEnabled(),
      retryable: false,
      details,
    };
  }

  if (status === 403 || has(m, "forbidden", "not allowed", "permission", "access denied", "insufficient")) {
    return {
      kind: "permission",
      title: "Your role cannot create this",
      what: `The platform refused to create the ${noun} for your account.`,
      why: "Creating agents, channels or secrets needs an owner or admin role in the workspace.",
      fixes: ["Ask a workspace owner or admin to build it, or to raise your role.", "Everything you have set up in the blueprint stays here while you sort it out."],
      action: { label: "Open access settings", href: "/admin/access" },
      retryable: false,
      details,
    };
  }

  if (has(m, "provider", "api key", "apikey", "no model", "model is not", "llm", "anthropic", "openai", "credentials", "hosted model")) {
    return {
      kind: "model",
      title: "The agent has no AI model to think with",
      what: `The ${noun} could not be created because no working AI model is connected.`,
      why: "An agent needs a model to answer. Either your own provider key is missing or invalid, or the platform's hosted model is switched off for this workspace.",
      fixes: ["Add a provider key (Anthropic, OpenAI, Gemini and more) - it takes a minute.", "Or turn on the free hosted model if your plan includes it.", "Then come back and press Build again."],
      action: { label: "Connect a model", href: "/providers" },
      retryable: false,
      details,
    };
  }

  if (output === "app" && has(m, "git", "repository", "repo", "github", "gitlab", "branch", "pull request", "app-builder", "app builder")) {
    return {
      kind: "git",
      title: "The app could not be saved to your Git",
      what: "The app builder could not create the branch and pull request in your repository.",
      why: "Your Git account is not connected, or the connection no longer has permission to write to the repository.",
      fixes: ["Connect (or reconnect) your GitHub or GitLab account.", "Make sure the token can create branches and pull requests.", "Then press Build again."],
      action: { label: "Connect GitHub", href: "/build/github" },
      retryable: false,
      details,
    };
  }

  if (status === 409 || has(m, "already exists", "duplicate", "conflict", "name is taken", "already taken")) {
    return {
      kind: "conflict",
      title: "Something with this name already exists",
      what: `We could not create the ${noun} because an existing one already uses that name.`,
      why: "Names must be unique inside a workspace.",
      fixes: ["Go back and give it a different name.", "Or open the existing one and edit it instead."],
      action: { label: "See existing agents", href: "/agents" },
      retryable: false,
      details,
    };
  }

  if (status === 400 || status === 422 || has(m, "invalid", "schema", "validation", "must be", "cannot be empty", "malformed")) {
    return {
      kind: "invalid",
      title: "One of the settings is not valid",
      what: "The platform checked your blueprint and rejected a setting.",
      why: "A value is out of range, empty, or in a format the platform does not accept.",
      fixes: ["Go back to the blueprint and look for anything empty or unusual - names, channel choices, secret names, schedules.", "The detail below usually names the exact field."],
      retryable: false,
      details,
    };
  }

  if (status === 429 || has(m, "too many requests", "rate limit")) {
    return {
      kind: "busy",
      title: "Too many requests at once",
      what: "The platform asked us to slow down.",
      why: "Several builds or messages were sent in a short time.",
      fixes: ["Wait about a minute, then press Retry."],
      retryable: true,
      details,
    };
  }

  if (status === 502 || status === 503 || status === 504 || has(m, "restarting", "briefly overloaded", "timed out", "timeout", "gateway", "temporarily unavailable", "service unavailable", "try again shortly")) {
    return {
      kind: "busy",
      title: "The service is busy for a moment",
      what: "The platform did not answer in time, so nothing was finished.",
      why: "It is most likely restarting or handling a spike - this usually clears within a minute.",
      fixes: ["Press Retry in a few seconds.", "If it keeps happening, check the status page."],
      action: { label: "Check status", href: "/status" },
      retryable: true,
      details,
    };
  }

  if (status === 0 || has(m, "failed to fetch", "networkerror", "network error", "unreachable", "load failed", "offline")) {
    return {
      kind: "offline",
      title: "We could not reach the platform",
      what: "The request never arrived, so nothing was created.",
      why: "Your connection dropped, or a browser extension or firewall is blocking the request.",
      fixes: ["Check your internet connection.", "Turn off any ad-blocker or VPN for this site, then press Retry."],
      retryable: true,
      details,
    };
  }

  if (status != null && status >= 500) {
    return {
      kind: "server",
      title: "Something went wrong on our side",
      what: `The platform hit an error while building your ${noun}. It has been logged.`,
      why: "This is not caused by anything you entered.",
      fixes: ["Press Retry - it often works the second time.", "If it fails again, copy the details below and send them to support."],
      retryable: true,
      details,
    };
  }

  return {
    kind: "unknown",
    title: "The build did not finish",
    what: `Your ${noun} was not created.`,
    why: message ? "The platform returned a message we do not recognise - it is shown below." : "The platform did not say why.",
    fixes: ["Press Retry.", "If it fails again, go back to the blueprint and simplify it, or copy the details below and send them to support."],
    retryable: true,
    details,
  };
}

/** Friendly names for the apply engine's step kinds ("CREATE_AGENT" -> "Creating your agent"). */
const STEP_LABELS: Record<string, string> = {
  AGENT: "Creating your agent",
  CREATE_AGENT: "Creating your agent",
  KNOWLEDGE: "Setting up the knowledge base",
  KNOWLEDGE_BASE: "Setting up the knowledge base",
  PROCEDURE: "Writing the step-by-step procedure",
  WORKFLOW: "Building the workflow",
  TRIGGER: "Adding the trigger",
  CHANNEL: "Preparing the channels",
  SECRET: "Reserving secret slots in the vault",
  TOOL: "Adding tools",
  MCP: "Connecting integrations",
  MCP_SERVER: "Connecting integrations",
  EVAL_SET: "Adding starter test questions",
  EVALSET: "Adding starter test questions",
  TRUST: "Putting it under watch",
  GUARDRAIL: "Adding safety rules",
};

export function stepLabel(kind: string): string {
  const key = kind.trim().toUpperCase().replace(/[\s-]+/g, "_");
  if (STEP_LABELS[key]) return STEP_LABELS[key];
  const words = kind
    .replace(/[_-]+/g, " ")
    .toLowerCase()
    .trim();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "Working";
}
