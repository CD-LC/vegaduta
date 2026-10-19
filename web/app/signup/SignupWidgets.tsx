"use client";

// Small presentational pieces of the signup flow (page.tsx): step indicator,
// shake-on-error message, field check mark and password strength signal.

import { useEffect, useRef } from "react";
import { useSpring, animated } from "@react-spring/web";
import { Check } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Alert } from "../components/ui/alert";

const STEP_LABEL_KEYS = ["stepAccount", "stepOrganization", "stepLaunch"];

export function StepIndicator({ active }: { active: number }) {
  const t = useTranslations("signup");
  return (
    <div className="vd-auth__steps flex items-center gap-2" aria-hidden="true">
      {STEP_LABEL_KEYS.map((labelKey, i) => {
        const n = i + 1;
        const state = n < active ? "done" : n === active ? "active" : "pending";
        return (
          <div className="flex items-center gap-2" key={labelKey}>
            <span
              className={`grid size-6 place-items-center rounded-full text-xs font-semibold ${
                state === "active"
                  ? "bg-primary text-primary-foreground"
                  : state === "done"
                    ? "bg-primary/20 text-primary"
                    : "bg-secondary text-muted-foreground"
              }`}
            >
              {state === "done" ? <Check className="size-3.5" aria-hidden="true" /> : n}
            </span>
            <span className={`text-xs ${state === "pending" ? "text-muted-foreground" : "text-foreground"}`}>
              {t(labelKey)}
            </span>
            {n < STEP_LABEL_KEYS.length && <span className="h-px w-5 bg-border" />}
          </div>
        );
      })}
    </div>
  );
}

// Error banner keeps its react-spring shake — a small "this needs attention"
// physical tell.
export function ShakeError({ message }: { message: string | null }) {
  const [style, api2] = useSpring(() => ({ x: 0 }));
  // The card's only error surface can sit well below the fold on a small
  // screen (submit button after six inputs, password meter, consent rows) -
  // scroll it into view and move focus there so setError() is actually seen.
  const wrapperRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!message) return;
    void api2.start({
      from: { x: -8 },
      to: async (next) => {
        await next({ x: 8 });
        await next({ x: -5 });
        await next({ x: 0 });
      },
      config: { tension: 520, friction: 14 },
    });
    wrapperRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    wrapperRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [message]);

  if (!message) return null;
  return (
    <animated.div ref={wrapperRef} tabIndex={-1} style={{ transform: style.x.to((x) => `translateX(${x}px)`) }}>
      <Alert variant="destructive">{message}</Alert>
    </animated.div>
  );
}

// Green check that pops in when a field passes the same validation handleSubmit
// enforces.
export function FieldCheck({ show }: { show: boolean }) {
  const style = useSpring({
    opacity: show ? 1 : 0,
    transform: show ? "scale(1)" : "scale(0.4)",
    config: { tension: 420, friction: 18 },
  });
  return (
    <animated.span className="text-primary" style={style} aria-hidden="true">
      <Check size={13} strokeWidth={3} aria-hidden="true" />
    </animated.span>
  );
}

// Real-time password strength — length 6+, 10+, mixed case+digit, a symbol.
const PASSWORD_STRENGTH_KEYS = [
  "passwordStrengthEmpty",
  "passwordStrengthFaint",
  "passwordStrengthStable",
  "passwordStrengthStrong",
  "passwordStrengthEncrypted",
];

export function PasswordSignal({ value }: { value: string }) {
  const t = useTranslations("signup");
  if (!value) return null;
  let score = 0;
  if (value.length >= 6) score++;
  if (value.length >= 10) score++;
  if (/[A-Z]/.test(value) && /[0-9]/.test(value)) score++;
  if (/[^A-Za-z0-9]/.test(value)) score++;
  return (
    <div className="mt-2 flex items-center gap-2">
      <div className="flex flex-1 gap-1">
        {[0, 1, 2, 3].map((i) => (
          <span key={i} className={`h-1 flex-1 rounded-full ${i < score ? "bg-primary" : "bg-secondary"}`} />
        ))}
      </div>
      <small className="font-mono text-[length:var(--fs-label)] text-muted-foreground">
        {t(PASSWORD_STRENGTH_KEYS[score])}
      </small>
    </div>
  );
}
