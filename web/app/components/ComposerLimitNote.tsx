"use client";

import { useTranslations } from "../lib/i18n";
import { CHAT_COUNTER_THRESHOLD, MAX_CHAT_MESSAGE_CHARS } from "../lib/chatLimits";

export const COMPOSER_LIMIT_NOTE_ID = "chat-composer-limit";

/** True once a message is long enough to need the running count. */
export function showsComposerCounter(length: number): boolean {
  return length >= CHAT_COUNTER_THRESHOLD;
}

/** True when the server would answer 400 "message is too long". */
export function isOverComposerLimit(length: number): boolean {
  return length > MAX_CHAT_MESSAGE_CHARS;
}

/** aria props for the composer input: invalid above the cap, described by the count once it is shown. */
export function composerLimitProps(length: number) {
  return {
    "aria-invalid": isOverComposerLimit(length) || undefined,
    "aria-describedby": showsComposerCounter(length) ? COMPOSER_LIMIT_NOTE_ID : undefined,
  };
}

/** Running character count under the chat composer; an alert once the message is over the server cap. */
export default function ComposerLimitNote({ length }: { length: number }) {
  const t = useTranslations("chatPanel");
  if (!showsComposerCounter(length)) return null;
  const over = isOverComposerLimit(length);
  return (
    <p
      id={COMPOSER_LIMIT_NOTE_ID}
      role={over ? "alert" : undefined}
      className={`mt-1 text-xs tabular-nums ${over ? "text-destructive" : "text-muted-foreground"}`}
    >
      {t(over ? "messageTooLong" : "messageCounter", { count: length, max: MAX_CHAT_MESSAGE_CHARS })}
    </p>
  );
}
