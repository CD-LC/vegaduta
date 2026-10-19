"use client";

import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";

// FAQ — six real owner questions, answered from the fact base (14 BYOK
// providers, official WhatsApp routes on Premium, 29 templates + 248
// playbooks, Academy 127 lessons / 12 labs, budget caps + master switch).
// Native <details>/<summary> — keyboard-accessible and readable with JS off.
export default function HireFaq() {
  const t = useTranslations("hire");
  const { variant } = useGeo();
  const isIndia = variant === "india";
  return (
    <section id="faq">
      <div className="wrap" style={{ maxWidth: 800 }}>
        <p className="doc-eyebrow">{t("faq_eyebrow")}</p>
        <h2 className="serif" style={{ marginBottom: 24 }}>
          {t("faq_title")}
        </h2>
        {[1, 2, 3, 4, 5, 6].map((n) => (
          <details key={n}>
            <summary>{t(`faq_q${n}`)}</summary>
            <p>{t(n === 6 && isIndia ? "faq_a6IN" : `faq_a${n}`)}</p>
          </details>
        ))}
      </div>
    </section>
  );
}
