import Link from "next/link";
import BrandName from "../BrandName";
import { docPageHref, docsHref } from "../../lib/siteLinks";
import { STATUS_LABEL, surfacesByGroup } from "../plugins/surfaces";
import { OssFooter, OssHeader, SmartLink } from "./OssChrome";
import {
  CAPABILITIES,
  DEPENDENCIES,
  INSTALL_COMMANDS,
  LICENSE_NAME,
  REPO_URL,
  SDK_BUILD_COMMAND,
  SDK_JAVA_SNIPPET,
  SDK_MAVEN_SNIPPET,
  STACK,
  STANDARDS,
  TIERS,
} from "./ossSite";

// The signed-out "/" page when NEXT_PUBLIC_SITE_MODE is "oss" (the default;
// see lib/siteMode.ts). A technical, open-source landing page: what the
// project is, how it is built, the standards it speaks, how to run it and how
// to contribute. Deliberately no plans, prices, trials, testimonials or sales
// contact. Server component; the original page is demo-landing/DemoLanding.

function Section({ id, title, lead, children }: { id: string; title: string; lead?: string; children: React.ReactNode }) {
  return (
    <section id={id} className="border-t border-border py-14">
      <div className="mx-auto max-w-6xl px-4">
        <h2 className="m-0 text-2xl font-semibold tracking-tight text-foreground">{title}</h2>
        {lead && <p className="mb-0 mt-2 max-w-2xl text-muted-foreground">{lead}</p>}
        <div className="mt-8">{children}</div>
      </div>
    </section>
  );
}

function Code({ children }: { children: string }) {
  return (
    <pre className="m-0 overflow-x-auto rounded-lg border border-border bg-muted p-4 text-sm leading-relaxed text-foreground">
      <code>{children}</code>
    </pre>
  );
}

export default function OssLanding() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <OssHeader />
      <main>
        <section className="mx-auto grid max-w-6xl gap-10 px-4 py-16 lg:grid-cols-[1.1fr_1fr] lg:items-center">
          <div className="min-w-0">
            <p className="m-0 mb-4 inline-block rounded-full border border-border px-3 py-1 text-xs text-muted-foreground">
              Open source · {LICENSE_NAME} · self-hostable
            </p>
            <h1 className="m-0 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
              <BrandName />: build, govern and run AI agents on your own infrastructure
            </h1>
            <p className="mb-0 mt-5 max-w-xl text-lg text-muted-foreground">
              A multi-tenant agent platform with MCP, A2A and AG-UI support, workflows with human approval, retrieval
              over your own data, and a Java 21 / Spring Boot core you can read, fork and extend.
            </p>
            <div className="mt-7 flex flex-wrap gap-3 text-sm">
              <a
                href={REPO_URL}
                rel="noopener noreferrer"
                className="rounded-md bg-primary px-4 py-2 font-medium text-primary-foreground hover:opacity-90"
              >
                View on GitHub
              </a>
              <SmartLink href={docsHref()} className="rounded-md border border-border px-4 py-2 font-medium hover:bg-muted">
                Read the docs
              </SmartLink>
              <Link href="/signup" className="rounded-md border border-border px-4 py-2 font-medium hover:bg-muted">
                Sign up to your instance
              </Link>
            </div>
          </div>
          <div className="min-w-0">
            <p className="m-0 mb-2 text-xs uppercase tracking-wide text-muted-foreground">Run it locally</p>
            <Code>{INSTALL_COMMANDS}</Code>
            <p className="mb-0 mt-2 text-xs text-muted-foreground">
              Needs Docker with Compose v2 and an LLM provider key or a local Ollama. First build compiles from source.
            </p>
          </div>
        </section>

        <Section
          id="capabilities"
          title="What is in the box"
          lead="Every item below is a module in the repository, not a roadmap slide."
        >
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {CAPABILITIES.map((c) => (
              <div key={c.title} className="rounded-lg border border-border p-5">
                <h3 className="m-0 text-base font-semibold">{c.title}</h3>
                <p className="mb-0 mt-2 text-sm text-muted-foreground">{c.body}</p>
              </div>
            ))}
          </div>
        </Section>

        <Section id="stack" title="Architecture and stack" lead="A modular monolith core with optional services for async work and untrusted code.">
          <dl className="m-0 grid gap-px overflow-hidden rounded-lg border border-border bg-border sm:grid-cols-2">
            {STACK.map((s) => (
              <div key={s.name} className="bg-background p-4">
                <dt className="text-sm font-semibold">{s.name}</dt>
                <dd className="m-0 mt-1 text-sm text-muted-foreground">{s.detail}</dd>
              </div>
            ))}
          </dl>
        </Section>

        <Section id="standards" title="Open standards" lead="Interoperable by default, so you are not locked to one client or vendor.">
          <ul className="m-0 grid list-none gap-3 p-0 sm:grid-cols-2 lg:grid-cols-3">
            {STANDARDS.map((s) => (
              <li key={s.name} className="rounded-lg border border-border p-4 text-sm">
                <span className="font-mono font-semibold">{s.name}</span>
                <span className="text-muted-foreground"> · {s.detail}</span>
              </li>
            ))}
          </ul>
        </Section>

        <Section
          id="dependencies"
          title="Use it as a dependency"
          lead="Run the platform, or embed the governance layer in your own application. They are separate things; pick by what your code is."
        >
          <div className="grid gap-6 lg:grid-cols-[1.1fr_1fr]">
            <div className="min-w-0 space-y-4">
              <p className="m-0 text-sm text-muted-foreground">
                The Java Agent SDK wraps the tools you already have (Spring AI today, any framework through its
                framework-free core) so that a risky call waits for a human, a run has a budget, a retried turn never
                repeats a payment, and every action leaves an audit event tagged with the OWASP agentic risk it
                relates to.
              </p>
              <Code>{SDK_MAVEN_SNIPPET}</Code>
              <Code>{SDK_JAVA_SNIPPET}</Code>
              <p className="m-0 text-xs text-muted-foreground">
                Not on Maven Central yet. Build it from a checkout with <code>{SDK_BUILD_COMMAND}</code> and depend on
                the snapshot.
              </p>
              <div className="flex flex-wrap gap-3 text-sm">
                <SmartLink
                  href={docPageHref("javaSdk")}
                  className="rounded-md bg-primary px-4 py-2 font-medium text-primary-foreground hover:opacity-90"
                >
                  Java Agent SDK docs
                </SmartLink>
                <SmartLink href={docPageHref("sdkOverview")} className="rounded-md border border-border px-4 py-2 font-medium hover:bg-muted">
                  All SDKs
                </SmartLink>
              </div>
            </div>
            <ul className="m-0 grid list-none gap-3 p-0">
              {DEPENDENCIES.map((d) => (
                <li key={d.name} className="rounded-lg border border-border p-4 text-sm">
                  <p className="m-0 font-semibold">{d.name}</p>
                  <p className="m-0 mt-1 font-mono text-xs text-muted-foreground">{d.coordinates}</p>
                  <p className="m-0 mt-2 text-muted-foreground">{d.body}</p>
                  <p className="m-0 mt-2 text-xs text-foreground">{d.status}</p>
                </li>
              ))}
            </ul>
          </div>
        </Section>

        <Section
          id="surfaces"
          title="Where it runs"
          lead="Editors, browsers, phones and your own MCP client, each with where it really stands."
        >
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {surfacesByGroup().map((g) => (
              <div key={g.group}>
                <h3 className="m-0 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{g.label}</h3>
                <ul className="m-0 mt-3 list-none space-y-2 p-0 text-sm">
                  {g.items.map((s) => (
                    <li key={s.id} className="flex items-center justify-between gap-2">
                      <span>{s.name}</span>
                      <span className="shrink-0 rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
                        {STATUS_LABEL[s.status]}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
          <div className="mt-6 flex flex-wrap gap-3 text-sm">
            <Link href="/plugins" className="rounded-md border border-border px-4 py-2 font-medium hover:bg-muted">
              Downloads and status
            </Link>
            <SmartLink
              href={docPageHref("pluginsAndApps")}
              className="rounded-md border border-border px-4 py-2 font-medium hover:bg-muted"
            >
              Plugins, apps and mobile reference
            </SmartLink>
          </div>
        </Section>

        <Section id="install" title="Install tiers" lead="All tiers run the same images.">
          <div className="grid gap-4 lg:grid-cols-3">
            {TIERS.map((t) => (
              <div key={t.name} className="flex flex-col gap-3 rounded-lg border border-border p-5">
                <h3 className="m-0 text-base font-semibold">{t.name}</h3>
                <p className="m-0 flex-1 text-sm text-muted-foreground">{t.body}</p>
                <Code>{t.cmd}</Code>
              </div>
            ))}
          </div>
        </Section>

        <Section id="community" title="Built in the open" lead={`Licensed ${LICENSE_NAME}. Issues, pull requests and discussion happen on GitHub.`}>
          <div className="flex flex-wrap gap-3 text-sm">
            <a href={`${REPO_URL}/blob/main/CONTRIBUTING.md`} rel="noopener noreferrer" className="rounded-md border border-border px-4 py-2 hover:bg-muted">
              Contributing guide
            </a>
            <a href={`${REPO_URL}/issues`} rel="noopener noreferrer" className="rounded-md border border-border px-4 py-2 hover:bg-muted">
              Open issues
            </a>
            <a href={`${REPO_URL}/blob/main/SECURITY.md`} rel="noopener noreferrer" className="rounded-md border border-border px-4 py-2 hover:bg-muted">
              Report a vulnerability
            </a>
          </div>
        </Section>
      </main>
      <OssFooter />
    </div>
  );
}
