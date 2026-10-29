"use client";

import { PLATFORM_FACTS } from "../../lib/platform-facts";
import { useTranslations } from "../../lib/i18n";

// Copy rewritten 2026-10-03. The earlier hero showed "10x Faster Deployment"
// and "50% Cost Reduction" — numbers nobody measured. The stat card now shows
// counts that come from lib/platform-facts.ts (recounted in core/), and the
// headline says what the platform does instead of promising an outcome.
export default function EnterpriseHero() {
    const tHC = useTranslations("hcComponentsEnterpriselandingEnterpriseHero");
  return (
    <section className="enterprise-hero">
      <div className="enterprise-hero-container">
        <div className="enterprise-hero-content">
          <h1 className="enterprise-hero-title">
            {tHC("hcComponentsEnterpriselandingEnterpriseHero.s7")}
          </h1>
          <p className="enterprise-hero-subtitle">
            {tHC("hcComponentsEnterpriselandingEnterpriseHero.s6")}
          </p>
          <div className="enterprise-hero-cta">
            <a href="#contact-form" className="enterprise-cta-primary">
              {tHC("hcComponentsEnterpriselandingEnterpriseHero.s5")}
            </a>
            <p className="enterprise-cta-note">{tHC("hcComponentsEnterpriselandingEnterpriseHero.s4")}</p>
          </div>
        </div>

        <div className="enterprise-hero-visual">
          <div className="enterprise-hero-card">
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">{PLATFORM_FACTS.channels}</span>
              <span className="enterprise-hero-label">{tHC("hcComponentsEnterpriselandingEnterpriseHero.s3")}</span>
            </div>
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">{PLATFORM_FACTS.byokProviders}</span>
              <span className="enterprise-hero-label">{tHC("hcComponentsEnterpriselandingEnterpriseHero.s2")}</span>
            </div>
            <div className="enterprise-hero-stat">
              <span className="enterprise-hero-number">1</span>
              <span className="enterprise-hero-label">{tHC("hcComponentsEnterpriselandingEnterpriseHero.s1")}</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
