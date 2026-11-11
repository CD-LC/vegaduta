"use client";

import { useState, type ReactNode } from "react";
import { motion, AnimatePresence } from "motion/react";
import { ThumbsUp, ThumbsDown, MoreHorizontal, Star, Check, PencilLine } from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";

/**
 * Inline per-turn feedback control for the agent Playground - the frontend
 * half of the reinforcement-learning reward loop. Each completed assistant
 * turn gets a compact row: thumbs up / thumbs down, plus a "…" that expands to
 * a 1-5 star rating, an optional comment, a "suggest a better answer" box (the
 * EDIT signal), and resolved / unresolved buttons.
 *
 * Every signal is sent optimistically and is intentionally best-effort: the
 * api.sendTurnFeedback wrapper resolves to { ok } and never throws, so a
 * transient RL-endpoint failure can never crash the chat. When a send lands we
 * show a subtle "saved" tick; when it doesn't, the optimistic selection is
 * reverted and a quiet "not saved" hint shows instead of pretending it stuck.
 *
 * Signals match the REST contract: THUMBS_UP | THUMBS_DOWN | RATING | EDIT |
 * RESOLVED | UNRESOLVED, with optional rating (1-5), comment, editedAnswer.
 */
type Signal = "THUMBS_UP" | "THUMBS_DOWN" | "RATING" | "EDIT" | "RESOLVED" | "UNRESOLVED";

export default function TurnFeedback({
  agentId,
  sessionId,
  turnRef,
}: {
  agentId: string;
  sessionId: string | null;
  turnRef: string;
}) {
  const tHC = useTranslations("hcComponentsAgentsTurnFeedback");
  const [thumb, setThumb] = useState<null | "up" | "down">(null);
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(0);
  const [hoverRating, setHoverRating] = useState(0);
  const [comment, setComment] = useState("");
  const [edit, setEdit] = useState("");
  const [resolution, setResolution] = useState<null | "RESOLVED" | "UNRESOLVED">(null);
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);
  const [sending, setSending] = useState(false);

  // Returns whether the signal persisted - callers revert their optimistic
  // selection on false so the UI never claims applied state it doesn't have.
  // Never throws (see api.sendTurnFeedback).
  async function send(signal: Signal, extra: Record<string, unknown> = {}) {
    setSending(true);
    setFailed(false);
    try {
      const res = await api.sendTurnFeedback(agentId, {
        sessionId: sessionId || undefined,
        turnRef,
        signal,
        ...extra,
      });
      if (res?.ok) {
        setSaved(true);
        window.setTimeout(() => setSaved(false), 2200);
        return true;
      }
      setFailed(true);
      window.setTimeout(() => setFailed(false), 3000);
      return false;
    } finally {
      setSending(false);
    }
  }

  async function onThumb(next: "up" | "down") {
    const value = thumb === next ? null : next;
    setThumb(value);
    if (value && !(await send(value === "up" ? "THUMBS_UP" : "THUMBS_DOWN"))) setThumb(null);
  }

  async function onRate(next: number) {
    const prev = rating;
    setRating(next);
    if (!(await send("RATING", { rating: next, comment: comment.trim() || undefined }))) setRating(prev);
  }

  async function onResolution(next: "RESOLVED" | "UNRESOLVED") {
    const value = resolution === next ? null : next;
    setResolution(value);
    if (value && !(await send(value))) setResolution(null);
  }

  const stars = hoverRating || rating;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-1">
        <FeedbackIconButton
          label={tHC("s11")}
          pressed={thumb === "up"}
          activeClass="text-[color:var(--state-success)]"
          onClick={() => onThumb("up")}
        >
          <ThumbsUp size={13} aria-hidden="true" />
        </FeedbackIconButton>
        <FeedbackIconButton
          label={tHC("s10")}
          pressed={thumb === "down"}
          activeClass="text-destructive"
          onClick={() => onThumb("down")}
        >
          <ThumbsDown size={13} aria-hidden="true" />
        </FeedbackIconButton>
        <FeedbackIconButton
          label={tHC("s9")}
          pressed={open}
          activeClass="text-primary"
          onClick={() => setOpen((v) => !v)}
        >
          <MoreHorizontal size={14} aria-hidden="true" />
        </FeedbackIconButton>
        <AnimatePresence>
          {saved && (
            <motion.span
              initial={{ opacity: 0, x: -4 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.16 }}
              className="ms-1 inline-flex items-center gap-1 text-xs text-muted-foreground"
            >
              <Check size={11} style={{ color: "var(--state-success)" }} aria-hidden="true" /> Thanks
            </motion.span>
          )}
          {failed && (
            <motion.span
              initial={{ opacity: 0, x: -4 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.16 }}
              className="ms-1 text-xs text-muted-foreground"
            >
              {tHC("s8")}
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="mt-1 w-full max-w-[85%] space-y-3 rounded-lg border border-border bg-secondary/30 p-3">
              {/* Rating */}
              <div className="space-y-1.5">
                <div className="text-xs font-medium text-foreground">{tHC("s7")}</div>
                <div className="flex items-center gap-0.5" aria-label={tHC("s6")}>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      key={n}
                      type="button"
                      aria-pressed={rating === n}
                      aria-label={`${n} star${n === 1 ? "" : "s"}`}
                      className="rounded p-0.5 text-muted-foreground outline-none transition-transform hover:scale-110 focus-visible:ring-[3px] focus-visible:ring-ring/40"
                      onMouseEnter={() => setHoverRating(n)}
                      onMouseLeave={() => setHoverRating(0)}
                      onClick={() => onRate(n)}
                    >
                      <Star
                        size={17}
                        className={cn(n > stars && "text-muted-foreground")}
                        style={n <= stars ? { color: "var(--state-warning)", fill: "var(--state-warning)" } : undefined}
                        aria-hidden="true"
                      />
                    </button>
                  ))}
                </div>
              </div>

              {/* Comment */}
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-foreground" htmlFor={`fb-comment-${turnRef}`}>
                  Comment <span className="font-normal text-muted-foreground">(optional)</span>
                </label>
                <Textarea
                  id={`fb-comment-${turnRef}`}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder={tHC("s5")}
                  className="min-h-14 text-sm"
                />
              </div>

              {/* Suggest a better answer (EDIT) */}
              <div className="space-y-1.5">
                <label
                  className="flex items-center gap-1.5 text-xs font-medium text-foreground"
                  htmlFor={`fb-edit-${turnRef}`}
                >
                  <PencilLine size={12} aria-hidden="true" /> {tHC("s4")}{" "}
                  <span className="font-normal text-muted-foreground">(optional)</span>
                </label>
                <Textarea
                  id={`fb-edit-${turnRef}`}
                  value={edit}
                  onChange={(e) => setEdit(e.target.value)}
                  placeholder={tHC("s3")}
                  className="min-h-16 text-sm"
                />
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={!edit.trim() || sending}
                  onClick={() => send("EDIT", { editedAnswer: edit.trim(), comment: comment.trim() || undefined })}
                >
                  {tHC("s2")}
                </Button>
              </div>

              {/* Resolution */}
              <div className="flex items-center gap-2 border-t border-border pt-2.5">
                <span className="text-xs text-muted-foreground">{tHC("s1")}</span>
                <Button
                  type="button"
                  size="sm"
                  variant={resolution === "RESOLVED" ? "default" : "outline"}
                  onClick={() => onResolution("RESOLVED")}
                >
                  Resolved
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={resolution === "UNRESOLVED" ? "destructive" : "outline"}
                  onClick={() => onResolution("UNRESOLVED")}
                >
                  Not yet
                </Button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function FeedbackIconButton({
  label,
  pressed,
  activeClass,
  onClick,
  children,
}: {
  label: string;
  pressed: boolean;
  activeClass: string;
  onClick: () => void;
  children: ReactNode;
}) {
  // Uses the shared Button (variant="ghost" size="icon") rather than a raw
  // <button>: `size="icon"` carries the load-bearing `p-0` that neutralizes the
  // global `button {}` rule in globals.css (which forces px-3.5 padding + a
  // border + card bg on every plain button and otherwise clips these icons to
  // zero width — the "three empty boxes" bug). This is the same icon-button
  // pattern the rest of the console uses, so it renders consistently.
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      aria-label={label}
      aria-pressed={pressed}
      title={label}
      onClick={onClick}
      className={cn(
        "size-7 rounded-md text-muted-foreground transition-transform hover:text-foreground active:scale-90",
        pressed && cn("bg-accent", activeClass)
      )}
    >
      {children}
    </Button>
  );
}
