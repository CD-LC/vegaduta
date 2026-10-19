import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowRight, ChevronRight, Sparkles, Zap } from "lucide-react";
import BrandName from "../../../components/BrandName";
import PublicWorkflowNav from "../PublicWorkflowNav";
import { NODE_META, type Recipe, type RecipeBuild } from "../recipes-types";
import { INDUSTRY_INDEX, type IndustryMeta } from "../recipes-index";
import { loadIndustry } from "../recipes-registry";
import { SITE_URL } from "../../../lib/site-url";
import JsonLd from "../../../components/JsonLd";

// ---------------------------------------------------------------------------
// SEO Phase 1 — programmatic per-industry pages (2026-07-25).
//
// /help/workflow-recipes renders the whole catalogue as ONE client page behind
// #anchors, so none of the ~90 industries' content is crawlable as its own
// URL. This route statically generates one SERVER-rendered page per industry
// id in recipes-index.ts. The sector pack modules are plain data (no hooks,
// no browser APIs — verified), so the FULL recipe bodies — scenario, step
// flow, build spec with prompts/MCP/tools/guardrails — are rendered to real
// HTML at build time via the same lazy registry the client guide uses (on the
// server the dynamic import just resolves at build).
//
// The interactive guide remains the richer experience; each page links back
// to it (and to /try) rather than duplicating its tabs/search UI.
//
// NAV-AUDIT FIX (2026-07-27): this page used to wrap its content in
// <AppShell>, which unconditionally calls useAuth(). This route is listed in
// PUBLIC_ROUTES (lib/auth.ts), so AuthProvider renders it with NO
// AuthContext.Provider at all - meaning every one of these ~90 pages 500'd
// for any logged-out visitor, including search-engine crawlers, which is the
// entire audience this SEO route exists for. The sibling index page
// (../page.tsx) already had this exact bug fixed; this page just never got
// the same treatment. Now renders the same standalone public chrome as
// /docs and the index page, via the shared PublicWorkflowNav.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

// Only industries whose full name would push the <title> past 60 chars get a
// shortened form ("AI Agent Workflows for " + name + " | VegaDūta" = name
// budget of 26 chars). Everything else uses its real catalogue name.
const SHORT_NAME: Record<string, string> = {
  "accounting-firm": "CA & Accounting Firms",
  solar: "Solar Installers",
  "job-seekers": "Job Seekers",
  "personal-finance": "Personal Finance",
  "multi-agent-patterns": "Multi-Agent Patterns",
  advertising: "Marketing Agencies",
  "public-services": "Banking & Public Services",
  manufacturing: "Manufacturing & Field Ops",
  "professional-services": "Professional Services",
  "youtube-creator": "YouTube Creators",
  "shortform-influencer": "Influencers & Creators",
  "dairy-coldchain": "Dairy & Cold Chain",
  diagnostics: "Diagnostics Labs",
  "drone-survey": "Drone & Aerial Survey",
  "everyday-life": "Everyday Family Admin",
  "personal-health": "Personal Health Records",
  facilities: "Facilities & Co-working",
  couples: "Couples & Life Admin",
  freelancer: "Freelancers & Consultants",
  "small-business": "Small Businesses",
  "smb-marketing": "Small-Team Marketing",
  "sales-prospecting": "Sales Prospecting",
  "law-firm": "Law Firms & Legal Ops",
  tutors: "Tutors & Coaching Centres",
  "fmcg-distribution": "FMCG Distribution",
  microfinance: "Microfinance & SHGs",
  "coop-bank": "Cooperative Banks",
  mining: "Mining & Metals",
  customs: "Customs Brokerage",
  "web-testing": "Automated QA Testing",
  "quick-commerce": "Quick Commerce",
  "cloud-kitchen": "Cloud Kitchens",
  "web-monitoring": "Web Research & Scraping",
  "data-entry": "Back-Office Data Entry",
  shipping: "Shipping & Freight",
  "security-ops": "SOC & Security Ops",
  staffing: "Staffing Agencies",
  lending: "Lending & Finance",
  "textile-apparel": "Textile & Apparel",
  "handicraft-export": "Handicraft Exporters",
  "telecom-tower": "Telecom Towers",
  "waste-management": "Waste Management",
  "public-transit": "Public Transport",
  "temple-trust": "Temples & Trusts",
  utilities: "Utilities & Energy",
  construction: "Construction & Infra",
  warehousing: "Warehousing & 3PL",
  wellbeing: "Mental Wellbeing",
  "care-support": "Caregiver Support",
};

// Meta description: the industry's real tagline, clipped to <=155 chars on a
// word boundary — never mid-word, never a fabricated summary.
function metaDescription(tagline: string): string {
  if (tagline.length <= 155) return tagline;
  const cut = tagline.slice(0, 154);
  const atWord = cut.slice(0, cut.lastIndexOf(" "));
  return `${atWord}…`;
}

function findMeta(id: string): IndustryMeta | undefined {
  return INDUSTRY_INDEX.find((i) => i.id === id);
}

// Every industry id becomes a static page at build time; anything else 404s.
export const dynamicParams = false;

export function generateStaticParams(): { industry: string }[] {
  return INDUSTRY_INDEX.map((i) => ({ industry: i.id }));
}

type Params = { params: Promise<{ industry: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { industry } = await params;
  const meta = findMeta(industry);
  if (!meta) return {};
  const title = `AI Agent Workflows for ${SHORT_NAME[meta.id] ?? meta.name} | VegaDūta`;
  const description = metaDescription(meta.tagline);
  const path = `/help/workflow-recipes/${meta.id}`;
  return {
    title,
    description,
    alternates: { canonical: path },
    openGraph: { title, description, url: path, type: "article", images: ["/og-image.png"] },
  };
}

// ------------------------------------------------------------- presentation

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">{children}</h3>;
}

function ChipList({ items }: { items: string[] }) {
  return (
    <ul className="flex flex-wrap gap-1.5">
      {items.map((c) => (
        <li key={c} className="rounded-full border border-border bg-muted/40 px-2.5 py-0.5 text-xs text-foreground">
          {c}
        </li>
      ))}
    </ul>
  );
}

function BuildSpec({ build }: { build: RecipeBuild }) {
  return (
    <div className="mt-5 space-y-5 rounded-lg border border-border bg-card p-4 sm:p-5">
      <div className="space-y-1">
        <SectionLabel>Build spec</SectionLabel>
        <p className="text-sm text-foreground">
          <span className="font-medium">
            {build.agents} agent{build.agents === 1 ? "" : "s"}
          </span>
          {build.agentsNote ? <span className="text-muted-foreground"> — {build.agentsNote}</span> : null}
          {build.pattern ? (
            <span className="text-muted-foreground">
              {" "}
              · Pattern: <span className="text-foreground">{build.pattern}</span>
            </span>
          ) : null}
        </p>
      </div>

      <div className="space-y-2">
        <SectionLabel>System prompt (paste-ready)</SectionLabel>
        <pre className="max-h-[28rem] overflow-auto whitespace-pre-wrap rounded-md border border-border bg-muted/30 p-3 font-mono text-xs leading-relaxed text-foreground">
          {build.prompt}
        </pre>
      </div>

      {build.agentRoles && build.agentRoles.length > 0 ? (
        <div className="space-y-3">
          <SectionLabel>Agent roles &amp; model tiers</SectionLabel>
          <ul className="space-y-3">
            {build.agentRoles.map((r) => (
              <li key={r.role} className="rounded-md border border-border bg-muted/20 p-3">
                <p className="text-sm font-medium text-foreground">
                  {r.role} <span className="font-normal text-muted-foreground">— {r.model}</span>
                </p>
                <pre className="mt-2 whitespace-pre-wrap font-mono text-xs leading-relaxed text-muted-foreground">
                  {r.prompt}
                </pre>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2">
        {build.mcp.length > 0 ? (
          <div className="space-y-2">
            <SectionLabel>MCP connectors</SectionLabel>
            <ChipList items={build.mcp} />
          </div>
        ) : null}
        {build.tools.length > 0 ? (
          <div className="space-y-2">
            <SectionLabel>Built-in tools</SectionLabel>
            <ChipList items={build.tools} />
          </div>
        ) : null}
      </div>

      {build.guardrails.length > 0 ? (
        <div className="space-y-2">
          <SectionLabel>Guardrails</SectionLabel>
          <ul className="list-disc space-y-1 pl-5 text-sm text-foreground">
            {build.guardrails.map((g) => (
              <li key={g}>{g}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {build.cost ? (
        <div className="space-y-1">
          <SectionLabel>Cost strategy</SectionLabel>
          <p className="text-sm text-muted-foreground">{build.cost}</p>
        </div>
      ) : null}

      <div className="space-y-1">
        <SectionLabel>Output &amp; delivery</SectionLabel>
        <p className="text-sm text-muted-foreground">{build.output}</p>
      </div>
    </div>
  );
}

function RecipeArticle({ recipe, index }: { recipe: Recipe; index: number }) {
  return (
    <article id={recipe.id} className="scroll-mt-24 border-t border-border pt-8">
      <h2 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">
        {index + 1}. {recipe.title}
      </h2>
      <p className="mt-3 text-sm leading-relaxed text-muted-foreground sm:text-base">{recipe.scenario}</p>

      <div className="mt-4 flex items-start gap-2 rounded-md border border-border bg-muted/30 px-3 py-2">
        <Zap className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
        <p className="text-sm text-foreground">
          <span className="font-medium">Trigger:</span> {recipe.triggerLabel}
        </p>
      </div>

      <div className="mt-5 space-y-2">
        <SectionLabel>How the workflow runs</SectionLabel>
        <ol className="space-y-3">
          {recipe.steps.map((step, i) => {
            const meta = NODE_META[step.kind];
            const Icon = meta.icon;
            return (
              <li key={`${recipe.id}-step-${i}`} className="flex gap-3">
                <span className="mt-0.5 inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full border border-border bg-muted/40 px-2 text-[11px] font-medium text-muted-foreground">
                  <Icon className="size-3.5" aria-hidden="true" />
                  {meta.label}
                </span>
                <p className="text-sm leading-relaxed text-foreground">
                  <span className="font-medium">{step.title}.</span>{" "}
                  <span className="text-muted-foreground">{step.detail}</span>
                </p>
              </li>
            );
          })}
        </ol>
      </div>

      {recipe.connectors.length > 0 ? (
        <div className="mt-5 space-y-2">
          <SectionLabel>Channels &amp; connectors</SectionLabel>
          <ChipList items={recipe.connectors} />
        </div>
      ) : null}

      <div className="mt-5 grid gap-4 sm:grid-cols-2">
        <div className="space-y-1">
          <SectionLabel>Outcome</SectionLabel>
          <p className="text-sm leading-relaxed text-muted-foreground">{recipe.outcome}</p>
        </div>
        <div className="space-y-1">
          <SectionLabel>Why it helps</SectionLabel>
          <p className="text-sm leading-relaxed text-muted-foreground">{recipe.impact}</p>
        </div>
      </div>

      {recipe.build ? <BuildSpec build={recipe.build} /> : null}
    </article>
  );
}

// ------------------------------------------------------------------- page

export default async function IndustryWorkflowsPage({ params }: Params) {
  const { industry } = await params;
  const meta = findMeta(industry);
  if (!meta) notFound();

  // Full recipe bodies from the industry's sector pack. On the server this
  // dynamic import resolves at build time — the whole page is static HTML.
  const full = await loadIndustry(meta.pack, meta.id);
  const recipes: Recipe[] = full?.recipes ?? [];
  const Icon = meta.icon;
  const pageUrl = `${BASE}/help/workflow-recipes/${meta.id}`;

  // Sibling industries that ship in the same sector pack — real, close
  // internal links (the packs pair adjacent domains on purpose).
  const siblings = INDUSTRY_INDEX.filter((i) => i.pack === meta.pack && i.id !== meta.id);

  const jsonLd = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "BreadcrumbList",
        itemListElement: [
          { "@type": "ListItem", position: 1, name: "Help", item: `${BASE}/help` },
          {
            "@type": "ListItem",
            position: 2,
            name: "Workflow recipes",
            item: `${BASE}/help/workflow-recipes`,
          },
          { "@type": "ListItem", position: 3, name: meta.name, item: pageUrl },
        ],
      },
      {
        "@type": "ItemList",
        name: `AI agent workflows for ${meta.name}`,
        description: meta.tagline,
        numberOfItems: recipes.length,
        itemListElement: recipes.map((r, i) => ({
          "@type": "ListItem",
          position: i + 1,
          name: r.title,
          url: `${pageUrl}#${r.id}`,
        })),
      },
    ],
  };

  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <JsonLd data={jsonLd} />

      <PublicWorkflowNav />

      <main className="mx-auto w-full max-w-4xl px-5 pb-16 sm:px-8">
        {/* Breadcrumb */}
        <nav aria-label="Breadcrumb" className="mb-6">
          <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <li>
              <Link href="/help" className="hover:text-foreground hover:underline">
                Help
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" aria-hidden="true" />
            </li>
            <li>
              <Link href="/help/workflow-recipes" className="hover:text-foreground hover:underline">
                Workflow recipes
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" aria-hidden="true" />
            </li>
            <li aria-current="page" className="text-foreground">
              {SHORT_NAME[meta.id] ?? meta.name}
            </li>
          </ol>
        </nav>

        {/* Header */}
        <header className="space-y-4">
          <div className="flex items-center gap-3">
            <span className="inline-flex size-11 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
              <Icon className="size-5" aria-hidden="true" />
            </span>
            <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">
              {recipes.length || meta.recipeCount} ready-to-build workflow
              {(recipes.length || meta.recipeCount) === 1 ? "" : "s"}
            </p>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
            AI agent workflows for {meta.name}
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">{meta.tagline}</p>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">
            Each recipe below is expressed only in <BrandName />
            &apos;s real workflow building blocks — Trigger, Agent, Knowledge, Condition, Approval, Output, Tool (MCP),
            Voice, Device, Loop, Code and Parallel — so it maps 1:1 to something you can assemble in the Workflow
            designer. Consequential actions always pass a human Approval step.
          </p>
        </header>

        {/* Recipes */}
        <div className="mt-10 space-y-12">
          {recipes.map((r, i) => (
            <RecipeArticle key={r.id} recipe={r} index={i} />
          ))}
        </div>

        {/* Related industries from the same pack */}
        {siblings.length > 0 ? (
          <section className="mt-12 border-t border-border pt-8">
            <SectionLabel>Related industries</SectionLabel>
            <ul className="mt-3 space-y-2">
              {siblings.map((s) => (
                <li key={s.id}>
                  <Link
                    href={`/help/workflow-recipes/${s.id}`}
                    className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                  >
                    AI agent workflows for {s.name}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* Closing CTAs */}
        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <div className="flex items-start gap-3">
            <Sparkles className="mt-1 size-5 shrink-0 text-primary" aria-hidden="true" />
            <div className="space-y-2">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">Build one of these in minutes</h2>
              <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
                The sandbox gives you a live agent workspace — no account, no card. Or head back to the full catalogue
                and compare patterns across every industry.
              </p>
            </div>
          </div>
          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Link
              href="/try"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              Try this in the sandbox — no signup
              <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
            <Link
              href="/help/workflow-recipes"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              Browse all industries
            </Link>
          </div>
        </section>

        {/* Internal-link fan-out (content-onpage SEO audit, 2026-07-30): the
            largest single page cluster on the site (this template drives all
            89 industry URLs) never linked to /compare, /why-vegaduta or
            /whatsapp-cost-calculator — one small addition here fans real
            internal link equity out across every one of those pages. */}
        <section className="mt-8 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
          <Link href="/compare" className="font-medium text-primary underline-offset-4 hover:underline">
            See how VegaDūta compares to n8n, Dify and Botpress
          </Link>
          <span aria-hidden="true">·</span>
          <Link href="/why-vegaduta" className="font-medium text-primary underline-offset-4 hover:underline">
            Why VegaDūta&apos;s architecture
          </Link>
          <span aria-hidden="true">·</span>
          <Link
            href="/whatsapp-cost-calculator"
            className="font-medium text-primary underline-offset-4 hover:underline"
          >
            Estimate your WhatsApp API costs
          </Link>
        </section>
      </main>
    </div>
  );
}
