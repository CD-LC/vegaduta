// Surfaces a one-person (local-auth) install never uses. The rail hides these
// (useNavSections), and /learn drops the matching screen→lesson cards - same
// rule both places, so a hidden screen never keeps a teaching link that leads
// to a console page the owner was deliberately spared.
//
// The pages still exist (deep links work); this list is about NOT advertising
// multi-tenant/platform surfaces on a single box: nothing is shared with
// teammates, there is no public agent directory or A2A trust story, no agent
// commerce or payments, no white-label branding, no MCP clients connecting in,
// no sales-lead funnel, no plans
// page, no machine API keys, no cost/ROI roll-up (billing is off) and no Studio
// generation surface.
import { isLocalAuth } from "./authMode";
import { getPublicEnv } from "./env";

const PERSONAL_HIDDEN_ROUTES: readonly string[] = [
  "/shared-with-me",
  "/directory",
  "/studio",
  "/roi",
  "/admin/agent-trust",
  "/admin/commerce",
  "/admin/payments",
  "/admin/branding",
  "/admin/mcp-servers",
  "/admin/api-keys",
  "/admin/sso",
  "/admin/leads",
  "/features",
  // Folded into /assistant on a personal install (the agent picker, "In background" and "Quick
  // task"); these routes redirect there, so the rail must not list them as separate destinations.
  "/playground",
  "/quick-task",
  "/jobs",
];

/**
 * Surfaces a shared/hosted install does not advertise. /assistant is for every install (the
 * Assistant agent is per user, on any tier). /add-ons is the switchboard on personal and lite -
 * on full every add-on ships installed, so the page drops out of the rail and renders a
 * "managed for you" notice instead of live switches for a server a tenant cannot change.
 * /providers/local-model is personal-only everywhere: guided Ollama setup writes the install's
 * platform pool, which on a tenant-based install belongs to the operator, never a tenant.
 */
const SHARED_ONLY_ROUTES: readonly string[] = ["/providers/local-model"];

const FULL_HIDDEN_ROUTES: readonly string[] = ["/add-ons"];

/** True on the lite tier (`NEXT_PUBLIC_DEPLOY_ENV=lite`): self-hosted, single box, Keycloak auth. */
function isLiteInstall(): boolean {
  return getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV")?.trim().toLowerCase() === "lite";
}

/**
 * True on the full tier (`NEXT_PUBLIC_DEPLOY_ENV=full`): the multi-tenant shape where every
 * add-on and runner already ships. Tenant surfaces that would change what runs on the server
 * (the add-ons switchboard, local-model setup) stay disabled on it.
 */
export function isFullInstall(): boolean {
  return getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV")?.trim().toLowerCase() === "full";
}

/**
 * Local-model setup (Ollama pull/warm/use, the /providers/local-model wizard, the troubleshoot
 * card's pull buttons) belongs to the personal install only: on lite and full the LLM pools are
 * platform infrastructure the operator configures at deploy time, so tenant surfaces neither
 * offer nor trigger them.
 */
export function localModelAllowed(): boolean {
  return isLocalAuth();
}

const under = (href: string, route: string): boolean => href === route || href.startsWith(`${route}/`);

/**
 * True when {@code href} is a surface this install should not advertise: on a personal install the
 * multi-tenant and platform pages above; a shared install hides nothing here. Deep links keep
 * working either way.
 */
export function hiddenOnPersonalInstall(href: string): boolean {
  if (!isLocalAuth()) {
    return (
      SHARED_ONLY_ROUTES.some((r) => under(href, r)) ||
      (isFullInstall() && FULL_HIDDEN_ROUTES.some((r) => under(href, r)))
    );
  }
  return PERSONAL_HIDDEN_ROUTES.some((r) => under(href, r));
}

/**
 * A personal install is a one-person, local-auth install. On it the "platform pool" row
 * that every agent routes through IS the Ollama on this machine (LocalLlmPoolBootstrap and
 * POST /api/local-models/use write that row), so nothing about it is "platform-hosted" or
 * "shared". Every screen that names the pool goes through {@link poolLabel} so the wording
 * flips in one place.
 */
export function isPersonalInstall(): boolean {
  return isLocalAuth();
}

/**
 * The vegaduta CLI command that fits this install's shape - `personal` on the one-user
 * install, `up --lite`/`up --full` on the Keycloak tiers. Mirrors the backend's
 * apply-command switch (AddonService.BASE_COMMAND_*): the flag tail (`rest`) reads
 * identically on every tier, e.g. "--local-ai=host" or "--browser".
 */
export function tierCommand(rest = ""): string {
  const base = isLocalAuth()
    ? "scripts/vegaduta personal"
    : isLiteInstall()
      ? "scripts/vegaduta up --lite"
      : "scripts/vegaduta up --full";
  return rest ? `${base} ${rest}` : base;
}

/** What the pool row is called on a personal install; matches core's bootstrap label (app.local-llm.bootstrap.label). */
export const LOCAL_MODEL_LABEL = "Local model (Ollama)";

/**
 * The label for the shared pool: {@code hosted} on a multi-tenant install, "Local model (Ollama)"
 * on a personal one. Pass the hosted wording a screen already uses; pass {@code local} to override
 * the personal wording for a sentence that needs a different grammatical shape.
 */
export function poolLabel(hosted: string, local: string = LOCAL_MODEL_LABEL): string {
  return isPersonalInstall() ? local : hosted;
}

/**
 * The "Use platform LLM" tenant toggle and its upsell copy only make sense where there is a
 * hosted pool to opt into. On a personal install the pool is the local model and always on.
 */
export function showPlatformPoolToggle(): boolean {
  return !isPersonalInstall();
}

/**
 * Workflow nodes whose backend a one-person install does not run: a graph holding one fails validation or can
 * never finish. `wait` needs app.workflow.wait-node.enabled (off), `voice_menu` a Twilio account and a public
 * webhook, `bulk_email` an SMTP relay, `crm_lead` the Leads CRM (hidden on this install), `device_control` a
 * paired phone. The palette stops offering them; a saved graph that already contains one still opens.
 */
const PERSONAL_UNAVAILABLE_WORKFLOW_NODES: readonly string[] = ["wait", "voice_menu", "bulk_email", "crm_lead", "device_control"];

/** False for a workflow node type the palette should not offer on this install. */
export function workflowNodeOffered(type: string): boolean {
  return !(isLocalAuth() && PERSONAL_UNAVAILABLE_WORKFLOW_NODES.includes(type));
}
