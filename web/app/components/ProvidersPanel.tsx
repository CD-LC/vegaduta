"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api } from "../lib/api";
import { useAuth } from "./AuthProvider";
import { LLM_PROVIDER_GUIDES, type LlmProviderGuide } from "../lib/credentialGuides";
import { Lightbulb } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Section } from "./ax/page-kit";
import { UpgradeLock } from "./ui/upgrade-lock";
import { Badge } from "./ui/badge";
import { Card } from "./ui/card";
import { cn } from "../lib/utils";
import { useProviderCatalogFilter } from "../providers/ProviderCatalogFilter";
import { ROUTING_MODES, getRoutingMode, type TenantProviderState } from "../lib/edge/routingState";
import { errorMessage } from "../lib/errors";
import { useCloudSpeechOffered } from "../lib/cloudSpeech";
import { isPersonalInstall, showPlatformPoolToggle } from "../lib/personalInstall";
import {
  E2B_SPECIAL_CASED,
  ELEVENLABS_SPECIAL_CASED,
  MULTI_FIELD_PROVIDERS,
  OK_BADGE,
  PROVIDERS,
  PROVIDER_INFO,
  SARVAM_SPECIAL_CASED,
  VERTEX_SPECIAL_CASED,
  WARN_BADGE,
  encodeMultiFieldCredential,
  encodeVertexCredential,
  offeredImplementedProviders,
  offeredProviders,
  type ProviderName,
} from "./providers/providerCatalog";
import { Alert, Toggle } from "./providers/ProviderUi";
import ProviderActivationCard from "./providers/ProviderActivationCard";
import E2bCardExtras from "./providers/E2bCardExtras";
import LocalServerProbe from "./providers/LocalServerProbe";
import ElevenLabsVoicesSection from "./providers/ElevenLabsVoicesSection";
import { useE2bSetup } from "./providers/useE2bSetup";
import { useElevenLabsVoices } from "./providers/useElevenLabsVoices";
import { ProviderCardStatus, ProviderGuideLinks, ProviderKeyActions } from "./providers/ProviderCardParts";

// Re-exported: lib/llmProviders.test.ts and others import the catalogue from here.
export { PROVIDERS };

/**
 * AXON rebuild - no design_handoff_axon mockup exists for this screen
 * (Providers is never one of the 14 handoff files, same gap Integrations
 * had - see GoogleIntegrationPanel's comment). Per that established
 * precedent, this ports onto the existing AXON idioms other rebuilt screens
 * already use rather than inventing a new visual language: the /tools
 * catalog-grid card shape (.ax-tl-grid/.ax-tl-card) for the known
 * providers, and /tools' below-grid inline form (.ax-tl-add-section) for
 * the activation form - not a literal pixel spec, since none exists to be
 * pixel-perfect against.
 */
/** One row of GET /api/providers - an activated (or previously-activated)
 * BYOK LLM credential. `codeExecutionTemplateId` only applies to the E2B
 * special-cased card but lives here too since `activated` below is shared
 * across the generic + special-cased cards. */
interface ProviderRow {
  providerName: ProviderName;
  status: string;
  createdAt?: string;
  codeExecutionTemplateId?: string;
  /** Per-credential health, when core reports it (see lib/providerHealth.ts). */
  health?: unknown;
  recentFailures?: number;
  lastError?: string | null;
}

interface ProvidersPanelProps {
  providers: ProviderRow[];
  onChanged: () => void;
}

export default function ProvidersPanel({ providers, onChanged }: ProvidersPanelProps) {
  const { hasPermission } = useAuth();
  const canManageProviders = hasPermission("MANAGE_INTEGRATIONS");
  // Sarvam and ElevenLabs are core's CLOUD_SPEECH feature: absent on a personal install, where their cards,
  // credential reads and the voice-cloning section would only be 404s.
  const speech = useCloudSpeechOffered();
  const cards = offeredProviders();
  const implementedCards = offeredImplementedProviders();
  const canManageVoice = hasPermission("MANAGE_CHANNELS"); // ElevenLabs key + voices: server-gated on MANAGE_CHANNELS
  const t = useTranslations("providersPanel");
  const [providerName, setProviderName] = useState<ProviderName | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [vertexProjectId, setVertexProjectId] = useState("");
  const [vertexLocation, setVertexLocation] = useState("us-central1");
  const [vertexServiceAccountJson, setVertexServiceAccountJson] = useState("");
  // Provider fan-out (July 2026) - holds { fieldKey: value } for whichever
  // provider in MULTI_FIELD_PROVIDERS is currently selected; unused (stays
  // {}) for every single-apiKey-field provider and for GOOGLE_VERTEX (which
  // keeps its own dedicated vertex* fields above).
  const [multiFieldValues, setMultiFieldValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [sarvamCredential, setSarvamCredential] = useState(null);
  const [e2bCredential, setE2bCredential] = useState(null);
  const [elevenLabsCredential, setElevenLabsCredential] = useState(null);
  // "Give option to edit, delete the added key" - direct feedback. `Edit`
  // just reopens the same activation form via selectProvider() below (every
  // provider's activate call already upserts, so resubmitting IS the edit);
  // `Delete` needs its own two-step confirm (mirrors the Academy learner-
  // unlock confirm/busy pattern elsewhere in this codebase) since it's a
  // destructive action any agent using that provider would immediately feel.
  const [confirmingDeleteProvider, setConfirmingDeleteProvider] = useState(null);
  const [deletingProvider, setDeletingProvider] = useState(null);
  // Direct feedback (issue report): "even provider page should not be
  // editable" for a FREE-tier tenant - FREE tenants never activate a BYOK
  // provider at all (they route through the platform-hosted pool, see
  // AgentWorkbench.tsx's own isFreePlan gating), so this whole page's write
  // path (select a card -> enter a key -> activate) is something a FREE
  // tenant should never be able to reach, same "read-only, not just
  // disabled-with-a-tooltip" posture as the Agents page's provider picker.
  const [tenantPlan, setTenantPlan] = useState(null);
  // #1085 - "every non free tenant should get an option to select use
  // platform llm...where there is a token limit for everyone who uses
  // platform llm and can be upgraded." experienceMode is captured here too
  // (not just plan/usePlatformLlm) because UpdateExperienceModeRequest
  // requires it on every PUT - see setTenantUsePlatformLlm's own comment
  // in lib/api.ts.
  const [experienceMode, setExperienceMode] = useState("ADVANCED");
  const [usePlatformLlm, setUsePlatformLlm] = useState(false);
  const [platformLlmToggleBusy, setPlatformLlmToggleBusy] = useState(false);
  // "application should show what platform llm is configured" - direct
  // feedback. Tenant-facing read (GET /api/providers/platform-llm) of the
  // provider/model actually backing the platform-hosted pool - shown below
  // both freeTierReadOnlyNote (FREE tenants) and the usePlatformLlmToggle
  // (paid tenants who opt in), instead of leaving "platform-hosted LLM" as
  // an unnamed black box in either case.
  const [platformLlmInfo, setPlatformLlmInfo] = useState(null);
  useEffect(() => {
    api
      .getTenantSettings()
      .then((s) => {
        setTenantPlan(s.plan || null);
        setExperienceMode(s.experienceMode || "ADVANCED");
        setUsePlatformLlm(!!s.usePlatformLlm);
      })
      .catch(() => {});
    api
      .platformLlm()
      .then(setPlatformLlmInfo)
      .catch(() => {});
  }, []);
  const isFreePlan = tenantPlan === "FREE";

  async function handleTogglePlatformLlm() {
    const next = !usePlatformLlm;
    setPlatformLlmToggleBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const updated = await api.setTenantUsePlatformLlm(experienceMode, next);
      setUsePlatformLlm(!!updated.usePlatformLlm);
      setSuccess(next ? t("usePlatformLlmEnabledSuccess") : t("usePlatformLlmDisabledSuccess"));
    } catch (err) {
      setError(errorMessage(err, t("usePlatformLlmUpdateFailedError")));
    } finally {
      setPlatformLlmToggleBusy(false);
    }
  }

  const refreshSarvam = () =>
    !speech
      ? Promise.resolve()
      : api
          .getSarvamCredential()
          .then(setSarvamCredential)
          .catch(() => setSarvamCredential(null));
  const refreshE2b = () =>
    api
      .getE2bCredential()
      .then(setE2bCredential)
      .catch(() => setE2bCredential(null));
  const refreshElevenLabs = () =>
    !speech
      ? Promise.resolve()
      : api
          .getElevenLabsCredential()
          .then(setElevenLabsCredential)
          .catch(() => setElevenLabsCredential(null));
  const e2b = useE2bSetup(refreshE2b, setError, setSuccess, t);
  const el = useElevenLabsVoices(setError, setSuccess, t, speech);

  useEffect(() => {
    void refreshSarvam();
    void refreshE2b();
    void refreshElevenLabs();
  }, []);

  const byName = new Map<ProviderName, ProviderRow>(providers.map((p) => [p.providerName, p]));
  const activeCount =
    providers.filter((p) => p.status === "ACTIVE").length +
    (sarvamCredential?.status === "ACTIVE" ? 1 : 0) +
    (e2bCredential?.status === "ACTIVE" ? 1 : 0) +
    (elevenLabsCredential?.status === "ACTIVE" ? 1 : 0);
  // Reliability blueprint Phase 3 Part 4 - LLM-only provider count (powers
  // IntelligenceSourceControl's activeProviderCount): NOT the sarvam/e2b/
  // elevenLabs-inclusive `activeCount` above, which are voice/sandbox
  // credentials a BYOK routing mode cannot hand a chat turn to.
  const activeLlmProviderCount = providers.filter((p) => p.status === "ACTIVE").length;
  const tenantProviderState: TenantProviderState = { usePlatformLlm, activeProviderCount: activeLlmProviderCount };
  const currentRoutingMode = getRoutingMode(tenantProviderState);
  const currentRoutingModeOption = ROUTING_MODES[currentRoutingMode];

  // LLM_PROVIDER_GUIDES (credentialGuides.ts) only documents 13 of the 15
  // ProviderName cards - E2B/ELEVENLABS aren't chat providers and have no
  // credential guide entry there, so lookups below all read through this
  // helper and tolerate `undefined`.
  const guideFor = (name: ProviderName): LlmProviderGuide | undefined => LLM_PROVIDER_GUIDES[name];
  const catalog = useProviderCatalogFilter(
    cards,
    (n) => t(PROVIDER_INFO[n].labelKey),
    (n) =>
      (n === SARVAM_SPECIAL_CASED
        ? sarvamCredential
        : n === E2B_SPECIAL_CASED
          ? e2bCredential
          : n === ELEVENLABS_SPECIAL_CASED
            ? elevenLabsCredential
            : byName.get(n)
      )?.status === "ACTIVE"
  );
  const selectedCredentialGuide = providerName ? guideFor(providerName) : undefined;

  function selectProvider(name: ProviderName) {
    if (!canManageProviders || isFreePlan) return;
    setError(null);
    setSuccess(null);
    setApiKey("");
    setVertexProjectId("");
    setVertexLocation("us-central1");
    setVertexServiceAccountJson("");
    setMultiFieldValues({});
    setProviderName((current) => (current === name ? null : name));
  }

  function setMultiFieldValue(key: string, value: string) {
    setMultiFieldValues((current) => ({ ...current, [key]: value }));
  }

  function validate() {
    if (providerName === VERTEX_SPECIAL_CASED) {
      if (!vertexProjectId.trim() || !vertexLocation.trim() || !vertexServiceAccountJson.trim()) {
        return t("googleVertexFieldsRequiredError");
      }
      return null;
    }
    const multiFields = MULTI_FIELD_PROVIDERS[providerName];
    if (multiFields) {
      for (const field of multiFields) {
        if (field.required && !(multiFieldValues[field.key] || "").trim()) {
          return t("multiFieldRequiredError", { field: t(field.labelKey) });
        }
      }
    } else if (!apiKey.trim()) {
      return t("apiKeyRequiredError");
    }
    if (!implementedCards.has(providerName)) {
      return t("notImplementedError", {
        provider: providerName,
        implementedList: Array.from(implementedCards).join(", "),
      });
    }
    return null;
  }

  async function handleActivate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    const validationError = validate();
    if (validationError) {
      setError(validationError);
      return;
    }

    setBusy(true);
    try {
      // SARVAM writes to its own sarvam_credential table (SarvamController),
      // not LlmProviderCredential - see the SARVAM_SPECIAL_CASED comment
      // above. SarvamCredentialService.activate() throws
      // SarvamCredentialValidationException (caught below, same as
      // ProviderValidationException) on a rejected key - a successful
      // response here always means status ACTIVE either way.
      if (providerName === SARVAM_SPECIAL_CASED) {
        await api.activateSarvamCredential(apiKey);
        await refreshSarvam();
      } else if (providerName === E2B_SPECIAL_CASED) {
        await api.activateE2bCredential(apiKey);
        await refreshE2b();
      } else if (providerName === ELEVENLABS_SPECIAL_CASED) {
        // ELEVENLABS writes to its own elevenlabs_credential table
        // (ElevenLabsController), not LlmProviderCredential - see the
        // ELEVENLABS_SPECIAL_CASED comment above.
        await api.activateElevenLabsCredential(apiKey);
        await refreshElevenLabs();
      } else if (providerName === VERTEX_SPECIAL_CASED) {
        // Same POST /api/providers write path every other provider uses -
        // only the apiKey value differs, encoded client-side into the
        // project/location/service-account blob core's VertexCredential
        // expects (see that class's javadoc and the comment above
        // encodeVertexCredential).
        const encoded = encodeVertexCredential(
          vertexProjectId.trim(),
          vertexLocation.trim(),
          vertexServiceAccountJson.trim()
        );
        await api.activateProvider(providerName, encoded);
        onChanged();
      } else if (MULTI_FIELD_PROVIDERS[providerName]) {
        // Multi-field providers (OLLAMA/AZURE_OPENAI/AWS_BEDROCK) still call
        // the exact same POST /api/providers {providerName, apiKey} endpoint
        // every other provider uses - `apiKey` just carries a JSON-encoded
        // object instead of a raw key (see MULTI_FIELD_PROVIDERS' comment
        // and each backend ...Credential record's javadoc for this pattern).
        await api.activateProvider(
          providerName,
          encodeMultiFieldCredential(MULTI_FIELD_PROVIDERS[providerName], multiFieldValues)
        );
        onChanged();
      } else {
        await api.activateProvider(providerName, apiKey);
        onChanged();
      }
      setApiKey("");
      setVertexProjectId("");
      setVertexLocation("us-central1");
      setVertexServiceAccountJson("");
      setMultiFieldValues({});
      setSuccess(t("activatedSuccess", { provider: providerName }));
      setProviderName(null);
    } catch (err) {
      setError(errorMessage(err, t("activationFailedError")));
    } finally {
      setBusy(false);
    }
  }

  // "Give option to edit, delete the added key" - direct feedback. Routes to
  // whichever backend actually holds this provider's credential - the same
  // three-way (SARVAM/E2B/ELEVENLABS special-cased, everything else through
  // the generic /api/providers path) split handleActivate above already
  // uses, since deactivation lives on the exact same tables/controllers as
  // activation.
  async function handleDeactivateProvider(name: ProviderName) {
    setDeletingProvider(name);
    setError(null);
    setSuccess(null);
    try {
      if (name === SARVAM_SPECIAL_CASED) {
        await api.deactivateSarvamCredential();
        await refreshSarvam();
      } else if (name === E2B_SPECIAL_CASED) {
        await api.deactivateE2bCredential();
        await refreshE2b();
      } else if (name === ELEVENLABS_SPECIAL_CASED) {
        await api.deactivateElevenLabsCredential();
        await refreshElevenLabs();
      } else {
        await api.deactivateProvider(name);
        onChanged();
      }
      setSuccess(t("deactivatedSuccess", { provider: name }));
    } catch (err) {
      setError(errorMessage(err, t("deactivationFailedError")));
    } finally {
      setDeletingProvider(null);
      setConfirmingDeleteProvider(null);
    }
  }

  return (
    <Section
      title={t("sectionLabel", { active: activeCount, total: cards.length })}
      description={t("description")}
      className="space-y-6"
    >
      {error && <Alert tone="error">{error}</Alert>}
      {success && <Alert tone="success">{success}</Alert>}

      {/* Provider credentials are workspace-wide, admin-only config (same
       * class of write as Twilio/WhatsApp channel credentials) - enforced
       * server-side too (SecurityConfig gates POST /api/providers to
       * ROLE_tenant-admin). A solo/B2C signup's one user is already
       * tenant-admin (every tenant's first user is - see
       * TenantProvisioningService), so this never blocks a single-person
       * workspace from configuring its own provider. */}
      {!canManageProviders && <p className="text-sm text-muted-foreground">{t("adminOnlyNote")}</p>}
      {/* FREE-tier read-only notice. The upsell lives in the hover popup on
          the card itself (UpgradeLock) rather than as a standalone on-page
          Upgrade button. */}
      {canManageProviders && isFreePlan && (
        <UpgradeLock reason={t("freeTierUpgradeReason")} label={t("freeTierUpgradeButton")} className="w-full">
          <Card className="w-full flex-row flex-wrap items-center gap-3 gap-y-2 border-dashed p-4">
            <p className="text-sm text-muted-foreground">
              {t("freeTierReadOnlyNote")}
              {platformLlmInfo?.available && (
                <>
                  {" "}
                  {t(isPersonalInstall() ? "localModelActiveNote" : "platformHostedActiveNote", {
                    label: platformLlmInfo.label,
                    model: platformLlmInfo.model,
                  })}
                </>
              )}
            </p>
          </Card>
        </UpgradeLock>
      )}

      {/* #1085 - "every non free tenant should get an option to select use
       * platform llm...where there is a token limit for everyone who uses
       * platform llm and can be upgraded." A paid tenant can flip this on to
       * route chat through the same shared platform-hosted pool FREE tenants
       * use (own token/message cap, higher than FREE's - see
       * FreeTierUsageLimiterService's premium tier) instead of managing its
       * own BYOK provider below - useful for a tenant that doesn't want to
       * hold API keys at all yet. Admin-only, same posture as every other
       * write on this page. */}
      {/* A personal install has no hosted pool to opt into: its pool row IS the local Ollama, so the
       * toggle is replaced by a plain statement of which local model answers (lib/personalInstall.ts). */}
      {canManageProviders && !isFreePlan && !showPlatformPoolToggle() && platformLlmInfo?.available && (
        <Card className="p-5">
          <div className="text-sm font-medium text-foreground">{t("localModelHeading")}</div>
          <p className="text-sm text-muted-foreground">
            {t("localModelActiveNote", { label: platformLlmInfo.label, model: platformLlmInfo.model })}
          </p>
        </Card>
      )}
      {canManageProviders && !isFreePlan && showPlatformPoolToggle() && (
        <Card className="flex-row items-center justify-between gap-4 p-5">
          <div className="min-w-0 space-y-0.5">
            <div className="text-sm font-medium text-foreground">{t("usePlatformLlmToggleLabel")}</div>
            <p className="text-sm text-muted-foreground">
              {t("usePlatformLlmToggleNote")}
              {platformLlmInfo?.available && (
                <> {t("platformHostedActiveNote", { label: platformLlmInfo.label, model: platformLlmInfo.model })}</>
              )}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            {platformLlmToggleBusy && <span className="text-xs text-muted-foreground">{t("savingButton")}</span>}
            <Toggle
              on={usePlatformLlm}
              disabled={platformLlmToggleBusy}
              onClick={handleTogglePlatformLlm}
              ariaLabel={t("usePlatformLlmToggleLabel")}
            />
          </div>
        </Card>
      )}

      {/* Reliability blueprint Phase 3 Part 4 - the same mode vocabulary
       * (routingState.ts's ROUTING_MODES) IntelligenceSourceControl.tsx and
       * AgentWorkbench.tsx now use, so an admin reading this raw on/off
       * toggle also sees the resolved mode name it maps to instead of
       * having to infer it. Purely informational - does not read/write
       * capabilities.ts or localStorage directly (all of that stays behind
       * getRoutingMode()), and does not change this toggle's own GET/PUT
       * round-trip. */}
      {canManageProviders && !isFreePlan && (
        <p className="-mt-2 px-1 text-xs text-muted-foreground">
          {t("currentRoutingModeNote", {
            label: currentRoutingModeOption.label,
            description: currentRoutingModeOption.description,
          })}
        </p>
      )}

      {/* Zero-provider guidance (maintainer report: academy-lab learners flipping
       * their workspace to BYOK land here with nothing configured and no
       * idea which card to pick or where a key even comes from). Points at
       * the per-card "How do I get this?" walkthroughs and the per-card
       * pricing links; deliberately names no prices, credits or offers -
       * those change, the providers' own pages are the only current source. */}
      {canManageProviders && !isFreePlan && activeLlmProviderCount === 0 && (
        <Card className="gap-2 border-dashed p-4">
          <div className="flex items-center gap-2 text-sm font-medium text-foreground">
            <Lightbulb aria-hidden="true" size={14} className="text-[var(--ax-amber)]" />
            {t("getStartedTitle")}
          </div>
          <p className="text-sm leading-relaxed text-muted-foreground">{t("getStartedBody")}</p>
        </Card>
      )}

      {catalog.controls}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {catalog.visible.map((name) => {
          const info = PROVIDER_INFO[name];
          const Icon = info.icon;
          const label = t(info.labelKey);
          const guide = guideFor(name);
          const activated =
            name === SARVAM_SPECIAL_CASED
              ? sarvamCredential
              : name === E2B_SPECIAL_CASED
                ? e2bCredential
                : name === ELEVENLABS_SPECIAL_CASED
                  ? elevenLabsCredential
                  : byName.get(name);
          const implemented = implementedCards.has(name);
          const selected = providerName === name;
          const canManageCard = name === ELEVENLABS_SPECIAL_CASED ? canManageVoice : canManageProviders;
          const comingSoon = !implemented && !activated;
          // Coming-soon cards (audit #11): don't wire a click handler at all
          // - previously these stayed fully clickable (opened the activate
          // form) even though the only possible outcome was validate()'s
          // always-losing notImplementedError. Also gate on `busy` (audit
          // #18) so a card can't be reselected/deselected out from under an
          // activation request already in flight - mirrors the disabled={busy}
          // guard on the Activate button further down.
          const clickable = canManageCard && !isFreePlan && !activated && implemented && !busy;
          return (
            <Card
              key={name}
              className={cn(
                "gap-0 p-5 transition-colors",
                clickable && "cursor-pointer hover:border-primary/40",
                selected && "border-primary ring-1 ring-primary",
                comingSoon && "opacity-60"
              )}
              onClick={clickable ? () => selectProvider(name) : undefined}
              role={clickable ? "button" : undefined}
              tabIndex={clickable ? 0 : undefined}
              onKeyDown={
                clickable
                  ? (e) => {
                      // Only the card itself: keys typed into the nested
                      // inputs/buttons (E2B template id, voice actions)
                      // bubble here and must not toggle selection.
                      if (e.target !== e.currentTarget) return;
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        selectProvider(name);
                      }
                    }
                  : undefined
              }
              aria-disabled={comingSoon ? true : undefined}
            >
              <div className="flex items-start gap-3">
                <span
                  className="flex size-9 shrink-0 items-center justify-center rounded-lg"
                  style={{ background: info.tint, color: info.color }}
                >
                  <Icon size={17} />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium text-foreground">{label}</span>
                    {activated ? (
                      activated.status ? (
                        <Badge variant="outline" className={activated.status === "ACTIVE" ? OK_BADGE : WARN_BADGE}>
                          {activated.status}
                        </Badge>
                      ) : null
                    ) : comingSoon ? (
                      <Badge variant="secondary">{t("comingSoonBadge")}</Badge>
                    ) : null}
                  </div>
                  <ProviderCardStatus
                    activated={activated}
                    implemented={implemented}
                    canReplaceKey={canManageCard && !isFreePlan}
                    onReplaceKey={() => selectProvider(name)}
                  />
                </div>
              </div>
              <p className="mt-3 text-sm text-muted-foreground">{t(info.descKey)}</p>
              <ProviderGuideLinks guide={guide} />
              {/* "Give option to edit, delete the added key" - direct feedback.
               * Applies to every activated provider on this panel, not just
               * E2B - Edit reopens the same activation form (resubmitting IS
               * the edit, since every activate() call upserts); Delete needs
               * its own two-step confirm since it's destructive. canManageCard-gated
               * same as every other write on this card. */}
              {canManageCard && !isFreePlan && activated && (
                <ProviderKeyActions
                  confirmingDelete={confirmingDeleteProvider === name}
                  deleting={deletingProvider === name}
                  onEdit={() => selectProvider(name)}
                  onRequestDelete={() => setConfirmingDeleteProvider(name)}
                  onCancelDelete={() => setConfirmingDeleteProvider(null)}
                  onConfirmDelete={() => handleDeactivateProvider(name)}
                />
              )}
              {name === "LOCAL_OPENAI" && canManageCard && !isFreePlan && activated?.status === "ACTIVE" && (
                <LocalServerProbe />
              )}
              {name === E2B_SPECIAL_CASED && (
                <E2bCardExtras
                  name={name}
                  activated={activated}
                  e2b={e2b}
                  canManage={canManageProviders && !isFreePlan}
                />
              )}

              {speech &&
                canManageCard &&
                !isFreePlan &&
                name === ELEVENLABS_SPECIAL_CASED &&
                activated?.status === "ACTIVE" && <ElevenLabsVoicesSection el={el} />}
            </Card>
          );
        })}
      </div>

      {canManageProviders && !isFreePlan && providerName && (
        <ProviderActivationCard
          providerName={providerName}
          guide={selectedCredentialGuide}
          busy={busy}
          onSubmit={handleActivate}
          onCancel={() => setProviderName(null)}
          apiKey={apiKey}
          setApiKey={setApiKey}
          vertexProjectId={vertexProjectId}
          setVertexProjectId={setVertexProjectId}
          vertexLocation={vertexLocation}
          setVertexLocation={setVertexLocation}
          vertexServiceAccountJson={vertexServiceAccountJson}
          setVertexServiceAccountJson={setVertexServiceAccountJson}
          multiFieldValues={multiFieldValues}
          setMultiFieldValue={setMultiFieldValue}
        />
      )}
    </Section>
  );
}
