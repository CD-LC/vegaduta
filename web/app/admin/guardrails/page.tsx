"use client";

import { useTranslations } from "../../lib/i18n";
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Loader2, Plus, Shield, Sparkles, TriangleAlert } from "lucide-react";
import { api } from "../../lib/api";
import { useAuth } from "../../components/AuthProvider";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import ContentPolicyCard from "./ContentPolicyCard";
import { GuardrailRuleForm } from "./GuardrailRuleForm";
import { GuardrailRuleRow } from "./GuardrailRuleRow";
import { isBillingEnabled } from "../../lib/billing-flag";
import { errorMessage } from "../../lib/errors";
import { EMPTY_DRAFT, compileLocally } from "./guardrailRules";
import type { GuardrailRule, RuleDraft } from "./guardrailRules";

// Workspace guardrail rules — the missing console for core/guardrails'
// GuardrailRuleController.
//
// Two things were broken here and both are fixed in the same change. The
// smaller one is this screen: the endpoints have existed since the 2026-07-27
// billing audit, and nothing in web/ or web-admin/ ever called them, so
// authoring a rule meant curl. The larger one was underneath — a rule that was
// authored did nothing at all, because no code on the chat or tool-call path
// ever read the table back. TenantGuardrailRuleEvaluator (core/guardrails) is
// the read side; this page is the write side. Neither is much use without the
// other, which is why shipping the screen alone would have been worse than
// shipping nothing: it would have made an inert feature look finished.
//
// Not translated (no useTranslations), following the /admin/branding
// precedent: new admin screens are picked up by the next I18N-MIGRATION-PLAN
// batch rather than growing en.json in a drive-by edit while other sessions
// are writing that file. The one string that does need a key is the nav row.

export default function AdminGuardrailsPage() {
  const tg = useTranslations("f2GapsAdmin");
  const { hasPermission } = useAuth();
  // MANAGE_GUARDRAILS scope (granular RBAC, 2026-09-23) - was isAdmin;
  // reads stay open to members, writes need the scope.
  const canManageGuardrails = hasPermission("MANAGE_GUARDRAILS");

  const [rules, setRules] = useState<GuardrailRule[]>([]);
  const [plan, setPlan] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [draft, setDraft] = useState<RuleDraft>(EMPTY_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [sample, setSample] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<string | null>(null);

  const isFreePlan = plan === "FREE";
  // Both gates are enforced server-side and for different reasons, so the UI
  // has to distinguish them: a member on Unlimited and an admin on Free are
  // both blocked from writing, but only one of them can fix it by upgrading.
  const canAuthor = canManageGuardrails && !isFreePlan;

  const refresh = useCallback(async () => {
    const list: GuardrailRule[] = await api.listGuardrailRules();
    setRules(Array.isArray(list) ? list : []);
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.listGuardrailRules(),
      // Plan drives the upgrade lock only; a failure here must not hide the
      // rules, so it degrades to "unknown plan" (no lock) rather than an error.
      api.getTenantSettings().catch((): { plan?: string | null } | null => null),
    ])
      .then(([list, settings]: [GuardrailRule[], { plan?: string | null } | null]) => {
        if (cancelled) return;
        setRules(Array.isArray(list) ? list : []);
        setPlan(settings?.plan ?? null);
        setLoadError(null);
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(errorMessage(e, tg("guardrailsLoadFailed")));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tg only supplies a fallback message
  }, []);

  const [ruleQuery, setRuleQuery] = useState("");
  const matchesQuery = useCallback(
    (r: GuardrailRule) => {
      const q = ruleQuery.trim().toLowerCase();
      return !q || `${r.name} ${r.description ?? ""} ${r.pattern}`.toLowerCase().includes(q);
    },
    [ruleQuery],
  );
  const defaults = useMemo(() => rules.filter((r) => r.ruleType === "DEFAULT_TEMPLATE" && matchesQuery(r)), [rules, matchesQuery]);
  const custom = useMemo(() => rules.filter((r) => r.ruleType === "CUSTOM" && matchesQuery(r)), [rules, matchesQuery]);
  const customPagination = usePagination(custom);
  const defaultsPagination = usePagination(defaults);

  const { regex, error: patternError } = useMemo(() => compileLocally(draft.pattern), [draft.pattern]);

  // Live preview of what this rule would do to the sample text. Uses the same
  // case-insensitive matching and the same [REDACTED] marker the server uses
  // (TenantGuardrailRuleEvaluator), so what is shown here is what happens.
  const preview = useMemo(() => {
    if (!regex || !sample) return null;
    const matches = sample.match(regex);
    if (!matches || matches.length === 0) return { matched: false, result: sample, count: 0 };
    return {
      matched: true,
      count: matches.length,
      result: draft.action === "REDACT" ? sample.replace(regex, "[REDACTED]") : sample,
    };
  }, [regex, sample, draft.action]);

  function startCreate() {
    setDraft(EMPTY_DRAFT);
    setEditingId(null);
    setFormOpen(true);
    setError(null);
    setNotice(null);
  }

  function startEdit(rule: GuardrailRule) {
    setDraft({
      name: rule.name,
      description: rule.description || "",
      pattern: rule.pattern,
      action: rule.action,
      enabled: rule.enabled,
    });
    setEditingId(rule.id);
    setFormOpen(true);
    setError(null);
    setNotice(null);
  }

  function closeForm() {
    setFormOpen(false);
    setEditingId(null);
    setDraft(EMPTY_DRAFT);
    setSample("");
    setError(null);
  }

  async function saveDraft() {
    if (!draft.name.trim() || !draft.pattern.trim()) {
      setError("A rule needs both a name and a pattern.");
      return;
    }
    if (patternError) {
      setError(patternError);
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const body = {
        name: draft.name.trim(),
        description: draft.description.trim() || null,
        pattern: draft.pattern,
        action: draft.action,
        enabled: draft.enabled,
      };
      if (editingId) {
        await api.updateGuardrailRule(editingId, body);
        setNotice(`Updated "${body.name}". It takes effect on the next tool call and the next chat turn.`);
      } else {
        await api.createGuardrailRule(body);
        setNotice(`Created "${body.name}". It takes effect on the next tool call and the next chat turn.`);
      }
      await refresh();
      closeForm();
    } catch (e: unknown) {
      setError(errorMessage(e, "Could not save that rule."));
    } finally {
      setBusy(false);
    }
  }

  async function toggleEnabled(rule: GuardrailRule) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      // PUT is a full replace (GuardrailRuleRequest has no partial shape), so
      // every field is resent with only `enabled` flipped.
      await api.updateGuardrailRule(rule.id, {
        name: rule.name,
        description: rule.description,
        pattern: rule.pattern,
        action: rule.action,
        enabled: !rule.enabled,
      });
      setNotice(`"${rule.name}" is now ${!rule.enabled ? "on" : "off"}.`);
      await refresh();
    } catch (e: unknown) {
      setError(errorMessage(e, "Could not change that rule."));
    } finally {
      setBusy(false);
    }
  }

  async function deleteRule(rule: GuardrailRule) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.deleteGuardrailRule(rule.id);
      setNotice(`Deleted "${rule.name}".`);
      setConfirmingDeleteId(null);
      await refresh();
    } catch (e: unknown) {
      setError(errorMessage(e, "Could not delete that rule."));
    } finally {
      setBusy(false);
    }
  }

  function renderRuleRow(rule: GuardrailRule, editable: boolean) {
    return (
      <GuardrailRuleRow
        key={rule.id}
        rule={rule}
        editable={editable}
        busy={busy}
        canAuthor={canAuthor}
        upgradeLocked={canManageGuardrails && isFreePlan}
        isConfirming={confirmingDeleteId === rule.id}
        onToggle={() => void toggleEnabled(rule)}
        onEdit={() => startEdit(rule)}
        onRequestDelete={() => setConfirmingDeleteId(rule.id)}
        onDelete={() => void deleteRule(rule)}
        onCancelDelete={() => setConfirmingDeleteId(null)}
      />
    );
  }

  return (
    <AppShell eyebrow="Guardrails">
      <PageHeader
        eyebrow="Safety"
        title="Guardrails"
        subtitle="Patterns this workspace refuses to pass through. Every rule here is checked against the result of every tool your agents call, before that text reaches the model."
      />

      {loading && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Loading your guardrail rules…
        </div>
      )}

      {!loading && loadError && (
        <Alert variant="destructive">
          <TriangleAlert className="size-4" aria-hidden="true" />
          <AlertTitle>{tg("guardrailsLoadFailedTitle")}</AlertTitle>
          <AlertDescription>{loadError}</AlertDescription>
        </Alert>
      )}

      {!loading && !loadError && (
        <div className="flex flex-col gap-6">
          {error && (
            <Alert variant="destructive">
              <TriangleAlert className="size-4" aria-hidden="true" />
              <AlertTitle>That didn&apos;t work</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          {notice && (
            <Alert>
              <AlertTitle>Saved</AlertTitle>
              <AlertDescription>{notice}</AlertDescription>
            </Alert>
          )}

          {!canManageGuardrails && (
            <Alert>
              <Shield className="size-4" aria-hidden="true" />
              <AlertTitle>You can see these, but not change them</AlertTitle>
              <AlertDescription>
                Guardrail rules apply to every agent and every member of this workspace, so editing them is limited to
                workspace admins. Ask an admin if one of these is getting in your way.
              </AlertDescription>
            </Alert>
          )}

          {canManageGuardrails && isFreePlan && (
            <Alert>
              <Sparkles className="size-4" aria-hidden="true" />
              <AlertTitle>Your own rules start on Premium</AlertTitle>
              <AlertDescription className="flex flex-col items-start gap-3">
                <span>
                  The platform&apos;s default rules below are active on every plan, including yours, and always will be.
                  Writing your own patterns is a Premium feature.
                </span>
                {isBillingEnabled() && (
                  <Button asChild size="sm">
                    <Link href="/admin/billing">See plans</Link>
                  </Button>
                )}
              </AlertDescription>
            </Alert>
          )}

          <Card>
            <CardHeader>
              <CardTitle>Where these apply</CardTitle>
              <CardDescription>
                Worth knowing before you write one, because the two layers are enforced in different places.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3 text-sm text-muted-foreground">
              <p>
                <span className="font-medium text-foreground">Tool results — always.</span> Every rule on this page runs
                against whatever a tool hands back (an API response, a file, a web page, an MCP server&apos;s output)
                before the model reads it. No per-agent switch: tool output is text from outside the platform, which is
                the case these rules exist for.
              </p>
              <p>
                <span className="font-medium text-foreground">Conversations — per agent, opt in.</span> To also check
                what people type and what an agent replies, turn on <em>Custom rules</em> in that agent&apos;s Guardrails
                card on the <Link href="/agents" className="underline underline-offset-2">Agents</Link> screen. There,
                a <strong>Block</strong> rule works in both directions, while <strong>Redact</strong> can only rewrite
                the agent&apos;s reply — a message someone already typed is left as they typed it.
              </p>
              <p>
                <span className="font-medium text-foreground">Patterns are regular expressions</span>, matched
                case-insensitively, anywhere in the text. They add to the platform&apos;s own built-in checks for
                prompt-injection markers and secret-shaped strings; they never replace or switch those off.
              </p>
            </CardContent>
          </Card>

          {rules.length > 8 && (
            <div className="max-w-sm">
              <Label htmlFor="guardrail-rule-search" className="sr-only">Search rules</Label>
              <Input
                id="guardrail-rule-search"
                value={ruleQuery}
                onChange={(e) => setRuleQuery(e.target.value)}
                placeholder="Search rules by name, description or pattern"
              />
            </div>
          )}
          <Card>
            <CardHeader className="flex flex-row items-start justify-between gap-4">
              <div className="flex flex-col gap-1.5">
                <CardTitle>Your rules</CardTitle>
                <CardDescription>
                  {custom.length === 0
                    ? "You haven't written any yet."
                    : `${custom.length} rule${custom.length === 1 ? "" : "s"} written by this workspace.`}
                </CardDescription>
              </div>
              {!formOpen && (
                <Button
                  type="button"
                  size="sm"
                  data-testid="guardrail-new-rule"
                  disabled={busy || !canManageGuardrails}
                  upgradeLocked={canManageGuardrails && isFreePlan}
                  upgradeReason="Authoring your own guardrail rules is included from the Premium plan."
                  upgradeHref="/admin/billing"
                  onClick={startCreate}
                >
                  <Plus className="size-4" aria-hidden="true" /> New rule
                </Button>
              )}
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              {formOpen && (
                <GuardrailRuleForm
                  draft={draft}
                  onDraftChange={setDraft}
                  editingId={editingId}
                  busy={busy}
                  canAuthor={canAuthor}
                  patternError={patternError}
                  sample={sample}
                  onSampleChange={setSample}
                  preview={preview}
                  onClose={closeForm}
                  onSave={() => void saveDraft()}
                />
              )}

              {custom.length === 0 && !formOpen && (
                <p className="text-sm text-muted-foreground">
                  Rules you write here apply on top of the platform defaults below. A good first one is usually an
                  internal identifier or codename you never want a model to see.
                </p>
              )}

              {customPagination.pageItems.map((rule) => renderRuleRow(rule, true))}
              <PaginationControls
                page={customPagination.page}
                totalPages={customPagination.totalPages}
                totalItems={customPagination.totalItems}
                pageSize={customPagination.pageSize}
                onPageChange={customPagination.setPage}
                onPageSizeChange={customPagination.setPageSize}
                itemLabel="rules"
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Platform defaults</CardTitle>
              <CardDescription>
                Active for every workspace on every plan. They can&apos;t be edited or turned off — they are the floor
                your own rules sit on top of.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {defaults.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No default rules are present in this workspace. The platform&apos;s own built-in checks still run —
                  these rows are the data-driven copy of them.
                </p>
              ) : (
                defaultsPagination.pageItems.map((rule) => renderRuleRow(rule, false))
              )}
              <PaginationControls
                page={defaultsPagination.page}
                totalPages={defaultsPagination.totalPages}
                totalItems={defaultsPagination.totalItems}
                pageSize={defaultsPagination.pageSize}
                onPageChange={defaultsPagination.setPage}
                onPageSizeChange={defaultsPagination.setPageSize}
                itemLabel="rules"
              />
            </CardContent>
          </Card>

          <ContentPolicyCard canManage={canManageGuardrails} />
        </div>
      )}
    </AppShell>
  );
}
