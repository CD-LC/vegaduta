// Install-shape helpers for the personal edition. The edition itself comes from lib/edition.ts
// (NEXT_PUBLIC_EDITION, falling back to local auth for an older image), and the list of surfaces a
// one-person install does not advertise lives in its EDITION_ROUTES table - the same table the
// deep-link gate (lib/editionCapabilities.ts) and the proxy read, so a hidden screen never keeps a
// link that leads to a page the owner was deliberately spared.
//
// The rail hides those pages (useNavSections); a deep link shows the "not available" state.
import { isFullEdition, isLiteEdition, isPersonalEdition, railHidden, underRoute } from "./edition";

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

/**
 * True on the full tier (`NEXT_PUBLIC_EDITION=full`, or the older `NEXT_PUBLIC_DEPLOY_ENV=full`): the
 * multi-tenant shape where every add-on and runner already ships. Tenant surfaces that would change
 * what runs on the server (the add-ons switchboard, local-model setup) stay disabled on it.
 */
export function isFullInstall(): boolean {
  return isFullEdition();
}

/**
 * Local-model setup (Ollama pull/warm/use, the /providers/local-model wizard, the troubleshoot
 * card's pull buttons) belongs to the personal install only: on lite and full the LLM pools are
 * platform infrastructure the operator configures at deploy time, so tenant surfaces neither
 * offer nor trigger them.
 */
export function localModelAllowed(): boolean {
  return isPersonalEdition();
}

/**
 * True when {@code href} is a surface this install should not advertise: on a personal install the
 * multi-tenant and platform pages in EDITION_ROUTES (lib/edition.ts); on a shared install only the
 * personal-only and full-only pages above.
 */
export function hiddenOnPersonalInstall(href: string): boolean {
  if (!isPersonalEdition()) {
    return (
      SHARED_ONLY_ROUTES.some((r) => underRoute(href, r)) ||
      (isFullInstall() && FULL_HIDDEN_ROUTES.some((r) => underRoute(href, r)))
    );
  }
  return railHidden(href);
}

/**
 * A personal install is a one-person, local-auth install. On it the "platform pool" row
 * that every agent routes through IS the Ollama on this machine (LocalLlmPoolBootstrap and
 * POST /api/local-models/use write that row), so nothing about it is "platform-hosted" or
 * "shared". Every screen that names the pool goes through {@link poolLabel} so the wording
 * flips in one place.
 */
export function isPersonalInstall(): boolean {
  return isPersonalEdition();
}

/**
 * The vegaduta CLI command that fits this install's shape - `personal` on the one-user
 * install, `up --lite`/`up --full` on the Keycloak tiers. Mirrors the backend's
 * apply-command switch (AddonService.BASE_COMMAND_*): the flag tail (`rest`) reads
 * identically on every tier, e.g. "--local-ai=host" or "--browser".
 */
export function tierCommand(rest = ""): string {
  const base = isPersonalEdition()
    ? "scripts/vegaduta personal"
    : isLiteEdition()
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
const PERSONAL_UNAVAILABLE_WORKFLOW_NODES: readonly string[] = [
  "wait",
  "voice_menu",
  "bulk_email",
  "crm_lead",
  "device_control",
];

/** False for a workflow node type the palette should not offer on this install. */
export function workflowNodeOffered(type: string): boolean {
  return !(isPersonalEdition() && PERSONAL_UNAVAILABLE_WORKFLOW_NODES.includes(type));
}
