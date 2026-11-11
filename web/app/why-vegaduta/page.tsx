import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import BrandName from "../components/BrandName";
import {
  ArrowRight,
  BrainCircuit,
  Database,
  FlaskConical,
  IndianRupee,
  Landmark,
  ShieldCheck,
  Wallet,
} from "lucide-react";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// SEO authority pack — /why-vegaduta (2026-07-25). The roadmap §3 #5
// architecture/trust page: "Why we built an agent platform on Spring
// Boot/Java 21, schema-per-tenant Postgres, and Keycloak", aimed at the
// Indian IT-services/banking mindset where Java is a trust signal. Fully
// server-rendered, no auth chrome. The honest current-state section
// (single-region, no SOC 2) is deliberate — the roadmap's own caveat is that
// this credibility is "a story, not yet a fact" until reliability and a
// compliance artifact exist, so the page says so plainly.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

export const metadata: Metadata = {
  title: "Why VegaDūta Runs on Java 21, Postgres & Keycloak",
  description:
    "The architecture behind VegaDūta's AI agent platform: Spring Boot/Java 21 with Spring AI orchestration, schema-per-tenant Postgres, Keycloak, guardrails, budgets, audit, and a run lab.",
  alternates: { canonical: "/why-vegaduta" },
  openGraph: {
    title: "Why VegaDūta Runs on Java 21, Postgres & Keycloak",
    description:
      "The architecture behind VegaDūta's AI agent platform: Spring Boot/Java 21 with Spring AI orchestration, schema-per-tenant Postgres, Keycloak, guardrails, budgets, audit, and a run lab.",
    url: "/why-vegaduta",
    type: "article",
    images: ["/og-image.png"],
  },
};

// Structured-data audit (2026-07-30): this page used to declare its OWN
// Organization node ({@id: `${BASE}/#organization`, logo: icon.png}) on top
// of the sitewide one layout.tsx already renders on every route (name:
// VegaDūta, logo: logo-mark.png). Because layout.tsx wraps every page, both
// scripts rendered on the SAME URL simultaneously — two Organization nodes
// for the same company with different logo URLs and no shared @id to merge
// them. Fix: don't redeclare Organization here at all — reference the
// sitewide node's @id (layout.tsx's STRUCTURED_DATA now carries the same
// `${BASE}/#organization` id) via `about`/`publisher` instead.
const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebPage",
      "@id": `${BASE}/why-vegaduta`,
      url: `${BASE}/why-vegaduta`,
      name: "Why we built an agent platform on Spring Boot/Java 21, schema-per-tenant Postgres, and Keycloak",
      about: { "@id": `${BASE}/#organization` },
      publisher: { "@id": `${BASE}/#organization` },
      inLanguage: "en",
    },
  ],
};

function Section({
  icon: Icon,
  title,
  children,
}: {
  icon: React.ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" | "false" }>;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="border-t border-border pt-8">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
          <Icon className="size-5" aria-hidden="true" />
        </span>
        <h2 className="text-lg font-semibold tracking-tight text-foreground sm:text-xl">{title}</h2>
      </div>
      <div className="mt-4 max-w-3xl space-y-4 text-sm leading-relaxed text-muted-foreground sm:text-base">
        {children}
      </div>
    </section>
  );
}

export default function WhyVegadutaPage() {
  return (
    <main className="vd-pub marketing-root min-h-screen w-full bg-background text-foreground antialiased">
      <JsonLd data={jsonLd} />

      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <div className="mx-auto w-full max-w-4xl px-5 py-10 sm:px-8 sm:py-14">
        <Link
          href="/"
          className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight"
          aria-label="VegaDūta home"
        >
          <Image
            src="/logo-mark.png"
            alt=""
            height={32}
            width={44}
            className="h-8 w-auto object-contain"
            style={{ height: 32, width: "auto" }}
            draggable={false}
          />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        {/* Header */}
        <header className="mt-8 space-y-4">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">Architecture &amp; trust</p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight sm:text-4xl">
            Why we built an agent platform on Spring Boot/Java 21, schema-per-tenant Postgres, and Keycloak
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Most AI-agent platforms are Python or Node apps that grew out of prototyping tools.
            <BrandName /> made a different bet: build the runtime the way Indian IT services and banks build systems
            that must not lose data — a multi-module Spring Boot/Java 21 backend, hard tenant isolation in Postgres, and
            identity delegated to Keycloak (OAuth2/OIDC). This page explains the choices, and is equally plain about
            what we haven&apos;t built yet.
          </p>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            The payoff for you as a customer: an agentic AI platform that behaves like enterprise software rather than a
            demo — the Java/Postgres/Keycloak backbone behind <BrandName />
            &apos;s AI agents, built for real AI business automation in Indian businesses, from a kirana store&apos;s
            WhatsApp number to a bank&apos;s compliance review.
          </p>
        </header>

        <div className="mt-10 space-y-10">
          <Section icon={Database} title="Real tenant isolation: schema-per-tenant Postgres">
            <p>
              Multi-tenancy in most agent tools means rows tagged with a workspace ID in shared tables — one missed{" "}
              <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">WHERE</code> clause away from
              showing one customer another customer&apos;s data. <BrandName /> gives every tenant its own Postgres
              schema. An agency running agents for twenty clients gets twenty isolated schemas under one deployment; a
              query physically cannot wander into a neighbour&apos;s tables.
            </p>
            <p>
              Secrets follow the same boundary: provider API keys and channel credentials are encrypted at rest, per
              tenant. Isolation this hard is what the big workflow tools sell as white-label enterprise contracts; here
              it is the default architecture, on every plan.
            </p>
          </Section>

          <Section icon={ShieldCheck} title="Guardrails, human approval, and an audit trail">
            <p>
              Agents that act on your business need brakes, not just prompts. <BrandName />
              &apos;s runtime enforces guardrails in the platform — including credential redaction on tool traffic —
              rather than trusting each agent&apos;s prompt to behave. Consequential actions can be routed through an
              explicit human-approval step: the agent proposes, a person approves, and only then does the action
              execute.
            </p>
            <p>
              Everything lands in an audit log, scoped to the tenant. When someone asks &quot;why did the agent say
              that, and who approved the refund?&quot; there is an answer — which is the difference between a demo and a
              system a bank-adjacent buyer will sit through a review with.
            </p>
            <p>
              Identity is not homegrown. Authentication and session management are delegated to Keycloak, the same
              open-source identity server used across enterprises — OAuth2/OIDC with PKCE, not a bespoke login table.
              Workspaces can add single sign-on through their own identity provider and SCIM user provisioning, set up
              by a workspace admin; this has not yet been validated against a customer&apos;s own identity provider.
            </p>
          </Section>

          <Section icon={FlaskConical} title="Evidence, not vibes: versions, evals and a run lab">
            <p>
              &quot;The agent did something odd&quot; is not a bug report anyone can act on. So every change to an agent
              or a workflow is kept as a version, with Staging and Production labels and one-click restore. Eval
              datasets scored by six LLM judges can gate a promotion, so a prompt that reads better but answers worse
              does not reach customers.
            </p>
            <p>
              When a run does go wrong, Agent Lab shows it as a graph of every model call and tool call, re-runs it from
              the step you choose, removes prompt sections one at a time to show which ones the answer depends on, and
              repeats an input to measure how consistent the agent is. When a job is too big for one agent, a lead agent
              hands parts of it to specialist agents in parallel, one level deep and under one shared run budget.
            </p>
          </Section>

          <Section icon={Wallet} title="Per-user dollar budgets: cost control as a primitive">
            <p>
              The fastest way to lose trust in an AI platform is a surprise model bill. <BrandName />
              meters model spend per user per day against a configurable dollar budget, enforced in the platform — when
              the budget is reached, the platform degrades predictably instead of billing infinitely. Combined with a
              hosted-model pool that falls back across providers when one fails, cost and availability are runtime
              concerns the platform owns, not spreadsheet estimates.
            </p>
          </Section>

          <Section icon={Landmark} title="Why Java? Because the buyer's engineers can read it">
            <p>
              Java is not fashionable in AI, and that is precisely the point. Indian IT services, banks, and mid-market
              enterprises run on the JVM; their engineers can audit a Spring Boot codebase, their ops teams know how to
              run one, and their security reviews know what questions to ask of it. A typed, multi-module Java 21
              backend with explicit module boundaries (billing, tenancy, agents, guardrails, channels, audit) is easier
              to reason about under load than a prototype that grew organically.
            </p>
            <p>
              The AI-native parts live where they belong: Python services handle knowledge/RAG-pipeline work and
              codebase intelligence, fronted by the same Java core and the same identity, tenancy and audit rules.
              Indian-language tools can run through Sarvam&apos;s India-built models, and voice through Sarvam, Whisper
              or ElevenLabs. The stack is boring where boring wins, and modern where it matters.
            </p>
          </Section>

          <Section icon={BrainCircuit} title="Spring AI, and the agent techniques built on it">
            <p>
              Model orchestration inside the Java core runs on Spring AI — one{" "}
              <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">ChatClient</code>/
              <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">ChatModel</code> abstraction
              wiring Anthropic, OpenAI, Google&apos;s Gemini and Vertex AI, AWS Bedrock, and every OpenAI-compatible
              endpoint we support (Azure OpenAI, OpenRouter, Groq, Mistral, DeepSeek, xAI, Meta, Ollama, Sarvam) behind
              the same interface. A capability registry tracks which model actually accepts tool calling, streaming,
              structured output or prompt caching, so an agent&apos;s configuration is checked against what its model
              can do — not what a provider&apos;s marketing page claims.
            </p>
            <p>The techniques on top of it, in plain terms:</p>
            <ul className="list-disc space-y-2 ps-5">
              <li>
                <span className="text-foreground">Advisors as the governance seam.</span> Spring AI&apos;s advisor chain
                intercepts every model call: one advisor meters tokens and cost per call — which is what the per-user
                budgets above read — while others apply content policy, screen prompts for injection, and redact
                credentials from tool output. Enforcement lives in the interceptor chain, so it fires for every
                provider, every agent, every turn — not when a prompt happens to ask nicely.
              </li>
              <li>
                <span className="text-foreground">Tool calling wrapped in guardrails.</span> Every agent tool is a
                Spring AI <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">ToolCallback</code>{" "}
                behind a guarded decorator that runs policy checks and human-approval gates, and a logging decorator
                that writes to the audit trail. A relevance selector narrows the tool list offered per turn, so context
                — and cost — stays small.
              </li>
              <li>
                <span className="text-foreground">MCP in both directions.</span> As a client, Spring AI&apos;s MCP
                support connects agents to external MCP servers — stdio and streamable HTTP, per-tenant registrations,
                self-healing connections. As a server, the platform exposes your own agents, workflows and knowledge
                bases as MCP tools under API-key access grants, so another system can call them like any other tool.
              </li>
              <li>
                <span className="text-foreground">RAG on pgvector, inside the tenant&apos;s schema.</span> Knowledge
                bases embed into per-tenant pgvector tables with HNSW indexes and a chosen embedding model per
                collection — no separate vector database to isolate. Retrieval is an ordinary platform capability:
                workflow knowledge nodes prepend the retrieved context, and agents can even write back into a collection
                through a tool call.
              </li>
              <li>
                <span className="text-foreground">Memory we built instead of the default.</span> Spring AI&apos;s
                ChatMemory autoconfiguration is deliberately excluded — scoped memory has to respect tenant boundaries.
                What runs instead: per-agent and per-tenant vector memory, session summaries, a graph-memory module on
                Apache AGE that extracts entities and arbitrates competing writes, and a semantic response cache that
                answers repeat questions without spending a model call.
              </li>
              <li>
                <span className="text-foreground">Structured output with a repair loop.</span> Result schemas generated
                by Spring AI&apos;s{" "}
                <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">BeanOutputConverter</code> go
                into the prompt; an output contract validates what comes back and runs a repair turn when it&apos;s
                malformed. A job that asked for JSON never &quot;succeeds&quot; with prose.
              </li>
              <li>
                <span className="text-foreground">LLM-as-judge evaluation, honestly reported.</span> A quality module
                scores turns on helpfulness, accuracy, tone, relevance, safety and faithfulness through the same gateway
                — judge calls pinned to temperature 0 — and an eval runner turns those into regression checks. When
                judging can&apos;t run, the result says &quot;unavailable&quot; rather than fabricating a score.
              </li>
              <li>
                <span className="text-foreground">Engineering around the framework&apos;s edges.</span> Bounded retries
                instead of Spring AI&apos;s default ten-attempt, minutes-long backoff — rate limits route to provider
                fallback rather than retry loops. Token accounting that counts streamed turns, bounded Gemini thinking
                budgets, provider prompt caching, and where the framework itself is broken — an upstream Gemini bug that
                silently dropped tool calls — a patched{" "}
                <code className="rounded bg-muted/50 px-1 py-0.5 font-mono text-[0.85em]">ChatModel</code> subclass in
                our tree rather than a shrug.
              </li>
            </ul>
            <p>
              The point of naming the framework is the same as naming Java: Spring AI is open and documented, and your
              engineers can read how it works — and where we bent it — without taking our word for any of it.
            </p>
          </Section>

          <Section icon={IndianRupee} title="What we haven't built yet — plainly">
            <p>
              Honesty is cheaper than a failed procurement call, so here is the current state, stated without varnish:
            </p>
            <ul className="list-disc space-y-2 ps-5">
              <li>
                <span className="text-foreground">One region, one database server today.</span> The hosted service runs
                in a single Azure region on managed container and database services. The Postgres server has no standby
                and its backups stay in the same region (automated, kept for seven days), so a regional outage or a
                database failure is downtime, not a failover. Metrics and logs go to Azure&apos;s managed monitoring and
                Grafana services. Multi-region high availability is still on the roadmap, not in production.
              </li>
              <li>
                <span className="text-foreground">No SOC 2 or ISO certification yet.</span> We keep a documented control
                program aligned to those standards in preparation for formal assessment, and run internal security
                reviews. If your procurement process requires a certificate today, we are not there yet — we would
                rather tell you that here than in week six of an evaluation.
              </li>
              <li>
                <span className="text-foreground">A smaller ecosystem.</span> Dozens of tool integrations (via MCP
                connectors and built-in tools), not the thousands a decade-old automation platform has — n8n alone
                advertises over 2,000.
              </li>
            </ul>
            <p>
              What is real today: schema-per-tenant isolation, Keycloak-fronted auth, guardrails with human approval and
              audit, per-user budgets, nine channels including WhatsApp, Telegram, email and voice, a console in all 22
              scheduled Indian languages, UPI billing, MCP in both directions (your agents call external tools; other
              systems call your agents), versions with eval-gated promotion, Agent Lab, agent teams, App Studio, plugins
              for VS Code, JetBrains IDEs and Chrome that you install from our own plugins page, and a sandbox you can
              open without an account.
            </p>
          </Section>
        </div>

        {/* CTAs */}
        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">Judge the architecture by using it</h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real isolated tenant in seconds — the same tenancy, guardrails and budgets
            described above, no account required.
          </p>
          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Link
              href="/try"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              Try the sandbox — no signup
              <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
            <Link
              href="/help/workflow-recipes"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              Browse workflow recipes
            </Link>
            <Link
              href="/compare"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              See honest comparisons
            </Link>
            <Link
              href="/whatsapp-cost-calculator"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              Estimate your WhatsApp costs
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
