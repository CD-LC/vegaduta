"use client";

import { useState } from "react";
import { PLATFORM_FACTS } from "../../lib/platform-facts";

// Plan prices come from the facts module, never a literal in this file.
const PLAN_LINE = PLATFORM_FACTS.plans
  .map((p) => (p.priceUsd === 0 ? p.name : `${p.name} at $${p.priceUsd} a month`))
  .join(", ");

export default function EnterpriseFaq() {
  const [openIdx, setOpenIdx] = useState<number | null>(null);

  // Answers rewritten 2026-10-03 against what exists. Removed: "4-8 weeks",
  // "GDPR/SOC2 compliance options", "SLA guarantees", "24/7 support tiers"
  // and a list of industries we have "experience across" — none had evidence.
  // docs/compliance/README.md is explicit that there is no SOC 2 or ISO
  // certificate; the only permitted wording is the one used below.
  const faqs = [
    {
      q: "How long does implementation take?",
      a: "It depends on the process. A first agent built from a one-sentence description runs in the sandbox within minutes; connecting it to your systems, data and approval rules is the real work, and we scope that with you on the first call instead of quoting a number here.",
    },
    {
      q: "What does it cost?",
      a: `The platform has public plans: ${PLAN_LINE}. Implementation help is quoted separately once we understand the scope.`,
    },
    {
      q: "Does it work with our existing systems?",
      a: "Agents call your systems through HTTP and OpenAPI tools, webhooks, and MCP servers, and can read your documents through the knowledge base. If a system has an API, an agent can usually reach it. There is no prebuilt SAP connector.",
    },
    {
      q: "What about security and compliance?",
      a: "Each tenant has its own database schema, provider keys and channel secrets are encrypted at rest, sign-in runs through Keycloak (OIDC with PKCE), and SSO and SCIM are available per workspace. VegaDūta is not SOC 2 or ISO certified. We maintain a documented control program aligned to those standards in preparation for formal assessment, and we will share it on request.",
    },
    {
      q: "Can we run it ourselves?",
      a: "The code is licensed Apache-2.0 and includes a Docker Compose stack for self-hosting. The public repository is still being prepared, so ask us for access today. The managed service runs in one cloud region.",
    },
    {
      q: "How do we know what an agent did, and why?",
      a: "Every action, tool call and approval is in the audit log. Agent Lab replays a run as a graph and re-runs it from any step, and eval datasets can gate a new agent version before it is promoted to Production.",
    },
    {
      q: "Who supports us after launch?",
      a: "You reach the engineers who build the platform, by email. We do not offer a contractual uptime or response-time SLA today, and we would rather say so now than in a procurement review.",
    },
    {
      q: "Do we need machine-learning expertise?",
      a: "No. Agents are described in plain language, tested in the Playground and the Lab, and changed through versions you can roll back. The free Academy has 280+ lessons for the people who will run them.",
    },
  ];

  return (
    <section className="enterprise-faq">
      <div className="enterprise-faq-container">
        <h2 className="enterprise-faq-title">Frequently Asked Questions</h2>
        <div className="enterprise-faq-list">
          {faqs.map((faq, idx) => (
            <div
              key={idx}
              className={`enterprise-faq-item ${openIdx === idx ? "open" : ""}`}
            >
              <button
                type="button"
                className="enterprise-faq-question"
                onClick={() => setOpenIdx(openIdx === idx ? null : idx)}
                aria-expanded={openIdx === idx}
                aria-controls={`enterprise-faq-a${idx}`}
              >
                <span>{faq.q}</span>
                <span className="enterprise-faq-toggle" aria-hidden="true">
                  {openIdx === idx ? "−" : "+"}
                </span>
              </button>
              {openIdx === idx && (
                <div className="enterprise-faq-answer" id={`enterprise-faq-a${idx}`}>
                  <p>{faq.a}</p>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
