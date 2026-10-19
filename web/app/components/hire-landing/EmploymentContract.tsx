"use client";

import { useTranslations } from "../../lib/i18n";
import ReservedTestimonials from "./ReservedTestimonials";

// Document 5 of 5 — the employment contract. Governance surfaced as contract
// clauses (digest: "governance you can SEE"), all claims from the fact base:
// tracing + audit log, per-user daily budget caps (default ≈15¢, USD), the
// approval queue + guardrails, tenant isolation + encryption at rest + SSO,
// one-click removal, and the 3 ENFORCED autonomy tiers (Annex A).
export default function EmploymentContract() {
  const t = useTranslations("hire");
  const clauses = ["clause1", "clause2", "clause3", "clause4", "clause5"];
  return (
    <section id="contract">
      <div className="wrap">
        <p className="doc-eyebrow">{t("contract_eyebrow")}</p>
        <h2 className="serif">{t("contract_title")}</h2>
        <p className="lede">{t("contract_lede")}</p>
        <div className="doc contract" style={{ marginTop: 34 }}>
          <div className="doc-inner">
            <div className="letterhead">
              <b>{t("contract_letterhead")}</b>
              <span>{t("contract_letterheadSub")}</span>
            </div>
            {clauses.map((c) => (
              <div className="clause" key={c}>
                <span className="cn">{t(c + "_n")}</span>
                <div>
                  <b>{t(c + "_t")}</b>
                  <p>{t(c + "_p")}</p>
                </div>
              </div>
            ))}
            <div className="clause">
              <span className="cn">{t("annex_n")}</span>
              <div>
                <b>{t("annex_t")}</b>
                <p>
                  {t("annex_p_a")}
                  <b>{t("annex_p_b")}</b>
                  {t("annex_p_c")}
                  <b>{t("annex_p_d")}</b>
                  {t("annex_p_e")}
                  <b>{t("annex_p_f")}</b>
                  {t("annex_p_g")}
                </p>
              </div>
            </div>
          </div>
        </div>
        {/* Mockup places the reserved-testimonials slot inside this section. */}
        <ReservedTestimonials />
      </div>
    </section>
  );
}
