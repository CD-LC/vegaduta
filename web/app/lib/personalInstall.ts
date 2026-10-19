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

/** Surfaces that exist for the one-person install only; any other install does not advertise them. */
const PERSONAL_ONLY_ROUTES: readonly string[] = ["/assistant", "/add-ons"];

const under = (href: string, route: string): boolean => href === route || href.startsWith(`${route}/`);

/**
 * True when {@code href} is a surface this install should not advertise: on a personal install the
 * multi-tenant and platform pages above, on every other install the personal-only ones (the
 * Assistant). Deep links to either keep working.
 */
export function hiddenOnPersonalInstall(href: string): boolean {
  if (!isLocalAuth()) return PERSONAL_ONLY_ROUTES.some((r) => under(href, r));
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
