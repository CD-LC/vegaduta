"use client";

import { useEffect, useRef } from "react";
import { Delete } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { PIN_MAX, sanitizePin } from "../../lib/localAuthPin";
import { Input } from "../ui/input";
import { Label } from "../ui/label";

interface Props {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  /** Show the on-screen digit pad under the field (the sign-in PIN pad). */
  keypad?: boolean;
  /** Focus the field on mount, on devices with a fine pointer only (a phone keyboard would cover the form). */
  focusOnMount?: boolean;
  describedBy?: string;
  testId?: string;
  /** Marks the field as failing validation for assistive tech. */
  invalid?: boolean;
}

const DIGITS = ["1", "2", "3", "4", "5", "6", "7", "8", "9"];

/**
 * A PIN entry. type=password so the digits show as dots, inputMode=numeric for phone keyboards,
 * autocomplete=off so a password manager does not offer to save it, and a real text input so the
 * keyboard works exactly as for any field; the optional on-screen pad is for touch and mirrors it.
 */
export default function PinField({ id, label, value, onChange, disabled, keypad, focusOnMount, describedBy, testId, invalid }: Props) {
  const t = useTranslations("signin");
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!focusOnMount) return;
    try {
      if (window.matchMedia?.("(pointer: fine)").matches) ref.current?.focus();
    } catch {
      // matchMedia can be missing; the field simply is not focused
    }
  }, [focusOnMount]);

  function press(digit: string) {
    onChange(sanitizePin(value + digit));
    ref.current?.focus();
  }

  return (
    <div className="vd-auth__field">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        ref={ref}
        type="password"
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete="off"
        maxLength={PIN_MAX}
        spellCheck={false}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        value={value}
        onChange={(e) => onChange(sanitizePin(e.target.value))}
        data-testid={testId}
      />
      {keypad && (
        <div className="grid grid-cols-3 gap-2" role="group" aria-label={t("local.pin.keypad")}>
          {DIGITS.map((d) => (
            <KeypadButton key={d} onPress={() => press(d)} disabled={disabled} label={d} testId={`pin-key-${d}`}>
              {d}
            </KeypadButton>
          ))}
          <span aria-hidden="true" />
          <KeypadButton onPress={() => press("0")} disabled={disabled} label="0" testId="pin-key-0">
            0
          </KeypadButton>
          <KeypadButton
            onPress={() => {
              onChange(value.slice(0, -1));
              ref.current?.focus();
            }}
            disabled={disabled || !value}
            label={t("local.pin.delete")}
            testId="pin-key-delete"
          >
            <Delete className="size-4" aria-hidden="true" />
          </KeypadButton>
        </div>
      )}
    </div>
  );
}

function KeypadButton({
  onPress,
  disabled,
  label,
  testId,
  children,
}: {
  onPress: () => void;
  disabled?: boolean;
  label: string;
  testId: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      // Keep focus in the field: the pad is a pointer shortcut, the field is the keyboard path.
      tabIndex={-1}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onPress}
      disabled={disabled}
      aria-label={label}
      data-testid={testId}
      className="inline-flex h-11 items-center justify-center rounded-lg border border-border bg-transparent text-base font-medium outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
    >
      {children}
    </button>
  );
}
