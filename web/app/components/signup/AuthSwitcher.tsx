"use client";

import { motion } from "motion/react";
import { useTranslations } from "../../lib/i18n";

// Sign In / Sign Up toggle — rebuilt on Tailwind + the shadcn token layer to
// match the ShadCN signup rebuild. A single shared layoutId'd pill still
// slides between whichever tab is active (Motion shared-layout), now styled
// with --secondary/--primary tokens instead of the old .auth-switcher-* CSS.
// `mode` is a plain string (not a "signin" | "signup" union) on purpose:
// the owning page (signup/page.tsx) keeps it in a useState("signup") whose
// inferred type is string, so a union here would push a TS2322 into that
// file — which belongs to a different migration package — without changing
// anything real. The runtime contract is still only ever those two values.
export default function AuthSwitcher({ mode, onChange }: { mode: string; onChange: (mode: string) => void }) {
  const t = useTranslations("signup");
  return (
    <div
      aria-label={t("switcherAriaLabel")}
      className="relative grid grid-cols-2 gap-1 rounded-full border border-border bg-secondary/40 p-1"
    >
      {[
        { key: "signin", label: t("switcherSignIn") },
        { key: "signup", label: t("switcherSignUp") },
      ].map((tab) => (
        <button
          key={tab.key}
          type="button"
          aria-current={mode === tab.key ? "page" : undefined}
          onClick={() => onChange(tab.key)}
          className={`relative z-10 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            mode === tab.key ? "text-primary-foreground" : "text-muted-foreground hover:text-foreground"
          }`}
        >
          {mode === tab.key && (
            <motion.span
              layoutId="auth-switcher-pill"
              className="absolute inset-0 -z-10 rounded-full bg-primary"
              transition={{ type: "spring", stiffness: 380, damping: 32 }}
            />
          )}
          {tab.label}
        </button>
      ))}
    </div>
  );
}
