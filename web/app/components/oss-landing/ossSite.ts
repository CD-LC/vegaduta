import { getPublicEnv } from "../../lib/env";
import { academyHref } from "../../lib/academyUrl";
import { REPO_URL, docPageHref, docsHref, hasDocsSite } from "../../lib/siteLinks";

export { REPO_URL };

// Shared facts for the open-source public pages (OssLanding, OssAbout).
// Every claim here is taken from README.md / docs/ in this repo; keep it that
// way (no capacity, latency or customer numbers).

export const LICENSE_NAME = "Business Source License 1.1";
export const CONTACT_EMAIL = getPublicEnv("NEXT_PUBLIC_CONTACT_EMAIL") || "";
export const RELEASES_URL = `${REPO_URL}/releases`;

export const GETTING_STARTED =
  "The release ships pinned, signed container images - vegaduta up pulls them; no source build.";

export const INSTALL_COMMANDS = `curl -fsSL https://raw.githubusercontent.com/CD-LC/vegaduta/main/install.sh | sh
./scripts/vegaduta up --lite`;

export const STACK: { name: string; detail: string }[] = [
  { name: "Core", detail: "Java 21, Spring Boot, one Maven reactor of focused modules" },
  { name: "Consoles", detail: "Next.js: tenant console and platform-admin console" },
  { name: "Data", detail: "Postgres 16 with pgvector and Apache AGE; one schema per tenant" },
  { name: "Cache and queues", detail: "Valkey; optional Temporal for durable workflows" },
  { name: "Identity", detail: "Keycloak (OIDC), SSO, per-tenant isolation" },
  { name: "Services", detail: "Python sidecar (ML), code-intel, sandboxed tool runner, MCP stdio runner" },
];

export const CAPABILITIES: { title: string; body: string }[] = [
  {
    title: "Agents and tools",
    body: "Build agents on any supported LLM provider. Import OpenAPI 3.x / Swagger 2.0 specs as tools, register MCP servers per tenant, and attach Agent Skills (SKILL.md).",
  },
  {
    title: "An Assistant for every user",
    body: "Everyone gets their own Assistant agent with routines, threads, scheduled tasks and learned skills - on every install shape, personal to full.",
  },
  {
    title: "Workflows with approvals",
    body: "A node-based workflow engine with human-in-the-loop approval steps, hosted forms, and an audit log of who decided what.",
  },
  {
    title: "Retrieval and graph memory",
    body: "Ground agents in your own data with pgvector retrieval and graph memory on Apache AGE.",
  },
  {
    title: "Omnichannel",
    body: "Reach agents over web chat, voice, email, WhatsApp and other channels through adapters that share one approval model.",
  },
  {
    title: "Build Studio",
    body: "Draft an agent from a description, preview it, and publish it to a tenant - the same pipeline the hosted builder runs.",
  },
  {
    title: "Guardrails and governance",
    body: "Content-filtering guardrails, budgets, tenant isolation, and role-based access, enforced in-process on the chat hot path.",
  },
  {
    title: "Artifacts, tables, schedules",
    body: "Agents produce files and datasets, keep structured data tables, and run work on a schedule - all tenant-scoped.",
  },
  {
    title: "Browser automation",
    body: "An opt-in browser runner lets agents navigate, read and fill real pages under the same approval and audit rules.",
  },
  {
    title: "Learning and Academy",
    body: "Agents learn reusable skills with proposals you approve; the hosted Academy at academy.vegaduta.ai teaches the platform end to end.",
  },
];

export const STANDARDS: { name: string; detail: string }[] = [
  { name: "MCP", detail: "client and server (Streamable HTTP, SSE, stdio)" },
  { name: "A2A", detail: "signed agent cards, JSON-RPC message/send and tasks" },
  { name: "AG-UI", detail: "server-sent-event agent runs" },
  { name: "OpenTelemetry", detail: "OTLP traces with gen_ai.* attributes" },
  { name: "OIDC", detail: "Keycloak or any compliant identity provider" },
  { name: "OpenAPI", detail: "3.x and Swagger 2.0 import as tools" },
];

export const TIERS: { name: string; body: string; cmd: string }[] = [
  {
    name: "Personal",
    body: "One person on a laptop: the Setup app, a bundled local model, sign-in with a passkey or PIN. Nothing to deploy.",
    cmd: "See /downloads",
  },
  {
    name: "Lite",
    body: "A small team's box: five containers, Keycloak sign-in, your own provider keys or host Ollama.",
    cmd: "./scripts/vegaduta up --lite",
  },
  {
    name: "Full",
    body: "The whole platform on compose: admin console, object storage, PgBouncer, every opt-in profile.",
    cmd: "./scripts/vegaduta up --full",
  },
  {
    name: "Kubernetes",
    body: "Umbrella Helm chart with scalable API, jobs, optional worker and every optional service as its own chart.",
    cmd: "helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta",
  },
];

export const COMMUNITY_LINKS: { label: string; href: string }[] = [
  { label: "Source on GitHub", href: REPO_URL },
  { label: "Contributing", href: `${REPO_URL}/blob/main/CONTRIBUTING.md` },
  { label: "Governance", href: `${REPO_URL}/blob/main/GOVERNANCE.md` },
  { label: "Security policy", href: `${REPO_URL}/blob/main/SECURITY.md` },
  { label: "Roadmap", href: `${REPO_URL}/blob/main/ROADMAP.md` },
  { label: "Code of conduct", href: `${REPO_URL}/blob/main/CODE_OF_CONDUCT.md` },
  { label: "Changelog", href: "/changelog" },
  { label: "API reference", href: "/api-reference" },
];

// ---------------------------------------------------------------------------
// The suite: three ways a visitor meets VegaDūta - run it, build on it, or
// extend it. Links go where the artifacts actually live.
// ---------------------------------------------------------------------------

export const SUITE: { title: string; body: string; links: { label: string; href: string }[] }[] = [
  {
    title: "The platform",
    body: "Agents with tools, an Assistant per user, workflows with human approval, retrieval and graph memory, omnichannel reach, guardrails, budgets and a sealed audit log. Try it in the browser, then put it on your own box.",
    links: [
      { label: "Try it live", href: "/try" },
      { label: "Install guide", href: docPageHref("installation") },
      { label: "API reference", href: "/api-reference" },
    ],
  },
  {
    title: "The SDKs",
    body: "Governance inside your own application: @Governed risk labels, human approval, budgets, idempotent effects and audit around any tool call. Java Agent SDK, Python Agent SDK, and generated TypeScript/Python REST clients.",
    links: [
      { label: "Java Agent SDK", href: docPageHref("javaSdk") },
      { label: "Python Agent SDK", href: `${REPO_URL}/blob/main/sdk/python-agent/README.md` },
      { label: "All SDKs", href: docPageHref("sdkOverview") },
    ],
  },
  {
    title: "Plugins and apps",
    body: "IDE extensions for VS Code, JetBrains and Eclipse; Chrome and Edge companions; the Android app (beta); a Wear OS preview; a desktop agent; and VegaDūta itself as an MCP server your own client can drive.",
    links: [
      { label: "Plugins and apps", href: "/plugins" },
      { label: "Reference guide", href: docPageHref("pluginsAndApps") },
      { label: "Example MCP servers", href: `${REPO_URL}/tree/main/examples/mcp-servers` },
    ],
  },
];

// ---------------------------------------------------------------------------
// Get it: the per-OS installer cards. The /downloads page resolves the real
// asset names from the latest release; these cards point there and name the
// artifact so the visitor knows exactly what they get.
// ---------------------------------------------------------------------------

export const DOWNLOAD_CARDS: { os: string; file: string; note: string }[] = [
  {
    os: "macOS",
    file: "VegaDuta-Setup-<version>.dmg",
    note: "Universal app - checks Docker, pulls the signed release, opens the console. Gatekeeper warns once (unsigned build).",
  },
  {
    os: "Windows",
    file: "vegaduta-setup-windows-amd64.zip",
    note: "Unzip and run the Setup exe; SmartScreen warns once. Installs the personal shape by default.",
  },
  {
    os: "Linux",
    file: "vegaduta-setup-linux-{amd64,arm64}.tar.gz",
    note: "Both architectures. Untar, run the setup binary, done - or use the one-line terminal installer below.",
  },
];

export const HELM_COMMAND = "helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta";

export const VERIFY_NOTE =
  "Every release artifact is listed in SHA256SUMS.txt - verify before you run. Images are cosign-signed and carry SBOMs.";

// ---------------------------------------------------------------------------
// Using VegaDūta from your own code, and everywhere it runs. Statuses are the
// real ones (see docs/sdk/README.md and docs/plugins-and-apps.md); nothing is
// published to a registry or a store unless the text says so.
// ---------------------------------------------------------------------------

export const SDK_MAVEN_SNIPPET = `<dependency>
  <groupId>io.vegaduta</groupId>
  <artifactId>vegaduta-agent-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>`;

export const SDK_JAVA_SNIPPET = `@Tool(description = "Refund an order")
@Governed(risk = RiskLabel.HIGH, requiresApproval = true, idempotent = true)
String refund(String orderId, long cents) { ... }`;

export const SDK_BUILD_COMMAND = "git checkout v0.1.4 && mvn -f sdk/java/pom.xml install";

export const DEPENDENCIES: { name: string; coordinates: string; body: string; status: string }[] = [
  {
    name: "Java Agent SDK",
    coordinates: "io.vegaduta:vegaduta-agent-*",
    body: "Governance inside your own Java application: tool risk classification, human approval, budgets, idempotent effects, guardrails, hooks, audit and telemetry around Spring AI tools. The core has no framework dependency.",
    status: "Apache-2.0. Builds reproducibly from the v0.1.4 tag; Maven Central publishing is on the roadmap.",
  },
  {
    name: "Python Agent SDK",
    coordinates: "vegaduta-agent (PyPI name reserved)",
    body: "The same governance contracts for Python: @governed, ApprovalGate, budgets, guardrails, tamper-evident evidence chain - dependency-free, transports are injected ports.",
    status: "Apache-2.0. In sdk/python-agent. First PyPI publish is on the roadmap.",
  },
  {
    name: "TypeScript client",
    coordinates: "vegaduta-sdk",
    body: "A dependency-free client for the platform API, generated from its OpenAPI document: agents, chat streaming, workflows.",
    status: "In sdk/typescript. The package name is a placeholder until a registry name is reserved.",
  },
  {
    name: "Python client",
    coordinates: "vegaduta_sdk",
    body: "The same client for Python 3.9+, standard library only.",
    status: "In sdk/python. Not published to PyPI yet.",
  },
];

/** Links into the documentation. Functions, not constants: the docs site URL is runtime configuration. */
export function resourceLinks(): { label: string; href: string }[] {
  return [
    { label: "Downloads", href: "/downloads" },
    // The Academy is a separate, proprietary deployable - the OSS pages link
    // to it and never carry its content. academyHref() is the configured
    // origin (hosted academy.vegaduta.ai by default).
    { label: "Academy", href: academyHref() },
    { label: hasDocsSite() ? "Documentation" : "Help and docs", href: docsHref() },
    { label: "Java Agent SDK", href: docPageHref("javaSdk") },
    { label: "SDKs and dependencies", href: docPageHref("sdkOverview") },
    { label: "Plugins, apps and mobile", href: docPageHref("pluginsAndApps") },
  ];
}
