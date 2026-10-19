"use client";

import { useEffect, useRef, useState } from "react";
import { Sparkles } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { edgeEnabled, edgeFallbackLabel } from "../lib/edge/capabilities";
import { routeTurn, resetEdgeSession } from "../lib/edge/tierRouter";
import { getEffectiveEdgeOptIn } from "./agents/IntelligenceSourceControl";
import { errorMessage } from "../lib/errors";

/**
 * "Ask AI" popover - sits next to any description/prompt field in the
 * console (agent system prompts, workflow/knowledge descriptions, node
 * config text, etc). Backed by POST /api/assist/draft (AssistController),
 * which uses whichever LLM provider the tenant already has active - no
 * separate configuration needed here.
 *
 * Deliberately never auto-applies anything: the person always sees the
 * suggestion and clicks "Use this" themselves, same "review before it lands
 * in the form" posture as the agent template gallery (see AgentsPanel).
 *
 * Rewritten (UI polish follow-up) to use the theme-aware .ask-ai-* classes
 * instead of hardcoded indigo hex values - previously this button/popover
 * stayed indigo no matter which accent palette a tenant chose in the theme
 * customizer, which read as a bug once every other control in the app
 * started following the theme.
 *
 * Edge (on-device) foundation pass, 2026-08-07: transparently prefers an
 * on-device draft over the hosted call above, mirroring AgentPlayground's
 * sendViaEdge - gated on the ASK_AI_EDGE_FLAG_KEY feature flag (admin
 * control, via the existing web-admin Feature Flags panel) AND the same
 * effective edge opt-in AgentPlayground/IntelligenceSourceControl already
 * use, so this never disagrees with what the rest of the console considers
 * "edge enabled." No picker is added here - unlike the full chat surface,
 * this is a small inline helper that should just quietly prefer edge when
 * available, the same transparent-preference posture CallPanel's Kokoro TTS
 * already has.
 *
 * @param fieldLabel plain-language name of the field, sent to the backend so
 *     the model knows what kind of text to produce (e.g. "Agent system
 *     prompt", "Workflow description").
 * @param currentValue the field's current contents, if any - passed through
 *     so the model can improve/extend rather than starting from scratch.
 * @param onApply(text) called with the generated text when the user clicks
 *     "Use this" - wire it to the same setter the textarea/input already uses.
 * @param placeholder example instruction text shown in the popover's input.
 */

/** Admin-tunable via the existing web-admin Feature Flags panel (no new
 * admin UI needed - PlatformController's generic /api/platform/feature-flags
 * CRUD already handles any key). rolloutPercentage lets an admin do a
 * gradual rollout; enabled=false (or no flag row at all) keeps every tenant
 * on the hosted-only path exactly as today (FeatureFlagService fails open to
 * "control" - see its own javadoc). */
export const ASK_AI_EDGE_FLAG_KEY = "edge.ask-ai";

/**
 * Every precondition for attempting an on-device Ask AI draft, combined:
 * the build flag, this tenant's edge.ask-ai feature-flag variant (the admin
 * control), and the effective edge opt-in (stored per-device choice, else
 * the edge-first default when a text model is genuinely ready - same helper
 * AgentPlayground/IntelligenceSourceControl already call). A pure-ish async
 * function (only side effect is the flag-evaluation network call) so it's
 * unit-testable without mounting the component. Never throws - a broken
 * flag check must fail closed (never attempt edge) rather than blocking the
 * existing hosted path, same posture FeatureFlagService itself documents.
 */
export async function shouldAttemptEdgeDraft(): Promise<boolean> {
  if (!edgeEnabled()) return false;
  try {
    const [{ variant }, optedIn] = await Promise.all([
      api.evaluateFeatureFlag(ASK_AI_EDGE_FLAG_KEY),
      getEffectiveEdgeOptIn(),
    ]);
    return variant === "treatment" && optedIn;
  } catch {
    return false;
  }
}

/** Mirrors AssistService.draft()'s exact system/user prompt shape (core/agents
 * .../assist/AssistService.java) so an on-device draft and a hosted draft are
 * asking the model the same question - only WHERE it runs differs. Pure and
 * synchronous so it's trivially unit-testable. */
export function buildAskAiEdgePrompt({
  fieldLabel,
  instruction,
  currentValue,
}: {
  fieldLabel: string;
  instruction: string;
  currentValue?: string | null;
}): { system: string; user: string } {
  const system =
    "You help an admin of an AI agent SaaS platform write clear, " +
    "effective text for one specific form field. You will be told the " +
    "field's purpose, the admin's rough instruction, and (maybe) what " +
    "they've already typed. Reply with ONLY the finished text for that " +
    "field - no preamble, no markdown code fences, no explanation, no " +
    "quotation marks around it. If a current draft is given, improve or " +
    "extend it rather than ignoring it. Keep length appropriate to the " +
    "field (a system prompt can be a paragraph or two; a short " +
    "description field should be one or two sentences).";
  const user =
    `Field: ${fieldLabel}\nInstruction: ${instruction}` +
    (currentValue && currentValue.trim() ? `\nCurrent draft:\n${currentValue}` : "");
  return { system, user };
}

interface AskAiHelperProps {
  fieldLabel: string;
  currentValue?: string | null;
  onApply: (text: string) => void;
  placeholder?: string;
}

export default function AskAiHelper({ fieldLabel, currentValue, onApply, placeholder }: AskAiHelperProps) {
  const t = useTranslations("askAiHelper");
  const [open, setOpen] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [suggestion, setSuggestion] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  // Edge-first fallback notice - same "never leave a fallback silent"
  // precedent as CallPanel.tsx's edgeFallbackNotice: null unless an
  // on-device attempt was actually made and declined for THIS generation.
  const [edgeFallbackNotice, setEdgeFallbackNotice] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);

  function close() {
    setOpen(false);
    setInstruction("");
    setSuggestion(null);
    setError(null);
    setEdgeFallbackNotice(null);
    triggerRef.current?.focus();
  }

  // Escape closes the popover and returns focus to the trigger, matching the
  // rest of the app's popovers/dialogs.
  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open]);

  async function handleGenerate() {
    if (!instruction.trim()) {
      setError(t("describeFirst"));
      return;
    }
    setBusy(true);
    setError(null);
    setEdgeFallbackNotice(null);
    try {
      if (await shouldAttemptEdgeDraft()) {
        // Stable per-field-label session key, reset before every attempt -
        // AssistService.draft() is explicitly "no session, no memory" (one
        // throwaway completion per click), so the on-device leg must be
        // equally stateless rather than accumulating conversation history
        // across repeated "Regenerate" clicks on the same field.
        const sessionKey = `ask-ai:${fieldLabel}`;
        resetEdgeSession(sessionKey);
        const { system, user } = buildAskAiEdgePrompt({
          fieldLabel,
          instruction,
          currentValue: currentValue || undefined,
        });
        const edgeResult = await routeTurn(user, { sessionKey, systemPrompt: system });
        if (edgeResult.handled === true) {
          setSuggestion(edgeResult.text.trim());
          setBusy(false);
          return;
        }
        if (edgeResult.reason !== "aborted") {
          setEdgeFallbackNotice(edgeFallbackLabel(edgeResult.reason));
        }
      }
      const res = await api.askAiDraft({
        fieldLabel,
        instruction,
        currentValue: currentValue || undefined,
      });
      setSuggestion(res.suggestion);
    } catch (err) {
      setError(errorMessage(err, t("generateError")));
    } finally {
      setBusy(false);
    }
  }

  function handleUse() {
    onApply(suggestion);
    close();
  }

  return (
    <span
      role="presentation"
      style={{ position: "relative", display: "inline-block" }}
      onClick={(e) => e.stopPropagation()}
    >
      <button
        type="button"
        ref={triggerRef}
        onClick={() => setOpen((v) => !v)}
        title={t("triggerTitle")}
        className={`ask-ai-trigger${open ? " ask-ai-trigger-active" : ""}`}
      >
        <Sparkles aria-hidden="true" size={12} /> {t("trigger")}
      </button>

      {open && (
        <div className="ask-ai-popover">
          <p className="muted" style={{ fontSize: "0.76rem", marginTop: 0, marginBottom: "0.5rem" }}>
            {t("describeFieldPrefix")} <strong>{fieldLabel}</strong>
            {currentValue ? t("improveExisting") : t("draftFromScratch")}
          </p>

          <textarea
            rows={3}
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
            placeholder={placeholder || t("placeholder")}
            aria-label={t("instructionAriaLabel")}
            style={{ width: "100%", fontSize: "0.82rem" }}
            autoFocus
          />

          {error && (
            <div className="error-box" style={{ fontSize: "0.76rem", marginTop: "0.4rem" }}>
              {error}
            </div>
          )}

          {suggestion && (
            <div className="ask-ai-suggestion-box">
              <div className="muted" style={{ fontSize: "0.7rem", marginBottom: "0.2rem" }}>
                {t("suggestion")}
              </div>
              <div style={{ whiteSpace: "pre-wrap", fontSize: "0.8rem" }}>{suggestion}</div>
            </div>
          )}

          {/* On-device was attempted (edge.ask-ai flag on + opted in) but
              declined for this generation - never leave that silent, same
              precedent as CallPanel.tsx's edgeFallbackNotice. Shown whether
              or not a hosted suggestion ended up landing above. */}
          {edgeFallbackNotice && (
            <div className="muted" style={{ fontSize: "0.7rem", marginTop: "0.4rem" }}>
              {t("edgeFallbackPrefix")} {edgeFallbackNotice}
            </div>
          )}

          <div className="row" style={{ marginTop: "0.6rem", justifyContent: "flex-end" }}>
            <button type="button" disabled={busy} onClick={close}>
              {t("cancel")}
            </button>
            {suggestion ? (
              <>
                <button type="button" disabled={busy} onClick={handleGenerate}>
                  {busy ? t("thinking") : t("regenerate")}
                </button>
                <button type="button" className="primary" onClick={handleUse}>
                  {t("useThis")}
                </button>
              </>
            ) : (
              <button type="button" className="primary" disabled={busy} onClick={handleGenerate}>
                {busy ? t("thinking") : t("generate")}
              </button>
            )}
          </div>
        </div>
      )}
    </span>
  );
}
