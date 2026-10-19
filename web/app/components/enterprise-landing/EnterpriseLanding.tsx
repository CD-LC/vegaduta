"use client";

import EnterpriseHero from "./EnterpriseHero";
import LeadForm from "./LeadForm";
import EnterpriseFaq from "./EnterpriseFaq";
import EnterpriseBenefits from "./EnterpriseBenefits";
import "./enterprise-landing.css";

export default function EnterpriseLanding() {
  return (
    <div className="enterprise-root">
      <EnterpriseHero />
      <EnterpriseBenefits />
      <LeadForm />
      <EnterpriseFaq />
    </div>
  );
}
