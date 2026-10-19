import { getPublicEnv } from "../../lib/env";
import { REPO_URL, docPageHref, docsHref, hasDocsSite } from "../../lib/siteLinks";

export { REPO_URL };

// Shared facts for the open-source public pages (OssLanding, OssAbout).
// Every claim here is taken from README.md / docs/ in this repo; keep it that
// way (no capacity, latency or customer numbers).

export const LICENSE_NAME = "Apache-2.0";
export const CONTACT_EMAIL = getPublicEnv("NEXT_PUBLIC_CONTACT_EMAIL") || "";

export const INSTALL_COMMANDS = `git clone ${REPO_URL}.git
cd vegaduta
./scripts/vegaduta up --lite --build`;

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
    title: "Workflows with approvals",
    body: "A node-based workflow engine with human-in-the-loop approval steps, hosted forms, and an audit log of who decided what.",
  },
  {
    title: "Retrieval and graph memory",
    body: "Ground agents in your own data with pgvector retrieval and graph memory on Apache AGE.",
  },
  {
    title: "Channels",
    body: "Reach agents over web chat, voice, email, WhatsApp and other channels through adapters that share one approval model.",
  },
  {
    title: "Guardrails and governance",
    body: "Content-filtering guardrails, budgets, tenant isolation, and role-based access, enforced in-process on the chat hot path.",
  },
  {
    title: "Self-hosted by design",
    body: "Runs from a five-container laptop install to a Helm deployment on Kubernetes. Your data, keys and models stay on your infrastructure.",
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
    name: "Lite",
    body: "Five containers: Postgres, Valkey, Keycloak, core, web. For trying it and local development.",
    cmd: "./scripts/vegaduta up --lite --build",
  },
  {
    name: "Full",
    body: "Adds the admin console, object storage, PgBouncer and opt-in profiles (Temporal, observability, voice, sandbox).",
    cmd: "./scripts/vegaduta up --full --build",
  },
  {
    name: "Kubernetes",
    body: "Umbrella Helm chart with scalable API, jobs, optional worker and every optional service as its own chart.",
    cmd: "helm install vegaduta deploy/helm/charts/vegaduta",
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
// Using VegaDūta from your own code, and everywhere it runs. Statuses are the
// real ones (see docs/sdk/README.md and docs/plugins-and-apps.md); nothing is
// published to a registry or a store unless the text says so.
// ---------------------------------------------------------------------------

export const SDK_MAVEN_SNIPPET = `<dependency>
  <groupId>io.vegaduta</groupId>
  <artifactId>vegaduta-agent-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>`;

export const SDK_JAVA_SNIPPET = `@Tool(description = "Refund an order")
@Governed(risk = RiskLabel.HIGH, requiresApproval = true, idempotent = true)
String refund(String orderId, long cents) { ... }`;

export const SDK_BUILD_COMMAND = "mvn -f sdk/java/pom.xml install";

export const DEPENDENCIES: { name: string; coordinates: string; body: string; status: string }[] = [
  {
    name: "Java Agent SDK",
    coordinates: "io.vegaduta:vegaduta-agent-*",
    body: "Governance inside your own Java application: tool risk classification, human approval, budgets, idempotent effects, guardrails, hooks, audit and telemetry around Spring AI tools. The core has no framework dependency.",
    status: "0.1.0-SNAPSHOT. Build from source; not on Maven Central yet.",
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
    { label: hasDocsSite() ? "Documentation" : "Help and docs", href: docsHref() },
    { label: "Java Agent SDK", href: docPageHref("javaSdk") },
    { label: "SDKs and dependencies", href: docPageHref("sdkOverview") },
    { label: "Plugins, apps and mobile", href: docPageHref("pluginsAndApps") },
  ];
}
