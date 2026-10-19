"use client";

import { useTranslations } from "../../lib/i18n";

// The honest empty testimonial slot (digest: "reserved for our first 100 real
// businesses — with their real names, or nothing at all"). Rendered inside
// the contract section's flow, directly after the contract document.
export default function ReservedTestimonials() {
  const t = useTranslations("hire");
  return (
    <div className="reserved">
      <span className="tw">{t("reserved_label")}</span>
      <p>{t("reserved_p")}</p>
    </div>
  );
}
