"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { Check, Minus, ArrowUpRight, Sparkles } from "lucide-react";
import Link from "next/link";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import UpgradePrompt from "../components/UpgradePrompt";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card } from "../components/ui/card";
import { useAuth } from "../components/AuthProvider";
import { errorMessage } from "../lib/errors";
import { useTranslations } from "../lib/i18n";

// Tenant-facing "Plans & Features" comparison (direct request: "display all
// the features available for the unlimited user, disabled for the free user").
//
// Two data sources, deliberately combined:
//   1. A canonical per-tier reference matrix (REF below) - the product tier
//      definition from control_plane.plan_limits' V101 seed and the
//      Subscription-Plan-and-GTM-Strategy doc. Drives the Free / Premium /
//      Unlimited columns so a user can see what each tier unlocks. A tenant
//      cannot read OTHER plans' live limits (that's the admin-only
//      /api/platform/plan-limits surface), so the canonical matrix is the
//      honest source for the cross-tier comparison.
//   2. The tenant's OWN live entitlements from GET /api/tenant/settings
//      (TenantSettingsResponse now carries byokAllowed / whatsappAllowed /
//      whiteLabel / maxKbCollections alongside the existing caps). These
//      override the current plan's column via each feature's `live` extractor,
//      so a platform-admin editing this tenant's plan-limits in web-admin is
//      actually reflected here - the whole point of the entitlement wiring
//      this page sits on top of.
//
// No i18n namespace: page copy is inline English, same posture as the
// help/workflow-recipes data pages, to avoid growing the shared messages
// bundle for a single screen. The nav label ("railPlans") is the one string
// that goes through i18n (AppShell resolves it).

const PLANS = [
  { key: "FREE", label: "Free", price: "$0", cadence: "/mo" },
  { key: "PREMIUM", label: "Premium", price: "$19", cadence: "/mo" },
  { key: "UNLIMITED", label: "Unlimited", price: "$79", cadence: "/mo" },
];

// The tenant's own live entitlements, as returned by GET /api/tenant/settings
// (TenantSettingsResponse on the backend). Only the fields this page actually
// reads are modelled here - the response carries more.
interface TenantSettings {
  plan?: string;
  byokAllowed?: boolean;
  maxUsers?: number | null;
  maxAgents?: number | null;
  maxWorkflows?: number | null;
  maxKbCollections?: number | null;
  whatsappAllowed?: boolean;
  liveVisionAllowed?: boolean;
  whiteLabel?: boolean;
}

// A feature's per-plan value is either a boolean ("included?") for kind
// "bool", or a number (with -1 meaning "no cap") for kind "limit".
type FeatureValue = number | boolean;

interface Feature {
  label: string;
  desc: string;
  kind: "bool" | "limit";
  ref: Record<string, FeatureValue>;
  live?: (settings: TenantSettings) => FeatureValue | null | undefined;
}

interface FeatureSection {
  title: string;
  features: Feature[];
}

// -1 in a `limit` ref means "no cap" (∞), matching the plan_limits sentinel.
const SECTIONS: FeatureSection[] = [
  {
    title: "Models & providers",
    features: [
      {
        label: "Platform-hosted LLM",
        desc: "Managed open models, ready out of the box - no API key needed.",
        kind: "bool",
        ref: { FREE: true, PREMIUM: true, UNLIMITED: true },
      },
      {
        label: "Bring your own key & model choice",
        desc: "Activate your own Anthropic, OpenAI, Groq and other provider keys, and pick the exact model per agent.",
        kind: "bool",
        ref: { FREE: false, PREMIUM: true, UNLIMITED: true },
        live: (s) => !!s.byokAllowed,
      },
    ],
  },
  {
    title: "Capacity",
    features: [
      {
        label: "Team members",
        desc: "Seats you can invite into the workspace.",
        kind: "limit",
        ref: { FREE: 2, PREMIUM: 10, UNLIMITED: -1 },
        live: (s) => s.maxUsers,
      },
      {
        label: "Agents",
        desc: "Assistants you can build and keep deployed at once.",
        kind: "limit",
        ref: { FREE: 5, PREMIUM: 25, UNLIMITED: -1 },
        live: (s) => s.maxAgents,
      },
      {
        label: "Workflows",
        desc: "Saved multi-step orchestrations.",
        kind: "limit",
        ref: { FREE: 3, PREMIUM: 15, UNLIMITED: -1 },
        live: (s) => s.maxWorkflows,
      },
      {
        label: "Knowledge collections",
        desc: "Separate knowledge bases you can ground agents on.",
        kind: "limit",
        // Premium = -1 (unlimited): truth-pass A2, pricing audit 2026-07-31
        // §2.5-1 - the pricing page always promised "unlimited collections"
        // while plan_limits capped Premium at 5; the backend cap is being
        // flipped to -1 in a parallel session, and this canonical matrix now
        // matches that promise.
        ref: { FREE: 1, PREMIUM: -1, UNLIMITED: -1 },
        live: (s) => s.maxKbCollections,
      },
    ],
  },
  {
    title: "Channels",
    features: [
      {
        label: "WhatsApp Business",
        desc: "Connect a WhatsApp Business number and serve agents over WhatsApp.",
        kind: "bool",
        // FREE is TRUE as of V225__free_plan_whatsapp.sql (2026-09-12), which
        // sets plan_limits.whatsapp_allowed = TRUE for plan = 'FREE'
        // unconditionally and permanently - it is a plan_limits edit, not part
        // of the 3-month complimentary UNLIMITED grant, so it does not lapse.
        //
        // Why the `live` extractor below did not already make this honest: it
        // only overrides the viewer's OWN plan column (see cellValue), so a
        // Premium or Unlimited tenant - and every logged-out visitor, who has
        // no settings at all - still read the Free column straight off this
        // constant and saw WhatsApp crossed out. That contradicted the
        // product's own shipped copy one click away (landing's free-channels
        // line, the tour's "WhatsApp, Telegram and email on the free plan"),
        // which is the ChannelWall lane1/lane2 class of bug: the comparison
        // matrix asserting a gate the backend does not enforce.
        ref: { FREE: true, PREMIUM: true, UNLIMITED: true },
        live: (s) => !!s.whatsappAllowed,
      },
    ],
  },
  {
    title: "Senses",
    features: [
      {
        label: "Live vision — VegaDūta Sight",
        desc: "Let your agent watch through your camera or a shared screen in real time. Snapshots work on every plan; watch & live sessions unlock here.",
        kind: "bool",
        ref: { FREE: false, PREMIUM: true, UNLIMITED: true },
        live: (s) => !!s.liveVisionAllowed,
      },
    ],
  },
  {
    title: "Branding",
    features: [
      {
        label: "White-label",
        desc: "Remove VegaDūta branding and present the product as your own.",
        kind: "bool",
        ref: { FREE: false, PREMIUM: false, UNLIMITED: true },
        live: (s) => !!s.whiteLabel,
      },
    ],
  },
];

// TenantSettingsResponse returns maxUsers as a primitive int (Integer.MAX_VALUE
// when uncapped) and the other caps as nullable Integers (null when uncapped).
// Normalise both to the -1 "no cap" sentinel the ref matrix uses.
const INT_MAX = 2147483647;
function normalizeLimit(value: FeatureValue | null | undefined): FeatureValue {
  if (value === null || value === undefined) return -1;
  if (typeof value === "number" && value >= INT_MAX) return -1;
  return value;
}

function limitDisplay(value: FeatureValue) {
  return value === -1 ? "∞" : String(value);
}

// The value shown in a given plan's column for a feature: the tenant's live
// entitlement for their CURRENT plan (so admin edits reflect), otherwise the
// canonical reference for that tier.
function cellValue(feature: Feature, planKey: string, settings: TenantSettings | null, currentPlan: string) {
  if (planKey === currentPlan && settings && typeof feature.live === "function") {
    const live = feature.live(settings);
    return feature.kind === "limit" ? normalizeLimit(live) : !!live;
  }
  const ref = feature.ref[planKey];
  return feature.kind === "limit" ? normalizeLimit(ref) : ref;
}

function BoolCell({ on }: { on: FeatureValue }) {
    const tHC = useTranslations("hcFeaturesPage");
  return on ? (
    <span
      role="img"
      className="inline-flex h-6 w-6 items-center justify-center rounded-full"
      style={{ background: "var(--success-bg)", color: "var(--success-text)" }}
      aria-label="Included"
    >
      <Check className="size-3.5" aria-hidden="true" />
    </span>
  ) : (
    <span
      role="img"
      className="inline-flex h-6 w-6 items-center justify-center rounded-full"
      style={{ background: "var(--ax-track)", color: "var(--text-faint)" }}
      aria-label={tHC("hcFeaturesPage.s9")}
    >
      <Minus className="size-3.5" aria-hidden="true" />
    </span>
  );
}

interface FeatureCellProps {
  feature: Feature;
  planKey: string;
  settings: TenantSettings | null;
  currentPlan: string;
}

function FeatureCell({ feature, planKey, settings, currentPlan }: FeatureCellProps) {
  const value = cellValue(feature, planKey, settings, currentPlan);
  if (feature.kind === "bool") return <BoolCell on={value} />;
  return (
    <span
      className="font-mono text-sm"
      style={{ color: value === -1 ? "var(--accent-700)" : "var(--text-primary)", fontWeight: value === -1 ? 600 : 500 }}
    >
      {limitDisplay(value)}
    </span>
  );
}

export default function FeaturesPage() {
    const tHC = useTranslations("hcFeaturesPage");
  // /features is also reachable by a logged-out visitor and by crawlers (see
  // AuthProvider's ANONYMOUS_RENDER_ROUTES) - only a signed-in tenant has
  // settings to fetch, so skip the call entirely rather than firing an
  // API request that's guaranteed to 401 and surfacing a spurious "Could not
  // load your plan details" error banner to someone who was never on a plan.
  const { username } = useAuth();
  const isAuthed = !!username;

  const [settings, setSettings] = useState<TenantSettings | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [upgradeOpen, setUpgradeOpen] = useState(false);
  const [loaded, setLoaded] = useState(!isAuthed);

  const refresh = useCallback(async () => {
    try {
      setSettings(await api.getTenantSettings());
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, "Could not load your plan details."));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    if (isAuthed) void refresh();
  }, [isAuthed, refresh]);
  usePageReady(loaded);

  const currentPlan = settings?.plan || "FREE";
  const currentPlanLabel = PLANS.find((p) => p.key === currentPlan)?.label || "Free";
  const canUpgrade = currentPlan !== "UNLIMITED";

  return (
    <AppShell eyebrow="Plans">
      <PageHeader
        title={tHC("hcFeaturesPage.s8")}
        subtitle="Everything each plan unlocks. Your current plan is highlighted — its column reflects your workspace's live entitlements."
        actions={
          isAuthed ? (
            canUpgrade ? (
              <Button type="button" style={{ marginBottom: 0 }} onClick={() => setUpgradeOpen(true)}>
                <ArrowUpRight width={14} height={14} style={{ marginRight: 4, verticalAlign: -2 }} />
                {tHC("hcFeaturesPage.s7")}
              </Button>
            ) : null
          ) : (
            <Button type="button" style={{ marginBottom: 0 }} asChild>
              <Link href="/signup">
                <ArrowUpRight width={14} height={14} style={{ marginRight: 4, verticalAlign: -2 }} />
                {tHC("hcFeaturesPage.s6")}
              </Link>
            </Button>
          )
        }
      />

      {isAuthed && loadError && (
        <div className="error-box" style={{ marginBottom: "1rem" }}>
          {loadError}
        </div>
      )}

      {/* Current-plan callout - only meaningful for a signed-in tenant; an
          anonymous visitor/crawler has no plan to be "on", so show a neutral
          compare-plans framing instead. */}
      <Card className="mb-4 gap-0 border-[var(--accent-200)] bg-[var(--accent-50)] p-5">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <Sparkles width={18} height={18} style={{ color: "var(--accent-600)" }} />
            <span className="text-sm" style={{ color: "var(--text-secondary)" }}>
              {isAuthed ? (
                <>
                  {tHC("hcFeaturesPage.s5")}{" "}
                  <span style={{ color: "var(--text-primary)", fontWeight: 600 }}>{currentPlanLabel}</span> plan.
                </>
              ) : (
                "Compare what every plan unlocks."
              )}
            </span>
          </div>
          {isAuthed && canUpgrade ? (
            <span className="text-sm text-muted-foreground" style={{ fontSize: "0.8rem" }}>
              {tHC("hcFeaturesPage.s4")} <Minus width={12} height={12} style={{ display: "inline", verticalAlign: -2 }} /> {tHC("hcFeaturesPage.s3")}
            </span>
          ) : isAuthed ? (
            <Badge
              variant="outline"
              className="border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
              style={{ fontSize: "0.75rem" }}
            >
              {tHC("hcFeaturesPage.s2")}
            </Badge>
          ) : null}
        </div>
      </Card>

      {/* Comparison matrix */}
      <Card className="mb-0 gap-0 overflow-hidden p-0">
        <div style={{ overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 640 }}>
            <thead>
              <tr>
                <th style={{ textAlign: "left", padding: "1rem 1.5rem", width: "40%" }}>
                  <span
                    className="font-mono"
                    style={{ fontSize: "0.75rem", letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--text-muted)" }}
                  >
                    Feature
                  </span>
                </th>
                {PLANS.map((plan) => {
                  const isCurrent = plan.key === currentPlan;
                  return (
                    <th
                      key={plan.key}
                      style={{
                        textAlign: "center",
                        padding: "1rem 1rem",
                        background: isCurrent ? "var(--accent-50)" : "transparent",
                        borderBottom: isCurrent ? "2px solid var(--accent-500)" : "none",
                      }}
                    >
                      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
                        <span
                          style={{
                            fontWeight: 700,
                            fontSize: "0.95rem",
                            color: isCurrent ? "var(--accent-700)" : "var(--text-primary)",
                          }}
                        >
                          {plan.label}
                        </span>
                        <span className="font-mono" style={{ fontSize: "0.75rem", color: "var(--text-secondary)" }}>
                          {plan.price}
                          <span style={{ color: "var(--text-faint)" }}>{plan.cadence}</span>
                        </span>
                        {isCurrent && (
                          <Badge
                            variant="outline"
                            className="border-[var(--accent-200)] bg-transparent text-[var(--accent-700)]"
                            style={{ fontSize: "0.75rem", marginTop: 2 }}
                          >
                            {tHC("hcFeaturesPage.s1")}
                          </Badge>
                        )}
                      </div>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {SECTIONS.map((section) => (
                <Fragment key={section.title}>
                  <tr>
                    <td
                      colSpan={1 + PLANS.length}
                      style={{
                        padding: "0.9rem 1.5rem 0.4rem",
                        borderTop: "1px solid var(--surface-border)",
                        background: "var(--surface-subtle, transparent)",
                      }}
                    >
                      <span
                        className="font-mono"
                        style={{ fontSize: "0.75rem", letterSpacing: "0.12em", textTransform: "uppercase", color: "var(--text-muted)" }}
                      >
                        {section.title}
                      </span>
                    </td>
                  </tr>
                  {section.features.map((feature) => (
                    <tr key={feature.label} style={{ borderTop: "1px solid var(--surface-border)" }}>
                      <td style={{ padding: "0.9rem 1.5rem", verticalAlign: "top" }}>
                        <div style={{ fontWeight: 600, fontSize: "0.85rem", color: "var(--text-primary)" }}>
                          {feature.label}
                        </div>
                        <p className="text-sm text-muted-foreground" style={{ margin: "2px 0 0", fontSize: "0.75rem", lineHeight: 1.5 }}>
                          {feature.desc}
                        </p>
                      </td>
                      {PLANS.map((plan) => {
                        const isCurrent = plan.key === currentPlan;
                        return (
                          <td
                            key={plan.key}
                            style={{
                              textAlign: "center",
                              padding: "0.9rem 1rem",
                              background: isCurrent ? "var(--accent-50)" : "transparent",
                            }}
                          >
                            <FeatureCell feature={feature} planKey={plan.key} settings={settings} currentPlan={currentPlan} />
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <UpgradePrompt
        open={upgradeOpen}
        onClose={() => setUpgradeOpen(false)}
        reason={`Unlock more on a higher plan than ${currentPlanLabel}.`}
      />
    </AppShell>
  );
}
