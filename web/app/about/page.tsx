import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import BrandName from "../components/BrandName";
import {
  ArrowRight,
  Bot,
  FlaskConical,
  GitBranch,
  GraduationCap,
  MessageCircle,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Users,
} from "lucide-react";
import { LEGAL_ENTITY, CIN_NUMBER, DIN_NUMBER } from "../lib/legalEntity";
import { SITE_URL } from "../lib/seo-i18n";
import JsonLd from "../components/JsonLd";
import { isOssSite } from "../lib/siteMode";
import OssAbout from "../components/oss-landing/OssAbout";
import { PLATFORM_FACTS } from "../lib/platform-facts";

// ---------------------------------------------------------------------------
// /about — what the product is, how it is built, and who operates this
// deployment. The operator details come from lib/legalEntity.ts
// (deployment-configured); the project description is neutral so any
// deployment of this codebase can serve it unchanged. The page leads with what
// VegaDūta does, in six plain statements, each of which is a shipped
// capability (numbers come from lib/platform-facts.ts).
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

const DESCRIPTION =
  "VegaDūta turns a one-sentence job description into a working AI agent on WhatsApp, voice, email and more, with approvals, budgets and an audit log built in.";

export const metadata: Metadata = {
  title: "About — VegaDūta",
  description: DESCRIPTION,
  alternates: { canonical: "/about" },
  openGraph: {
    title: "About — VegaDūta",
    description: DESCRIPTION,
    url: "/about",
    type: "website",
    images: ["/og-image.png"],
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "AboutPage",
      "@id": `${BASE}/about`,
      url: `${BASE}/about`,
      name: "About VegaDūta",
      about: { "@id": `${BASE}/#organization` },
      publisher: { "@id": `${BASE}/#organization` },
      inLanguage: "en",
    },
  ],
};

const WHAT_IT_DOES = [
  {
    icon: Bot,
    title: "Builds the agent from a sentence",
    body: `Describe the job in plain language. It picks the tools from ${PLATFORM_FACTS.builtInToolsFloor}+ built in and any MCP server you connect, chooses a model with a fallback, and gives you an agent you can talk to in about a minute.`,
  },
  {
    icon: MessageCircle,
    title: "Puts it where your customers are",
    body: `One agent, one memory, ${PLATFORM_FACTS.channels} channels: WhatsApp, Telegram, email, voice calls, Slack, Discord, Instagram, Messenger and X. ${PLATFORM_FACTS.freeChannels.slice(0, -1).join(", ")} and ${PLATFORM_FACTS.freeChannels.at(-1)} are on the Free plan.`,
  },
  {
    icon: GitBranch,
    title: "Runs the work behind the conversation",
    body: `Workflows with ${PLATFORM_FACTS.workflowNodeTypes} node types cover schedules, webhooks, branches, loops, parallel steps, bulk email, a leads CRM and human sign-off. Agents can also hand part of a task to a team of specialist agents.`,
  },
  {
    icon: ShieldCheck,
    title: "Keeps you in charge",
    body: `Risky actions wait for your approval. ${PLATFORM_FACTS.guardrailTypes} guardrail types check what goes in and out, budgets cap spend per user, and every action is written to an audit log.`,
  },
  {
    icon: FlaskConical,
    title: "Shows you why it answered that way",
    body: "Agent Lab replays a run as a graph, re-runs it from any step and shows which parts of the prompt matter. Evals score real runs, and a version only becomes Production when you promote it.",
  },
  {
    icon: Smartphone,
    title: "Builds the app around it",
    body: "App Studio turns a description into a multi-page app with a live preview, wired to your agents and workflows, with its own sign-in for your customers.",
  },
] as const;

export default function AboutPage() {
  // Site-mode switch (lib/siteMode.ts): "classic" keeps the page below.
  if (isOssSite()) return <OssAbout />;
  return (
    <main
      lang="en"
      dir="ltr"
      className="vd-pub marketing-root min-h-screen w-full bg-background text-foreground antialiased"
    >
      <JsonLd data={jsonLd} />

      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <div className="mx-auto w-full max-w-4xl px-5 py-10 sm:px-8 sm:py-14">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark.png" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <header className="mt-8 space-y-4">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            About
          </p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight sm:text-4xl">
            You describe the job. <BrandName /> does it, and asks first on the big calls.
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            <BrandName /> is an open-source agentic AI platform. It turns a sentence into a working
            agent, puts that agent on the channels your customers already use, and wraps everything
            it does in approvals, budgets and an audit trail. Vega means speed and dūta means
            messenger: a fast messenger that carries work between you, your customers and your
            systems.
          </p>
        </header>

        {/* What the platform does */}
        <section className="mt-12" aria-labelledby="what-it-does">
          <h2 id="what-it-does" className="text-lg font-semibold tracking-tight text-foreground sm:text-xl">
            What it does
          </h2>
          <div className="mt-5 grid gap-4 sm:grid-cols-2">
            {WHAT_IT_DOES.map(({ icon: Icon, title, body }) => (
              <div key={title} className="rounded-xl border border-border bg-card p-5">
                <Icon className="size-5 text-primary" aria-hidden="true" />
                <h3 className="mt-3 text-sm font-semibold tracking-tight text-foreground">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{body}</p>
              </div>
            ))}
          </div>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground">
            Developers get the same agents inside VS Code and JetBrains, a code-intelligence
            service that maps a repository before an agent edits it, and {PLATFORM_FACTS.byokProviders}{" "}
            model providers to bring their own keys to.{" "}
            <Link href="/platform" className="text-primary underline-offset-2 hover:underline">
              See the platform in detail
            </Link>
            .
          </p>
        </section>

        {/* How it is built */}
        <section className="mt-12" aria-labelledby="how-it-is-built">
          <h2 id="how-it-is-built" className="text-lg font-semibold tracking-tight text-foreground sm:text-xl">
            How it is built
          </h2>
          <div className="mt-5 grid gap-4 sm:grid-cols-3">
            <div className="rounded-xl border border-border bg-card p-5">
              <Users className="size-5 text-primary" aria-hidden="true" />
              <h3 className="mt-3 text-sm font-semibold tracking-tight text-foreground">
                Open source
              </h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                The source code is published under the Apache-2.0 license. Contributions, bug
                reports and security disclosures are welcome through the project repository.
              </p>
            </div>
            <div className="rounded-xl border border-border bg-card p-5">
              <Sparkles className="size-5 text-primary" aria-hidden="true" />
              <h3 className="mt-3 text-sm font-semibold tracking-tight text-foreground">
                A platform, not a chat wrapper
              </h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                Schema-per-tenant data isolation, human approval on consequential actions,
                per-user budgets, and a full audit trail.
              </p>
            </div>
            <div className="rounded-xl border border-border bg-card p-5">
              <GraduationCap className="size-5 text-primary" aria-hidden="true" />
              <h3 className="mt-3 text-sm font-semibold tracking-tight text-foreground">
                Learn and develop
              </h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                The free Academy has 280+ lessons, and the console is available in{" "}
                {PLATFORM_FACTS.interfaceLocales} languages, including all{" "}
                {PLATFORM_FACTS.indianLanguages} scheduled Indian languages.
              </p>
            </div>
          </div>
        </section>

        {/* Honest current state */}
        <section className="mt-12 border-t border-border pt-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground sm:text-xl">
            What it is not, yet
          </h2>
          <p className="mt-4 max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Dozens of integrations, not thousands. MCP servers and HTTP tools cover the rest.{" "}
            <Link href="/compare" className="text-primary underline-offset-2 hover:underline">
              Read how it compares with n8n, Dify and Botpress
            </Link>
            , including where they are better.
          </p>
        </section>

        {/* Registered company */}
        <section className="mt-10 border-t border-border pt-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground sm:text-xl">
            Operator
          </h2>
          <div className="mt-4 max-w-3xl space-y-1.5 text-sm leading-relaxed text-muted-foreground sm:text-base">
            <p>
              <BrandName /> is operated by{" "}
              <span className="text-foreground">{LEGAL_ENTITY}</span>.
            </p>
            {CIN_NUMBER ? <p>CIN: {CIN_NUMBER}</p> : null}
            {DIN_NUMBER ? <p>DIN: {DIN_NUMBER}</p> : null}
          </div>
        </section>

        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            The quickest way to understand it is to use it
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real isolated tenant in seconds — no account required.
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
              href="/why-vegaduta"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              Why we built it this way
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
