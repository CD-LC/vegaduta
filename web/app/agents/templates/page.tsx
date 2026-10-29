"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Bot, Search } from "lucide-react";
import AppShell from "../../components/AppShell";
import { api } from "../../lib/api";
import {
  AGENT_AUDIENCES,
  AGENT_TEMPLATE_CATEGORIES,
  AGENT_TEMPLATES,
  CATEGORY_AUDIENCE,
  audienceLabelKey,
  audienceTaglineKey,
  categoryLabelKey,
  templateNameKey,
  templateTaglineKey,
  templateKnowledgeHintKey,
} from "../../components/agentTemplates";
import {
  STARTER_TEMPLATES,
  isOpenConsoleTemplate,
  type StarterTemplate,
} from "../../components/agents/starterTemplates";
import TemplateSetupGuide from "../../components/agents/TemplateSetupGuide";
import TemplatePrereqChips from "../../components/agents/TemplatePrereqChips";
import {
  TEMPLATE_AUDIENCES,
  TEMPLATE_RELATED_LINKS,
  audienceOfTemplate,
  audienceTagLabelKey,
  type TemplateAudience,
} from "../../components/agents/templateAudience";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { whatsAppFit } from "../../components/whatsappFit";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState, Section } from "../../components/ax/page-kit";

/**
 * Dedicated template gallery - split out of the Agents page (direct
 * feedback: "create another page for template selection, add all the
 * general use cases that common people do and organization/Developers and
 * everyone"). Browsing here never creates anything; picking a template
 * navigates back to /agents?template=<key>, which pre-fills the create
 * form there (nothing is created until the person names it and submits -
 * same "review before creating" posture the inline gallery always had).
 *
 * Two galleries on one page (funnel audit, 2026-09):
 *   1. Starter templates - the 16 /try business personas
 *      (components/agents/starterTemplates.ts). Open on EVERY plan, listed
 *      first, so a brand-new FREE tenant's first agent is one click rather
 *      than a blank draft. Core has no plan gate on templates (picking one
 *      only pre-fills the form), so this is honest, not a UI-only unlock.
 *   2. The full audience/category gallery below it - a paid capability in
 *      this UI (AgentWorkbench used to lock-badge the whole Templates button
 *      for FREE; the lock now lives here, per card, with the same Upgrade
 *      affordance the shared Button provides).
 */
export default function AgentTemplatesPage() {
  const t = useTranslations("agentTemplatesPage");
  const tTemplates = useTranslations("agentTemplates");
  // Starter-gallery strings + persona labels live in the sandbox's `try`
  // namespace on purpose - same 16 personas, same labels, one translation.
  const tTry = useTranslations("try");
  // The two lock strings AgentWorkbench already used for its Templates
  // button - reused verbatim so the upsell reads identically everywhere.
  const tWorkbench = useTranslations("agentWorkbench");
  const tp = useTranslations("consolePolish");
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [showAllStarters, setShowAllStarters] = useState(false);
  const [activeAudience, setActiveAudience] = useState("all");
  // "Who is it for?" - a flatter persona axis next to the audience sections
  // (student / researcher / freelancer / small business / developer).
  const [activePersona, setActivePersona] = useState<TemplateAudience | "all">("all");
  // ?channel=whatsapp (linked from the WhatsApp binding warning) starts on the WhatsApp-ready set.
  const [whatsappOnly, setWhatsappOnly] = useState(false);
  useEffect(() => {
    setWhatsappOnly(new URLSearchParams(window.location.search).get("channel") === "whatsapp");
  }, []);
  const needle = query.trim().toLowerCase();
  const matchesQuery = (...parts: string[]) => !needle || parts.some((p) => p.toLowerCase().includes(needle));
  const matchesPersona = (templateKey: string) =>
    (activePersona === "all" || audienceOfTemplate(templateKey) === activePersona) &&
    (!whatsappOnly || whatsAppFit(templateKey) === "ready") &&
    matchesQuery(tTemplates(templateNameKey(templateKey)), tTemplates(templateTaglineKey(templateKey)));

  // Plan gate for the full gallery. Same posture as AgentWorkbench's
  // isFreePlan: permissive while the first response is in flight (no
  // flicker on the common paid case), but a confirmed fetch failure fails
  // CLOSED to locked - unlocked-by-default is the unsafe direction, since we
  // can no longer tell a FREE tenant from a paid one. The starters above the
  // gallery are unaffected either way.
  const [plan, setPlan] = useState<"loading" | "free" | "paid">("loading");
  useEffect(() => {
    let cancelled = false;
    api
      .getTenantSettings()
      .then((s: { plan?: string | null }) => {
        if (!cancelled) setPlan(s?.plan === "FREE" ? "free" : "paid");
      })
      .catch(() => {
        if (!cancelled) setPlan("free");
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const galleryLocked = plan === "free";

  // Named applyTemplate, not useTemplate - a "use"-prefixed identifier
  // makes eslint-plugin-react-hooks treat this as a Hook, which then
  // flags any call site as a rules-of-hooks violation even though it's
  // just a plain function.
  function applyTemplate(template: (typeof AGENT_TEMPLATES)[number]) {
    router.push(`/agents?template=${encodeURIComponent(template.key)}`);
  }

  function applyStarter(starter: StarterTemplate) {
    router.push(`/agents?template=${encodeURIComponent(starter.key)}`);
  }

  const STARTER_PREVIEW_COUNT = 8;
  const matchingStarters = STARTER_TEMPLATES.filter((st) => matchesQuery(tTry(st.labelKey), st.defaultName));
  // Searching always shows every match; otherwise a long starter list is folded behind a toggle.
  const visibleStarters =
    needle || showAllStarters ? matchingStarters : matchingStarters.slice(0, STARTER_PREVIEW_COUNT);
  const anyGalleryMatch = AGENT_TEMPLATES.some((x) => matchesPersona(x.key));

  const audiences =
    activeAudience === "all" ? AGENT_AUDIENCES : AGENT_AUDIENCES.filter((a) => a.key === activeAudience);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={
          <>
            {t("introPrefix")}{" "}
            <Link href="/agents" className="font-medium text-primary underline-offset-4 hover:underline">
              {t("agentsPageLinkText")}
            </Link>
            {t("introSuffix")}
          </>
        }
      />

      <div className="relative mb-6 max-w-md">
        <Search
          size={16}
          aria-hidden="true"
          className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={tp("templatesSearchPlaceholder")}
          aria-label={tp("templatesSearchLabel")}
          className="ps-9"
        />
      </div>

      {/* Starter allow-list - first, and never gated. */}
      <Section title={tTry("starter_sectionTitle")} description={tTry("starter_sectionDescription")} className="mb-12">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {visibleStarters.map((starter) => (
            <Card key={starter.key} className="group gap-0 p-4 transition-colors hover:border-primary/40">
              <CardContent className="flex h-full flex-col p-0">
                <span className="mb-3 flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Bot size={17} strokeWidth={2.1} aria-hidden="true" />
                </span>
                <h3 className="text-sm font-semibold text-foreground">{tTry(starter.labelKey)}</h3>
                {/* The pre-filled agent name doubles as the one-line "what
                    it does" - it is the sandbox's own seed name. */}
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{starter.defaultName}</p>
                <div className="mt-auto pt-3">
                  <Button size="sm" onClick={() => applyStarter(starter)}>
                    {t("useTemplate")}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
        {matchingStarters.length > STARTER_PREVIEW_COUNT && !needle && (
          <div className="mt-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              aria-expanded={showAllStarters}
              onClick={() => setShowAllStarters((v) => !v)}
            >
              {showAllStarters ? tp("templatesShowFewer") : tp("templatesShowAll", { count: matchingStarters.length })}
            </Button>
          </div>
        )}
        {matchingStarters.length === 0 && needle && (
          <p className="text-sm text-muted-foreground">{tp("templatesNoStarterMatch")}</p>
        )}
      </Section>

      {needle && !anyGalleryMatch && matchingStarters.length === 0 && (
        <EmptyState
          className="mb-8"
          icon={Search}
          title={tp("templatesNoMatchTitle")}
          description={tp("templatesNoMatchBody")}
          action={
            <Button type="button" variant="outline" size="sm" onClick={() => setQuery("")}>
              {tp("clearSearch")}
            </Button>
          }
        />
      )}

      {galleryLocked && (
        <div className="mb-6">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">{tTry("starter_premiumHeading")}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{tTry("starter_premiumNote")}</p>
        </div>
      )}

      <div className="mb-8 flex flex-wrap gap-2">
        <Button
          variant={activeAudience === "all" ? "default" : "outline"}
          size="sm"
          className="rounded-full"
          aria-pressed={activeAudience === "all"}
          onClick={() => setActiveAudience("all")}
        >
          {t("everything")}
        </Button>
        {AGENT_AUDIENCES.map((a) => (
          <Button
            key={a.key}
            variant={activeAudience === a.key ? "default" : "outline"}
            size="sm"
            className="rounded-full"
            aria-pressed={activeAudience === a.key}
            onClick={() => setActiveAudience(a.key)}
          >
            {tTemplates(audienceLabelKey(a.key))}
          </Button>
        ))}
      </div>

      <div className="-mt-4 mb-8 flex flex-wrap items-center gap-2" role="group" aria-label={t("forWhoLabel")}>
        <span className="me-1 text-sm font-medium text-muted-foreground">{t("forWhoLabel")}</span>
        <Button
          variant={activePersona === "all" ? "default" : "outline"}
          size="sm"
          className="rounded-full"
          aria-pressed={activePersona === "all"}
          onClick={() => setActivePersona("all")}
        >
          {t("everything")}
        </Button>
        <Button
          variant={whatsappOnly ? "default" : "outline"}
          size="sm"
          className="rounded-full"
          aria-pressed={whatsappOnly}
          onClick={() => setWhatsappOnly((v) => !v)}
        >
          {whatsappOnly ? t("whatsappFilterOn") : t("whatsappFilterOff")}
        </Button>
        {TEMPLATE_AUDIENCES.map((p) => (
          <Button
            key={p}
            variant={activePersona === p ? "default" : "outline"}
            size="sm"
            className="rounded-full"
            aria-pressed={activePersona === p}
            onClick={() => setActivePersona(p)}
          >
            {tTemplates(audienceTagLabelKey(p))}
          </Button>
        ))}
      </div>

      <div className="space-y-12">
        {audiences.map((audience) => {
          const categoriesForAudience = AGENT_TEMPLATE_CATEGORIES.filter(
            (category) => CATEGORY_AUDIENCE[category] === audience.key
          );
          // With a persona picked, an audience section with nothing for that
          // persona is left out rather than shown empty.
          if (
            !categoriesForAudience.some((category) =>
              AGENT_TEMPLATES.some((x) => x.category === category && matchesPersona(x.key))
            )
          ) {
            return null;
          }
          return (
            <Section
              key={audience.key}
              title={tTemplates(audienceLabelKey(audience.key))}
              description={tTemplates(audienceTaglineKey(audience.key))}
            >
              <div className="space-y-8">
                {categoriesForAudience.map((category) => {
                  const templates = AGENT_TEMPLATES.filter((t) => t.category === category && matchesPersona(t.key));
                  if (templates.length === 0) return null;
                  return (
                    <div key={category} className="space-y-3">
                      <div className="font-mono text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                        {tTemplates(categoryLabelKey(category))}
                      </div>
                      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                        {templates.map((template) => {
                          const Icon = template.icon;
                          return (
                            <Card
                              key={template.key}
                              className="group gap-0 p-5 transition-colors hover:border-primary/40"
                            >
                              <CardContent className="flex h-full flex-col p-0">
                                <span className="mb-4 flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                                  <Icon size={18} strokeWidth={2.1} />
                                </span>
                                <h3 className="text-sm font-semibold text-foreground">
                                  {tTemplates(templateNameKey(template.key))}
                                </h3>
                                <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                                  {tTemplates(templateTaglineKey(template.key))}
                                </p>
                                {whatsAppFit(template.key) === "ready" && (
                                  <Badge variant="secondary" className="mt-2 self-start text-[10px]">
                                    {t("whatsappReadyBadge")}
                                  </Badge>
                                )}
                                {whatsAppFit(template.key) === "unsuitable" && (
                                  <Badge
                                    variant="outline"
                                    className="mt-2 self-start text-[10px]"
                                    title={t("whatsappUnsuitableNote")}
                                  >
                                    {t("whatsappUnsuitableBadge")}
                                  </Badge>
                                )}
                                {template.knowledgeHint && (
                                  <p className="mt-2 text-xs text-muted-foreground">
                                    {tTemplates(templateKnowledgeHintKey(template.key))}
                                  </p>
                                )}
                                {/* What this template needs, from the same
                                    data as the setup guide behind its button. */}
                                <TemplatePrereqChips
                                  templateKey={template.key}
                                  category={template.category}
                                  suggestKnowledgeBase={template.suggestKnowledgeBase}
                                />
                                {TEMPLATE_RELATED_LINKS[template.key] ? (
                                  <Link
                                    href={TEMPLATE_RELATED_LINKS[template.key].href}
                                    className="mt-2 self-start text-xs font-medium text-primary underline-offset-4 hover:underline"
                                  >
                                    {tTemplates(TEMPLATE_RELATED_LINKS[template.key].labelKey)}
                                  </Link>
                                ) : null}
                                <div className="mt-auto flex flex-wrap items-center gap-2 pt-4">
                                  {/* FREE: the same lock-badged Button (hover
                                      reveals reason + Upgrade link) the
                                      workbench toolbar used to show for the
                                      whole gallery - now per template, next to
                                      an open starter list. */}
                                  {galleryLocked && !isOpenConsoleTemplate(template.key) ? (
                                    <Button
                                      size="sm"
                                      upgradeLocked
                                      upgradeReason={tWorkbench("templatesFreePlanTooltip")}
                                      upgradeLabel={tWorkbench("freeTierUpgradeButton")}
                                    >
                                      {t("useTemplate")}
                                    </Button>
                                  ) : (
                                    <Button size="sm" onClick={() => applyTemplate(template)}>
                                      {t("useTemplate")}
                                    </Button>
                                  )}
                                  {/* "A total guide on click" - the ordered,
                                      honest list of everything this particular
                                      agent needs before it does real work
                                      (documents, a channel, which of the 17
                                      MCP catalog servers to switch on, what
                                      the Free plan does and does not cover),
                                      with whatever this workspace already has
                                      ticked off. Rendered on a locked card too:
                                      knowing what a template would require is
                                      exactly the information a FREE tenant
                                      needs in order to judge the upgrade, and
                                      the guide's own "Use this template" CTA
                                      stays hidden while galleryLocked, so the
                                      plan gate is unchanged. */}
                                  <TemplateSetupGuide
                                    templateKey={template.key}
                                    templateName={tTemplates(templateNameKey(template.key))}
                                    suggestKnowledgeBase={template.suggestKnowledgeBase}
                                    category={template.category}
                                    onUseTemplate={() => applyTemplate(template)}
                                    useDisabled={galleryLocked && !isOpenConsoleTemplate(template.key)}
                                  />
                                </div>
                              </CardContent>
                            </Card>
                          );
                        })}
                      </div>
                    </div>
                  );
                })}
              </div>
            </Section>
          );
        })}
      </div>
    </AppShell>
  );
}
