"use client";

// A text input that commits while the person types (debounced) AND on blur /
// Enter, so a click straight on "Build" never loses the last edit. The parent
// stays the source of truth: external changes are adopted whenever the field
// is not focused.

import { useEffect, useRef, useState, type ComponentProps } from "react";
import { Input } from "../components/ui/input";

type Props = Omit<ComponentProps<typeof Input>, "value" | "defaultValue" | "onChange" | "onBlur"> & {
  value: string;
  onCommit: (value: string) => void;
  delayMs?: number;
};

export default function CommitInput({ value, onCommit, delayMs = 400, onKeyDown, ...rest }: Props) {
  const [local, setLocal] = useState(value);
  const timer = useRef<number | null>(null);
  const focused = useRef(false);
  const sent = useRef(value);
  const latest = useRef(onCommit);
  latest.current = onCommit;
  const pending = useRef(value);

  useEffect(() => {
    if (!focused.current && value !== sent.current) {
      sent.current = value;
      pending.current = value;
      setLocal(value);
    }
  }, [value]);

  const flush = () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
    if (pending.current !== sent.current) {
      sent.current = pending.current;
      latest.current(pending.current);
    }
  };

  // Commit anything still pending if the field unmounts mid-edit.
  useEffect(() => () => flush(), []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Input
      {...rest}
      value={local}
      onFocus={() => {
        focused.current = true;
      }}
      onChange={(e) => {
        const v = e.target.value;
        setLocal(v);
        pending.current = v;
        if (timer.current !== null) window.clearTimeout(timer.current);
        timer.current = window.setTimeout(flush, delayMs);
      }}
      onBlur={() => {
        focused.current = false;
        flush();
      }}
      onKeyDown={(e) => {
        onKeyDown?.(e);
        if (e.key === "Enter" && !e.nativeEvent.isComposing) flush();
      }}
    />
  );
}
