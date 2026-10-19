"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";

const STAGE_KEYS = ["processingStage1", "processingStage2", "processingStage3", "processingStage4"];

// Cosmetic label sequence shown inside the submit button only while the
// real POST /api/signup request (the `active` prop mirrors handleSubmit's
// `busy` state) is actually in flight - not a fixed fake-progress timer
// that could reach a later stage, or "done," before the real request
// resolves. If the request is slow, the stages just loop; if it's fast,
// whichever stage happens to be showing when `active` flips back to false
// is immediately replaced by the button's normal label - there's no
// separate "done" stage to fake, since the real outcome (the payment
// screen, or an error message) is what tells the person it finished.
export default function AIProcessingLoader({ active }: { active: boolean }) {
  const t = useTranslations("signup");
  const [stage, setStage] = useState(0);

  useEffect(() => {
    if (!active) {
      setStage(0);
      return undefined;
    }
    const id = setInterval(() => setStage((s) => (s + 1) % STAGE_KEYS.length), 900);
    return () => clearInterval(id);
  }, [active]);

  if (!active) return null;
  return <span className="ai-processing-loader">{t(STAGE_KEYS[stage])}</span>;
}
