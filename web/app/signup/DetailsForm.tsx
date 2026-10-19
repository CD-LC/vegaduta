"use client";

// Signup account + organization details form (page.tsx).

import { type ChangeEvent, type FormEvent } from "react";
import Link from "next/link";
import { useTrail, animated } from "@react-spring/web";
import { useTranslations } from "../lib/i18n";
import SocialLoginRow from "../components/signup/SocialLoginRow";
import AIProcessingLoader from "../components/signup/AIProcessingLoader";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import BrandText from "../components/BrandText";
import { FieldCheck, PasswordSignal, ShakeError } from "./SignupWidgets";

export function DetailsForm({
  orgName,
  setOrgName,
  firstName,
  setFirstName,
  lastName,
  setLastName,
  adminEmail,
  setAdminEmail,
  adminPassword,
  setAdminPassword,
  confirmPassword,
  setConfirmPassword,
  agreedToTerms,
  setAgreedToTerms,
  newsOptIn,
  setNewsOptIn,
  busy,
  error,
  onSubmit,
}: {
  orgName: string;
  setOrgName: (value: string) => void;
  firstName: string;
  setFirstName: (value: string) => void;
  lastName: string;
  setLastName: (value: string) => void;
  adminEmail: string;
  setAdminEmail: (value: string) => void;
  adminPassword: string;
  setAdminPassword: (value: string) => void;
  confirmPassword: string;
  setConfirmPassword: (value: string) => void;
  agreedToTerms: boolean;
  setAgreedToTerms: (value: boolean) => void;
  newsOptIn: boolean;
  setNewsOptIn: (value: boolean) => void;
  busy: boolean;
  error: string | null;
  onSubmit: (e: FormEvent<HTMLFormElement>) => void;
}) {
  const t = useTranslations("signup");
  const emailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(adminEmail);
  // Same six fields, same validation, same autocomplete hints as before;
  // `half` puts first + last name side by side from sm up so the form is one
  // row shorter (the standard sign-up shape).
  const fields = [
    {
      key: "org",
      label: t("fieldWorkspace"),
      type: "text",
      autoComplete: "organization",
      value: orgName,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setOrgName(e.target.value),
      maxLength: 255,
      placeholder: t("placeholderWorkspace"),
      valid: orgName.trim().length > 0,
      half: false,
    },
    {
      key: "first",
      label: t("fieldFirstName"),
      type: "text",
      autoComplete: "given-name",
      value: firstName,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setFirstName(e.target.value),
      maxLength: 255,
      placeholder: t("placeholderFirstName"),
      valid: firstName.trim().length > 0,
      half: true,
    },
    {
      key: "last",
      label: t("fieldLastName"),
      type: "text",
      autoComplete: "family-name",
      value: lastName,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setLastName(e.target.value),
      maxLength: 255,
      placeholder: t("placeholderLastName"),
      valid: lastName.trim().length > 0,
      half: true,
    },
    {
      key: "email",
      label: t("fieldEmail"),
      type: "email",
      inputMode: "email" as const,
      autoComplete: "email",
      value: adminEmail,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setAdminEmail(e.target.value),
      maxLength: 254,
      placeholder: t("placeholderEmail"),
      valid: emailValid,
      half: false,
    },
    {
      key: "password",
      label: t("fieldPassword"),
      type: "password",
      autoComplete: "new-password",
      value: adminPassword,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setAdminPassword(e.target.value),
      minLength: 12,
      maxLength: 128,
      valid: adminPassword.length >= 12,
      half: false,
    },
    {
      key: "confirm",
      label: t("fieldConfirmPassword"),
      type: "password",
      autoComplete: "new-password",
      value: confirmPassword,
      onChange: (e: ChangeEvent<HTMLInputElement>) => setConfirmPassword(e.target.value),
      minLength: 12,
      maxLength: 128,
      valid: confirmPassword.length >= 12 && confirmPassword === adminPassword,
      half: false,
    },
  ];

  // Slide-only, no opacity - a stalled animation must never render an invisible
  // input, only one sitting 14px low.
  const trail = useTrail(fields.length, {
    from: { transform: "translateY(14px)" },
    to: { transform: "translateY(0px)" },
    config: { tension: 230, friction: 24 },
  });

  return (
    <div className="vd-auth__stack">
      <ShakeError message={error} />

      <form onSubmit={onSubmit} className="vd-auth__form">
        <div className="vd-auth__grid">
          {fields.map((field, i) => (
            <animated.div
              className={`vd-auth__field${field.half ? " vd-auth__field--half" : ""}`}
              key={field.key}
              style={trail[i]}
            >
              <Label htmlFor={`su-${field.key}`}>
                {field.label}
                <FieldCheck show={field.valid && field.value.length > 0} />
              </Label>
              <Input
                id={`su-${field.key}`}
                type={field.type}
                inputMode={field.inputMode}
                value={field.value}
                onChange={field.onChange}
                required
                maxLength={field.maxLength}
                minLength={field.minLength}
                placeholder={field.placeholder}
                autoComplete={field.autoComplete}
                autoFocus={i === 0}
              />
              {field.key === "password" && <PasswordSignal value={adminPassword} />}
            </animated.div>
          ))}
        </div>

        {/* Consent - required agreement gates submission (JS-checked below,
            not native `required`, so an unchecked attempt always surfaces the
            inline ShakeError message instead of a silent browser tooltip);
            the news opt-in is a plain, unrelated, default-off preference. */}
        <div className="vd-auth__consent">
          <label>
            <input type="checkbox" checked={agreedToTerms} onChange={(e) => setAgreedToTerms(e.target.checked)} />
            <span>
              {t("consentTermsPrefix")} <Link href="/terms">{t("consentTermsLink")}</Link> {t("consentTermsAnd")}{" "}
              <Link href="/privacy">{t("consentPrivacyLink")}</Link>
              {t("consentTermsSuffix")}
            </span>
          </label>
          <label className="vd-auth__consent--muted">
            <input type="checkbox" checked={newsOptIn} onChange={(e) => setNewsOptIn(e.target.checked)} />
            <span>
              <BrandText text={t("consentNewsLabel")} />
            </span>
          </label>
        </div>

        <Button size="lg" type="submit" className="w-full" disabled={busy}>
          {busy ? <AIProcessingLoader active={busy} /> : t("submit")}
        </Button>
      </form>

      {/* 2026-09-17: the intro step is skipped for ?email= / ?demo= arrivals,
          which used to hide the Google / GitHub / Microsoft row entirely
          (maintainer feedback: "why google, microsoft and git is missing in signup"). The
          row renders only the providers the realm actually enables. */}
      <SocialLoginRow />
    </div>
  );
}
