"use client";

import { PLATFORM_FACTS } from "../../lib/platform-facts";

// Copy rewritten 2026-10-03. The earlier hero showed "10x Faster Deployment"
// and "50% Cost Reduction" — numbers nobody measured. The stat card now shows
// counts that come from lib/platform-facts.ts (recounted in core/), and the
// headline says what the platform does instead of promising an outcome.
export default function EnterpriseHero() {
  return (
    <section className="enterprise-hero">
      <div className="enterprise-hero-container">
        <div className="enterprise-hero-content">
          <h1 className="enterprise-hero-title">
            AI agents your IT team can say yes to
          </h1>
          <p className="enterprise-hero-subtitle">
            VegaDūta builds agents that answer customers, run workflows and work in your
            code, with the controls a review board asks about already in place: approval
            gates, an audit log, per-user budgets, tenant isolation, SSO and SCIM. Tell us
            the process you want handled and we will build the first agent with you.
          </p>
          <div className="enterprise-hero-cta">
            <a href="#contact-form" className="enterprise-cta-primary">
              Talk to us about your process
            </a>
            <p className="enterprise-cta-note">No card, no contract to start</p>
          </div>
        </div>

        <div className="enterprise-hero-visual">
          <div className="enterprise-hero-card">
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">{PLATFORM_FACTS.channels}</span>
              <span className="enterprise-hero-label">Customer channels, one agent</span>
            </div>
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">{PLATFORM_FACTS.byokProviders}</span>
              <span className="enterprise-hero-label">Model providers, your own keys</span>
            </div>
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">1</span>
              <span className="enterprise-hero-label">Database schema per tenant</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
