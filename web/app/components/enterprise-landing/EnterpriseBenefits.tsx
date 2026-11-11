"use client";

import { PLATFORM_FACTS } from "../../lib/platform-facts";
import { useTranslations } from "../../lib/i18n";

// Copy rewritten 2026-10-03: each card names a shipped capability rather than
// a promised result ("Proven ROI", "Bank-grade security"). SSO/SCIM is
// deployed but has not been validated against a customer identity provider
// yet, so the card says "available", not "certified".
export default function EnterpriseBenefits() {
  const tHC = useTranslations("hcComponentsEnterpriselandingEnterpriseBenefits");
  const benefits = [
    {
      icon: "🛡️",
      title: "Nothing risky runs unseen",
      description:
        "Set an agent's autonomy level and consequential actions wait in an approval queue. Tool calls pass a confirmation gate, and every action lands in an audit log you can export.",
    },
    {
      icon: "🔬",
      title: "Test it before customers meet it",
      description:
        "Agent Lab replays a run step by step and re-runs it from any point. Eval datasets scored by six judges can block a new version from reaching Production, and any earlier version restores in a click.",
    },
    {
      icon: "💳",
      title: "Spend you can cap",
      description: `Daily budgets per user, rate limits per workspace and a cost figure per run. Bring your own keys for ${PLATFORM_FACTS.byokProviders} providers, or use the hosted pool with automatic failover.`,
    },
    {
      icon: "🏢",
      title: "Fits your identity setup",
      description:
        "Single sign-on through your own identity provider and SCIM user provisioning per workspace, on top of Keycloak sign-in with OIDC and PKCE.",
    },
    {
      icon: "🗄️",
      title: "Your data stays yours",
      description:
        "One database schema per tenant, provider keys and channel secrets encrypted at rest, and full export or erasure on request.",
    },
    {
      icon: "🧩",
      title: "Connects to what you run",
      description: `${PLATFORM_FACTS.builtInToolsFloor}+ built-in tools, HTTP and OpenAPI calls, and MCP servers for everything else. Workflows with ${PLATFORM_FACTS.workflowNodeTypes} node types handle schedules, webhooks, branches and human sign-off.`,
    },
  ];

  return (
    <section className="enterprise-benefits">
      <div className="enterprise-benefits-container">
        <h2 className="enterprise-benefits-title">{tHC("s1")}</h2>
        <div className="enterprise-benefits-grid">
          {benefits.map((benefit, idx) => (
            <div key={idx} className="enterprise-benefit-card">
              <div className="enterprise-benefit-icon">{benefit.icon}</div>
              <h3>{benefit.title}</h3>
              <p>{benefit.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
