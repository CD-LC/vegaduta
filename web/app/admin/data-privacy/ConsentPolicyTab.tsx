"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import {
  LANGUAGE_TAG_PATTERN,
  MAX_NOTICE_CHARS,
  PURPOSE_PATTERN,
  privacyApi,
  type ConsentPolicyDto,
} from "../../lib/api/privacy";
import { useTranslations } from "../../lib/i18n";
import { Section } from "../../components/ax/page-kit";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Skeleton } from "../../components/ui/skeleton";
import { Switch } from "../../components/ui/switch";
import { Textarea } from "../../components/ui/textarea";
import { LoadError, errText, fmtTime } from "./shared";

type AgentOption = { id: string; name: string };
type LangRow = { lang: string; text: string };

const toRows = (m: Record<string, string> | undefined): LangRow[] =>
  Object.entries(m ?? {}).map(([lang, text]) => ({ lang, text }));
const fromRows = (rows: LangRow[]): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const r of rows) {
    const lang = r.lang.trim().toLowerCase();
    const text = r.text.trim();
    if (lang && text) out[lang] = text;
  }
  return out;
};
const toCsv = (l: string[] | undefined) => (l ?? []).join(", ");
const fromCsv = (s: string) =>
  s
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);

/** Mirrors ConsentPolicyService.put validation so the form fails before the round trip. */
export function validateConsent(f: {
  enabled: boolean;
  purpose: string;
  defaultLanguage: string;
  notices: LangRow[];
  instructions: LangRow[];
}): string | null {
  const purpose = f.purpose.trim().toLowerCase();
  const def = f.defaultLanguage.trim().toLowerCase();
  if (!PURPOSE_PATTERN.test(purpose)) return "errPurpose";
  if (!LANGUAGE_TAG_PATTERN.test(def)) return "errDefaultLanguage";
  for (const r of [...f.notices, ...f.instructions]) {
    if (r.lang.trim() && !LANGUAGE_TAG_PATTERN.test(r.lang.trim().toLowerCase())) return "errLanguage";
  }
  const notices = fromRows(f.notices);
  if (Object.values(notices).some((x) => x.length > MAX_NOTICE_CHARS)) return "errNoticeLength";
  if (f.enabled && !notices[def]) return "errNoticeMissing";
  return null;
}

function LangEditor({
  idPrefix,
  rows,
  onChange,
  addLabel,
  langLabel,
  textLabel,
  removeLabel,
}: {
  idPrefix: string;
  rows: LangRow[];
  onChange: (r: LangRow[]) => void;
  addLabel: string;
  langLabel: string;
  textLabel: string;
  removeLabel: string;
}) {
  return (
    <div className="space-y-3">
      {rows.map((row, i) => (
        <div key={i} className="grid gap-2 sm:grid-cols-[8rem_1fr_auto] sm:items-start">
          <Input
            aria-label={`${langLabel} ${i + 1}`}
            value={row.lang}
            placeholder="en"
            onChange={(e) => onChange(rows.map((r, j) => (j === i ? { ...r, lang: e.target.value } : r)))}
          />
          <Textarea
            aria-label={`${textLabel} ${i + 1}`}
            id={`${idPrefix}-${i}`}
            rows={3}
            value={row.text}
            onChange={(e) => onChange(rows.map((r, j) => (j === i ? { ...r, text: e.target.value } : r)))}
          />
          <Button type="button" variant="ghost" size="sm" onClick={() => onChange(rows.filter((_, j) => j !== i))}>
            {removeLabel}
          </Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => onChange([...rows, { lang: "", text: "" }])}>
        {addLabel}
      </Button>
    </div>
  );
}

export default function ConsentPolicyTab() {
  const tHC = useTranslations("hcAdminDataprivacyConsentPolicyTab");
  const t = useTranslations("privacySso");
  const [agents, setAgents] = useState<AgentOption[] | null>(null);
  const [agentsError, setAgentsError] = useState<string | null>(null);
  const [agentId, setAgentId] = useState("");
  const [policy, setPolicy] = useState<ConsentPolicyDto | null>(null);
  const [loadingPolicy, setLoadingPolicy] = useState(false);
  const [policyError, setPolicyError] = useState<string | null>(null);

  const [enabled, setEnabled] = useState(false);
  const [purpose, setPurpose] = useState("support");
  const [defaultLanguage, setDefaultLanguage] = useState("en");
  const [notices, setNotices] = useState<LangRow[]>([]);
  const [instructions, setInstructions] = useState<LangRow[]>([]);
  const [accept, setAccept] = useState("");
  const [decline, setDecline] = useState("");
  const [withdraw, setWithdraw] = useState("");
  const [eraseOnWithdrawal, setEraseOnWithdrawal] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const loadAgents = useCallback(() => {
    setAgentsError(null);
    setAgents(null);
    api
      .listAgents()
      .then((list: any[]) => setAgents(list.map((a) => ({ id: a.id, name: a.name ?? a.id }))))
      .catch((e: unknown) => setAgentsError(errText(e)));
  }, []);
  useEffect(loadAgents, [loadAgents]);

  const apply = useCallback((p: ConsentPolicyDto | null) => {
    setPolicy(p);
    setEnabled(p?.enabled ?? false);
    setPurpose(p?.purpose ?? "support");
    setDefaultLanguage(p?.defaultLanguage ?? "en");
    setNotices(toRows(p?.notices));
    setInstructions(toRows(p?.instructions));
    setAccept(toCsv(p?.keywords?.accept));
    setDecline(toCsv(p?.keywords?.decline));
    setWithdraw(toCsv(p?.keywords?.withdraw));
    setEraseOnWithdrawal(p?.eraseOnWithdrawal ?? false);
  }, []);

  const loadPolicy = useCallback(
    (id: string) => {
      if (!id) {
        apply(null);
        return;
      }
      setLoadingPolicy(true);
      setPolicyError(null);
      setSaved(false);
      setFormError(null);
      privacyApi
        .getConsentPolicy(id)
        .then(apply)
        .catch((e: unknown) => setPolicyError(errText(e)))
        .finally(() => setLoadingPolicy(false));
    },
    [apply]
  );

  const onSave = async () => {
    setSaved(false);
    const problem = validateConsent({ enabled, purpose, defaultLanguage, notices, instructions });
    if (problem) {
      setFormError(t(problem, { max: MAX_NOTICE_CHARS }));
      return;
    }
    setFormError(null);
    setSaving(true);
    try {
      const keywords: Record<string, string[]> = {};
      for (const [k, v] of [
        ["accept", accept],
        ["decline", decline],
        ["withdraw", withdraw],
      ] as const) {
        const list = fromCsv(v);
        if (list.length) keywords[k] = list;
      }
      const result = await privacyApi.putConsentPolicy(agentId, {
        enabled,
        purpose: purpose.trim().toLowerCase(),
        defaultLanguage: defaultLanguage.trim().toLowerCase(),
        notices: fromRows(notices),
        instructions: fromRows(instructions),
        keywords,
        eraseOnWithdrawal,
      });
      apply(result);
      setSaved(true);
    } catch (e) {
      setFormError(errText(e));
    } finally {
      setSaving(false);
    }
  };

  if (agentsError) return <LoadError message={agentsError} onRetry={loadAgents} />;

  return (
    <Section title={t("consentTitle")} description={t("consentIntro")}>
      {agents === null ? (
        <Skeleton className="h-10 w-full max-w-md" aria-label={t("loading")} />
      ) : agents.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("noAgents")}</p>
      ) : (
        <div className="grid max-w-md gap-1.5">
          <Label htmlFor="consent-agent">{t("agent")}</Label>
          <Select
            id="consent-agent"
            value={agentId}
            onValueChange={(v) => {
              setAgentId(v);
              loadPolicy(v);
            }}
            options={[{ value: "", label: t("chooseAgent") }, ...agents.map((a) => ({ value: a.id, label: a.name }))]}
          />
        </div>
      )}

      {agentId && loadingPolicy && <Skeleton className="h-48 w-full" aria-label={t("loading")} />}
      {agentId && policyError && <LoadError message={policyError} onRetry={() => loadPolicy(agentId)} />}

      {agentId && !loadingPolicy && !policyError && (
        <form
          className="space-y-5"
          onSubmit={(e) => {
            e.preventDefault();
            void onSave();
          }}
        >
          <p className="text-sm text-muted-foreground">
            {policy
              ? t("policyMeta", {
                  version: policy.noticeVersion,
                  by: policy.updatedBy ?? "-",
                  at: fmtTime(policy.updatedAt),
                })
              : t("policyNone")}
          </p>

          <div className="flex items-center gap-3">
            <Switch
              id="consent-enabled"
              checked={enabled}
              onCheckedChange={setEnabled}
              aria-label={t("consentEnabled")}
            />
            <Label htmlFor="consent-enabled">{t("consentEnabled")}</Label>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="consent-purpose">{t("purpose")}</Label>
              <Input id="consent-purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)} />
              <p className="text-xs text-muted-foreground">{t("purposeHint")}</p>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="consent-lang">{t("defaultLanguage")}</Label>
              <Input id="consent-lang" value={defaultLanguage} onChange={(e) => setDefaultLanguage(e.target.value)} />
              <p className="text-xs text-muted-foreground">{t("languageHint")}</p>
            </div>
          </div>

          <div className="space-y-2">
            <h3 className="text-sm font-medium">{t("notices")}</h3>
            <p className="text-xs text-muted-foreground">{t("noticesHint", { max: MAX_NOTICE_CHARS })}</p>
            <LangEditor
              idPrefix="notice"
              rows={notices}
              onChange={setNotices}
              addLabel={t("addNotice")}
              langLabel={t("languageCode")}
              textLabel={t("noticeText")}
              removeLabel={t("remove")}
            />
          </div>

          <div className="space-y-2">
            <h3 className="text-sm font-medium">{t("instructions")}</h3>
            <p className="text-xs text-muted-foreground">{t("instructionsHint")}</p>
            <LangEditor
              idPrefix="instr"
              rows={instructions}
              onChange={setInstructions}
              addLabel={t("addInstructions")}
              langLabel={t("languageCode")}
              textLabel={t("instructionsText")}
              removeLabel={t("remove")}
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <div className="grid gap-1.5">
              <Label htmlFor="kw-accept">{t("kwAccept")}</Label>
              <Input
                id="kw-accept"
                value={accept}
                onChange={(e) => setAccept(e.target.value)}
                placeholder={tHC("s2")}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="kw-decline">{t("kwDecline")}</Label>
              <Input id="kw-decline" value={decline} onChange={(e) => setDecline(e.target.value)} placeholder="no" />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="kw-withdraw">{t("kwWithdraw")}</Label>
              <Input
                id="kw-withdraw"
                value={withdraw}
                onChange={(e) => setWithdraw(e.target.value)}
                placeholder={tHC("s1")}
              />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">{t("keywordsHint")}</p>

          <div className="flex items-center gap-3">
            <Switch
              id="consent-erase"
              checked={eraseOnWithdrawal}
              onCheckedChange={setEraseOnWithdrawal}
              aria-label={t("eraseOnWithdrawal")}
            />
            <Label htmlFor="consent-erase">{t("eraseOnWithdrawal")}</Label>
          </div>

          {formError && (
            <Alert variant="destructive">
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}
          {saved && (
            <Alert variant="success">
              <AlertDescription>{t("consentSaved")}</AlertDescription>
            </Alert>
          )}
          <Button type="submit" disabled={saving}>
            {saving ? t("saving") : t("saveConsent")}
          </Button>
        </form>
      )}
    </Section>
  );
}
