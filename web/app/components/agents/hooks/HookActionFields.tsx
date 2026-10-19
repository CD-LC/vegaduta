// Action-specific fields of the hook editor: what to say on block/approval, the log label, the context note,
// rate limits, redaction patterns and the webhook target. Split out of HookEditor; same ids and test ids.

import { Input } from "../../ui/input";
import { Select } from "../../ui/select";
import { Textarea } from "../../ui/textarea";
import { BUILTIN_PATTERNS, type DraftErrors, type HookDraft, type Translate } from "../agentHooks";
import FieldError from "./FieldError";

export default function HookActionFields({
  t,
  draft,
  errors,
  webhookHosts,
  set,
}: {
  t: Translate;
  draft: HookDraft;
  errors: DraftErrors;
  webhookHosts: string[];
  set: (patch: Partial<HookDraft>) => void;
}) {
  return (
    <div className="mt-3 space-y-3">
      {(draft.action === "block" || draft.action === "require_approval") && (
        <div>
          <label htmlFor="agent-hook-reason" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldReason")}
          </label>
          <Input
            id="agent-hook-reason"
            data-testid="agent-hook-reason"
            value={draft.reason}
            placeholder={t("fieldReasonPlaceholder")}
            aria-describedby="agent-hook-reason-error"
            onChange={(e) => set({ reason: e.target.value })}
          />
          <FieldError t={t} error={errors.reason} id="agent-hook-reason-error" />
        </div>
      )}

      {draft.action === "log" && (
        <div>
          <label htmlFor="agent-hook-label" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldLabel")}
          </label>
          <Input
            id="agent-hook-label"
            value={draft.label}
            placeholder={t("fieldLabelPlaceholder")}
            aria-describedby="agent-hook-label-error"
            onChange={(e) => set({ label: e.target.value })}
          />
          <FieldError t={t} error={errors.label} id="agent-hook-label-error" />
        </div>
      )}

      {draft.action === "append_context" && (
        <div>
          <label htmlFor="agent-hook-note" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldNote")}
          </label>
          <Textarea
            id="agent-hook-note"
            data-testid="agent-hook-note"
            value={draft.note}
            rows={2}
            aria-describedby="agent-hook-note-error"
            onChange={(e) => set({ note: e.target.value })}
          />
          <FieldError t={t} error={errors.note} id="agent-hook-note-error" />
        </div>
      )}

      {draft.action === "limit" && (
        <div>
          <div className="grid grid-cols-[minmax(0,1fr)] gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))]">
            {draft.point !== "BEFORE_MODEL" && (
              <div>
                <label htmlFor="agent-hook-max-run" className="mb-1 block text-xs text-muted-foreground">
                  {t("fieldMaxPerRun")}
                </label>
                <Input
                  id="agent-hook-max-run"
                  data-testid="agent-hook-max-run"
                  inputMode="numeric"
                  value={draft.maxPerRun}
                  aria-describedby="agent-hook-limit-error"
                  onChange={(e) => set({ maxPerRun: e.target.value })}
                />
              </div>
            )}
            <div>
              <label htmlFor="agent-hook-max-minute" className="mb-1 block text-xs text-muted-foreground">
                {t("fieldMaxPerMinute")}
              </label>
              <Input
                id="agent-hook-max-minute"
                data-testid="agent-hook-max-minute"
                inputMode="numeric"
                value={draft.maxPerMinute}
                aria-describedby="agent-hook-limit-error"
                onChange={(e) => set({ maxPerMinute: e.target.value })}
              />
            </div>
          </div>
          <FieldError t={t} error={errors.limit} id="agent-hook-limit-error" />
          <div className="mt-3">
            <label htmlFor="agent-hook-message" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldMessage")}
            </label>
            <Input
              id="agent-hook-message"
              value={draft.message}
              aria-describedby="agent-hook-message-error"
              onChange={(e) => set({ message: e.target.value })}
            />
            <FieldError t={t} error={errors.message} id="agent-hook-message-error" />
          </div>
        </div>
      )}

      {draft.action === "redact" && (
        <div>
          <fieldset>
            <legend className="mb-1 text-xs text-muted-foreground">{t("fieldPatterns")}</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              {BUILTIN_PATTERNS.map((p) => (
                <label key={p} className="flex items-center gap-1.5 text-xs text-foreground">
                  <input
                    type="checkbox"
                    checked={draft.patterns.includes(p)}
                    onChange={(e) =>
                      set({
                        patterns: e.target.checked ? [...draft.patterns, p] : draft.patterns.filter((x) => x !== p),
                      })
                    }
                  />
                  {t(`patternLabel.${p}`)}
                </label>
              ))}
            </div>
          </fieldset>
          <FieldError t={t} error={errors.patterns} id="agent-hook-patterns-error" />
          <div className="mt-3">
            <label htmlFor="agent-hook-regex" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldRegex")}
            </label>
            <Textarea
              id="agent-hook-regex"
              data-testid="agent-hook-regex"
              value={draft.regex}
              rows={2}
              className="font-mono"
              aria-describedby="agent-hook-regex-error"
              onChange={(e) => set({ regex: e.target.value })}
            />
            <FieldError t={t} error={errors.regex} id="agent-hook-regex-error" />
          </div>
          <div className="mt-3">
            <label htmlFor="agent-hook-replacement" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldReplacement")}
            </label>
            <Input
              id="agent-hook-replacement"
              value={draft.replacement}
              placeholder={t("fieldReplacementPlaceholder")}
              aria-describedby="agent-hook-replacement-error"
              onChange={(e) => set({ replacement: e.target.value })}
            />
            <FieldError t={t} error={errors.replacement} id="agent-hook-replacement-error" />
          </div>
        </div>
      )}

      {draft.action === "webhook" && (
        <div>
          <label htmlFor="agent-hook-url" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldUrl")}
          </label>
          <Input
            id="agent-hook-url"
            data-testid="agent-hook-url"
            value={draft.url}
            placeholder="https://hooks.example.com/check"
            autoComplete="off"
            aria-describedby="agent-hook-url-error"
            onChange={(e) => set({ url: e.target.value })}
          />
          <FieldError t={t} error={errors.url} id="agent-hook-url-error" />
          <p className="mt-1 text-xs text-muted-foreground" data-testid="agent-hook-hosts">
            {webhookHosts.length > 0 ? t("webhookHosts", { hosts: webhookHosts.join(", ") }) : t("webhookHostsNone")}
          </p>
          <label className="mt-2 flex items-center gap-2 text-xs text-foreground">
            <input
              type="checkbox"
              checked={draft.includeData}
              onChange={(e) => set({ includeData: e.target.checked })}
            />
            {t("fieldIncludeData")}
          </label>
          {draft.point !== "ON_ERROR" && draft.point !== "ON_RUN_END" && (
            <div className="mt-3 grid grid-cols-[minmax(0,1fr)] gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))]">
              <div>
                <label htmlFor="agent-hook-failmode" className="mb-1 block text-xs text-muted-foreground">
                  {t("fieldFailMode")}
                </label>
                <Select
                  id="agent-hook-failmode"
                  value={draft.failMode}
                  onValueChange={(v) => set({ failMode: v as HookDraft["failMode"] })}
                  options={[
                    { value: "", label: t("failModeDefault") },
                    { value: "closed", label: t("failModeLabel.closed") },
                    { value: "open", label: t("failModeLabel.open") },
                  ]}
                />
              </div>
              <div>
                <label htmlFor="agent-hook-timeout" className="mb-1 block text-xs text-muted-foreground">
                  {t("fieldTimeout")}
                </label>
                <Input
                  id="agent-hook-timeout"
                  inputMode="numeric"
                  value={draft.timeoutMs}
                  placeholder="2000"
                  aria-describedby="agent-hook-timeout-error"
                  onChange={(e) => set({ timeoutMs: e.target.value })}
                />
                <FieldError t={t} error={errors.timeoutMs} id="agent-hook-timeout-error" />
              </div>
            </div>
          )}
          <p className="mt-2 text-xs text-muted-foreground">{t("webhookNote")}</p>
        </div>
      )}
    </div>
  );
}
