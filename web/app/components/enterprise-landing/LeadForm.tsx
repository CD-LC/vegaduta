"use client";

import { useState } from "react";
import { ENTITY_FACTS } from "../../lib/platform-facts";

export default function LeadForm() {
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
          <h2>Get Started Today</h2>
          <p>
            Our team will review your requirements and reach out within 24 hours.
          </p>
        </div>

        {submitted && (
          <div className="enterprise-form-success" role="status">
            <div className="enterprise-success-icon">✓</div>
            <h3>Almost there</h3>
            <p>Your mail app should have opened with the details filled in - send that email and we&apos;ll get back to you.</p>
            <button
              type="button"
              className="enterprise-form-submit"
              onClick={() => setSubmitted(false)}
            >
              Send another message
            </button>
          </div>
        )}

        {!submitted && (
          <form className="enterprise-form" onSubmit={handleSubmit}>
            <div className="enterprise-form-grid">
              <div className="enterprise-form-group">
                <label htmlFor="company">Company Name *</label>
                <input
                  id="company"
                  name="company"
                  type="text"
                  required
                  autoComplete="organization"
                  value={formData.company}
                  onChange={handleChange}
                  placeholder="Your company"
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="contact">Your Name *</label>
                <input
                  id="contact"
                  name="contact"
                  type="text"
                  required
                  autoComplete="name"
                  value={formData.contact}
                  onChange={handleChange}
                  placeholder="Full name"
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="email">Work Email *</label>
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
                <label htmlFor="phone">Phone (Optional)</label>
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
                <label htmlFor="useCase">What&apos;s your use case? *</label>
                <textarea
                  id="useCase"
                  name="useCase"
                  required
                  value={formData.useCase}
                  onChange={handleChange}
                  placeholder="E.g., automate customer support, streamline internal workflows, build custom AI agent for sales..."
                  rows={4}
                />
              </div>

              <div className="enterprise-form-group">
                <label htmlFor="timeline">Implementation Timeline *</label>
                <select
                  id="timeline"
                  name="timeline"
                  value={formData.timeline}
                  onChange={handleChange}
                >
                  <option value="urgent">Urgent (1-2 weeks)</option>
                  <option value="1-3 months">Soon (1-3 months)</option>
                  <option value="3-6 months">Medium term (3-6 months)</option>
                  <option value="exploring">Still exploring</option>
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
              We respect your privacy. No spam, ever.
            </p>
          </form>
        )}
      </div>
    </section>
  );
}
