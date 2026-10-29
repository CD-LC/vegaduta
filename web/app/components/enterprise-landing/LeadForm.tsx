"use client";

import { useState } from "react";
import { ENTITY_FACTS } from "../../lib/platform-facts";
import { useTranslations } from "../../lib/i18n";

export default function LeadForm() {
    const tHC = useTranslations("hcComponentsEnterpriselandingLeadForm");
  const [formData, setFormData] = useState({
    company: "",
    contact: "",
    email: "",
    phone: "",
    useCase: "",
    timeline: "1-3 months",
  });

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState("");

  const handleChange = (
    e: React.ChangeEvent<
      HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
    >
  ) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  // Security review SEC-040 (2026-09-30): this form used to POST to
  // app/api/leads, an unauthenticated route that logged the name, email and
  // phone to stdout and then discarded the lead. There is no lead intake on
  // the self-hosted stack, so the form now opens the visitor's own mail client
  // with the details prefilled (same approach as the landing ContactBand) -
  // nothing is sent to this server or to analytics.
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);
    setError("");
    try {
      const subject = `Enterprise inquiry: ${formData.company}`;
      const body = [
        `Company: ${formData.company}`,
        `Contact: ${formData.contact}`,
        `Email: ${formData.email}`,
        `Phone: ${formData.phone}`,
        `Timeline: ${formData.timeline}`,
        "",
        formData.useCase,
      ].join("\n");
      window.location.href = `mailto:${ENTITY_FACTS.contactEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
      setSubmitted(true);
    } catch {
      setError(`Could not open your mail app. Please email ${ENTITY_FACTS.contactEmail}.`);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <section id="contact-form" className="enterprise-form-section">
      <div className="enterprise-form-container">
        <div className="enterprise-form-header">
          <h2>{tHC("hcComponentsEnterpriselandingLeadForm.s19")}</h2>
          <p>
            {tHC("hcComponentsEnterpriselandingLeadForm.s18")}
          </p>
        </div>

        {submitted && (
          <div className="enterprise-form-success" role="status">
            <div className="enterprise-success-icon">✓</div>
            <h3>{tHC("hcComponentsEnterpriselandingLeadForm.s17")}</h3>
            <p>{tHC("hcComponentsEnterpriselandingLeadForm.s16")}</p>
            <button
              type="button"
              className="enterprise-form-submit"
              onClick={() => setSubmitted(false)}
            >
              {tHC("hcComponentsEnterpriselandingLeadForm.s15")}
            </button>
          </div>
        )}

        {!submitted && (
          <form className="enterprise-form" onSubmit={handleSubmit}>
            <div className="enterprise-form-grid">
              <div className="enterprise-form-group">
                <label htmlFor="company">{tHC("hcComponentsEnterpriselandingLeadForm.s14")}</label>
                <input
                  id="company"
                  name="company"
                  type="text"
                  required
                  autoComplete="organization"
                  value={formData.company}
                  onChange={handleChange}
                  placeholder={tHC("hcComponentsEnterpriselandingLeadForm.s13")}
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="contact">{tHC("hcComponentsEnterpriselandingLeadForm.s12")}</label>
                <input
                  id="contact"
                  name="contact"
                  type="text"
                  required
                  autoComplete="name"
                  value={formData.contact}
                  onChange={handleChange}
                  placeholder={tHC("hcComponentsEnterpriselandingLeadForm.s11")}
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="email">{tHC("hcComponentsEnterpriselandingLeadForm.s10")}</label>
                <input
                  id="email"
                  name="email"
                  type="email"
                  required
                  autoComplete="email"
                  value={formData.email}
                  onChange={handleChange}
                  placeholder="your@company.com"
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="phone">{tHC("hcComponentsEnterpriselandingLeadForm.s9")}</label>
                <input
                  id="phone"
                  name="phone"
                  type="tel"
                  autoComplete="tel"
                  inputMode="tel"
                  value={formData.phone}
                  onChange={handleChange}
                  placeholder="+1 (555) 123-4567"
                />
              </div>

              <div className="enterprise-form-group enterprise-form-full">
                <label htmlFor="useCase">{tHC("hcComponentsEnterpriselandingLeadForm.s8")}</label>
                <textarea
                  id="useCase"
                  name="useCase"
                  required
                  value={formData.useCase}
                  onChange={handleChange}
                  placeholder={tHC("hcComponentsEnterpriselandingLeadForm.s7")}
                  rows={4}
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="timeline">{tHC("hcComponentsEnterpriselandingLeadForm.s6")}</label>
                <select
                  id="timeline"
                  name="timeline"
                  value={formData.timeline}
                  onChange={handleChange}
                >
                  <option value="urgent">{tHC("hcComponentsEnterpriselandingLeadForm.s5")}</option>
                  <option value="1-3 months">{tHC("hcComponentsEnterpriselandingLeadForm.s4")}</option>
                  <option value="3-6 months">{tHC("hcComponentsEnterpriselandingLeadForm.s3")}</option>
                  <option value="exploring">{tHC("hcComponentsEnterpriselandingLeadForm.s2")}</option>
                </select>
              </div>
            </div>

            {error && (
              <div className="enterprise-form-error" role="alert">
                {error}
              </div>
            )}

            <button
              type="submit"
              className="enterprise-form-submit"
              disabled={isSubmitting}
            >
              {isSubmitting ? "Submitting..." : "Submit"}
            </button>

            <p className="enterprise-form-privacy">
              {tHC("hcComponentsEnterpriselandingLeadForm.s1")}
            </p>
          </form>
        )}
      </div>
    </section>
  );
}
