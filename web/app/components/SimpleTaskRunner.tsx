"use client";

import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "motion/react";
import { Loader2, Paperclip, CheckCircle2, History, AlertTriangle } from "lucide-react";
import { toast } from "../lib/toastBus";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Textarea } from "./ui/textarea";
import { Badge } from "./ui/badge";
import { Alert, AlertDescription } from "./ui/alert";
import PageHeader from "./ax/PageHeader";
import BrandText from "./BrandText";
import { usePageReady } from "./RouteTransition";
import { errorMessage } from "../lib/errors";
import ResearchEntryCard from "./research/ResearchEntryCard";
import { EXAMPLES, type QuickTaskStreamEvent, type RunPower } from "./quickTask/model";
import QuickTaskPlanCard from "./quickTask/QuickTaskPlanCard";
import QuickTaskLiveTrace from "./quickTask/QuickTaskLiveTrace";
import QuickTaskResultCard from "./quickTask/QuickTaskResultCard";

// Quick Task: type what you want done, confirm the detected plan, and the
// result is delivered by WhatsApp, SMS, a call, or only logged. The design
// history, the channel list and the stream event shape are documented in
// quickTask/model.ts.

export default function SimpleTaskRunner() {
  const tHC = useTranslations("hcComponentsSimpleTaskRunner");
  const t = useTranslations("simpleTaskRunner");
  const [phoneNumber, setPhoneNumber] = useState(null); // null = not loaded yet
  const [phoneDraft, setPhoneDraft] = useState("");
  const [phoneBusy, setPhoneBusy] = useState(false);
  const [phoneError, setPhoneError] = useState(null);

  const [message, setMessage] = useState("");
  const [channel, setChannel] = useState("WHATSAPP");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  // "customers should be able to select the agents" - agentId null means
  // auto-select (today's default, AgentRelevanceSelector picks the closest
  // match server-side); a real id routes straight to that agent instead. Both
  // the preview and the real run honor this the same way (see api.js).
  const [agents, setAgents] = useState([]);
  const [agentId, setAgentId] = useState("");
  // agentsLoaded (distinct from `agents.length` - an empty tenant has zero
  // agents and that's a legitimate loaded state, not "still loading")
  // separately tracks whether the initial listAgents() call has settled, so
  // usePageReady below can tell "not fetched yet" apart from "fetched, empty".
  const [agentsLoaded, setAgentsLoaded] = useState(false);
  useEffect(() => {
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]))
      .finally(() => setAgentsLoaded(true));
  }, []);

  // Live trace (#474 follow-up: "I want to see the live trace log for that
  // task - everything running for that task") - each entry is one real
  // ToolCallLog row QuickTaskService.runStream noticed and reported over
  // SSE (api.runQuickTaskStream), in the order tools actually fired. Empty
  // just means "no tools were needed" (the agent answered from its own
  // knowledge) - not a broken trace.
  const [trace, setTrace] = useState([]);

  // Direct feedback: "in quicktask - it should generate an ID of a task and
  // we should be able to see the complete log with request sent and
  // response back - not just half of it - any point of time - they should
  // be able to view it." taskId is the real sessionId the "started" SSE
  // event carries (QuickTaskRunService now persists a QuickTaskRun row
  // keyed by this exact id) - shown as soon as the run starts, and stays
  // visible on the result card afterward so it can be looked up again later
  // via the dedicated Task history page (/quick-task/history), even in a
  // different browser session.
  const [taskId, setTaskId] = useState(null);

  // "customers should know what they are doing, what is powering" - the
  // engine the running task resolved to, taken from the stream's started/
  // done events (the agent's configured provider+model, emitted
  // server-side). Null until a run starts; cleared with the rest of the
  // run state.
  const [runPower, setRunPower] = useState<RunPower | null>(null);

  // "Ask user how to trigger it, validate it" - Run opens this confirmation
  // step instead of executing immediately; the actual api.runQuickTask call
  // only happens from confirmRun() below, once the person has explicitly
  // reviewed the detected agent/tools and delivery channel shown here.
  const [showConfirm, setShowConfirm] = useState(false);

  // "Detect the underlying workflow if specified, select defined tools/MCP
  // tools" - a debounced dry-run preview (QuickTaskController.preview) so
  // the person sees which agent + tools would actually be used before they
  // ever hit Run, without adding a click of their own to ask for it.
  const [preview, setPreview] = useState(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const previewTimer = useRef(null);

  // Both failure modes below (message too short to preview, and a preview
  // request that actually failed) used to leave the person clicking
  // "Get it done" and seeing nothing happen - showConfirm flipped true but
  // the confirm step only ever renders inside the plan card below, and the
  // plan card only renders once a preview has come back successfully. This
  // state makes the too-short case an explicit inline message instead.
  const [validationMessage, setValidationMessage] = useState(null);

  // "User should be able to attach the knowledge base, upload the docs" -
  // reuses the same ingestion pipeline every other upload in this app goes
  // through (KnowledgeController.upload -> chunk + embed + store), landing
  // in the tenant's Default collection so no collection picker is needed
  // here. That pipeline succeeding IS the "system checks the doc" step -
  // a bad/unreadable file fails ingestion and shows as an error, not a
  // silent no-op.
  const [attachments, setAttachments] = useState([]);
  const [attachBusy, setAttachBusy] = useState(false);
  const fileInputRef = useRef(null);

  useEffect(() => {
    api
      .getAccount()
      .then((account) => {
        const phone = account?.phoneNumber || "";
        setPhoneNumber(phone);
        // No number on file: start on Log only, the one channel that needs none, so the
        // primary button is usable. A channel the person already picked is left alone.
        if (!phone) setChannel((current) => (current === "WHATSAPP" ? "LOG" : current));
      })
      .catch(() => setPhoneNumber(""));
  }, []);

  useEffect(() => {
    clearTimeout(previewTimer.current);
    const trimmed = message.trim();
    if (trimmed.length < 8) {
      setPreview(null);
      return undefined;
    }
    previewTimer.current = setTimeout(
      () =>
        void (async () => {
          setPreviewBusy(true);
          try {
            const response = await api.previewQuickTask(trimmed, agentId || undefined);
            setPreview(response);
          } catch (err) {
            setPreview(null);
            toast.error(errorMessage(err, t("previewFailedToast")));
          } finally {
            setPreviewBusy(false);
          }
        })(),
      500
    );
    return () => clearTimeout(previewTimer.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [message, agentId]);

  async function attachFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setAttachBusy(true);
    try {
      const ingested = await api.uploadKnowledgeFile(file, null);
      setAttachments((list) => [...list, { name: file.name, chunkCount: ingested.chunkCount }]);
      toast.success(
        t("fileCheckedAdded", {
          fileName: file.name,
          count: ingested.chunkCount,
          plural: ingested.chunkCount === 1 ? "" : "s",
        })
      );
    } catch (err) {
      toast.error(errorMessage(err, t("couldNotReadFile", { fileName: file.name })));
    } finally {
      setAttachBusy(false);
    }
  }

  async function savePhone(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPhoneBusy(true);
    setPhoneError(null);
    try {
      const account = await api.setAccountPhoneNumber(phoneDraft.trim());
      setPhoneNumber(account.phoneNumber);
    } catch (err) {
      setPhoneError(errorMessage(err, t("couldNotSaveNumber")));
    } finally {
      setPhoneBusy(false);
    }
  }

  // Opens the confirmation step - the actual run is confirmRun() below.
  function requestRun() {
    const trimmed = message.trim();
    if (!trimmed || needsPhone) return;
    // The confirm step only renders inside the plan card, and the plan card
    // only renders once a preview has come back - so without these checks a
    // too-short message or a failed preview made this button silently do
    // nothing when clicked.
    if (trimmed.length < 8) {
      setValidationMessage(t("messageTooShortValidation"));
      return;
    }
    if (!preview) {
      toast.error(t("previewNotReadyError"));
      return;
    }
    // Same silent-no-op class as the two guards above: with a finished
    // result (or error) still on screen, showPlan is false, so opening the
    // confirm step would render nothing. Clear the stale outcome first -
    // mirrors exactly what the textarea's onChange already does on edit.
    if (result || error) {
      setResult(null);
      setError(null);
      setTrace([]);
      setRunPower(null);
    }
    setValidationMessage(null);
    setShowConfirm(true);
  }

  async function confirmRun() {
    setShowConfirm(false);
    setBusy(true);
    setError(null);
    setResult(null);
    setTrace([]);
    setTaskId(null);
    setRunPower(null);
    try {
      // Streaming, not the older blocking runQuickTask - tool_call events
      // arrive live (real ToolCallLog rows, polled server-side) while the
      // task is still running; "done"/"error" close it out with the same
      // data the blocking endpoint would have returned synchronously.
      await api.runQuickTaskStream(
        message.trim(),
        channel,
        (evt: QuickTaskStreamEvent) => {
          if (evt.type === "started") {
            setTaskId(evt.sessionId);
            setRunPower({ providerName: evt.providerName, model: evt.model });
          } else if (evt.type === "tool_call") {
            setTrace((prev) => [...prev, evt]);
          } else if (evt.type === "done") {
            setResult({ resultText: evt.resultText, deliveryNote: evt.deliveryNote });
            if (evt.providerName || evt.model) setRunPower({ providerName: evt.providerName, model: evt.model });
            toast.success(
              evt.channel === "LOG"
                ? t("doneLoggedToast")
                : t("doneSentViaToast", { channel: evt.channel === "CALL" ? t("phoneCallLabel") : evt.channel })
            );
          } else if (evt.type === "error") {
            setError(evt.message || t("genericRunError"));
          }
        },
        agentId || undefined
      );
    } catch (err) {
      setError(errorMessage(err, t("genericRunError")));
    } finally {
      setBusy(false);
    }
  }

  // "Once quick task is done, there should be option to restart the next
  // task" - previously result/error/trace only ever got cleared at the TOP
  // of confirmRun(), so once a task finished, showPlan/showAnalyzing stayed
  // permanently false (both gated on `!result`) and the screen was stuck on
  // the DONE card with no way back to the input flow short of a full page
  // reload. Two ways out now: this explicit reset (a visible "Start a new
  // task" button on the result card) and, separately, the textarea's own
  // onChange below also clears a stale result/error the moment someone just
  // starts typing the next task, so neither path requires both.
  function resetTask() {
    setMessage("");
    setResult(null);
    setError(null);
    setTrace([]);
    setTaskId(null);
    setRunPower(null);
    setPreview(null);
    setAttachments([]);
    setShowConfirm(false);
  }

  // UX audit fix: this screen didn't opt into the route-transition cover
  // (usePageReady/RouteTransition) the way most other data-fetching pages
  // do, so navigating here revealed the form before its own account-phone
  // and agent-list fetches had actually resolved - e.g. the "add your phone
  // number" card below is deliberately hidden while `phoneNumber === null`,
  // so it could pop in a moment late. Holding the shared cover open until
  // both have settled makes Quick Task's loading behavior (and the branded
  // spinner it shows meanwhile) consistent with every other console page.
  usePageReady(phoneNumber !== null && agentsLoaded);

  // LOG never dispatches to a phone (see QuickTaskChannel#LOG's javadoc /
  // the CHANNELS comment above), so it's the one delivery choice that
  // shouldn't be gated behind "add your phone number first."
  const needsPhone = phoneNumber === "" && channel !== "LOG";

  // "customers should know what is powering" the task BEFORE they confirm.
  // The preview response carries the resolved agent's provider/model on
  // newer core builds; on older ones the local agents list fills it in by
  // id (explicit pick or preview-returned agentId) or name. Nothing is
  // fabricated - if neither source knows, no chip renders.
  const previewAgent =
    agents.find((a: any) => a.id === (agentId || preview?.agentId)) ??
    agents.find((a: any) => a.name === preview?.agentName);
  const plannedPower =
    preview && (preview.providerName || preview.model || previewAgent?.providerName || previewAgent?.model)
      ? {
          providerName: preview.providerName ?? previewAgent?.providerName ?? null,
          model: preview.model ?? previewAgent?.model ?? null,
        }
      : null;
  const trimmedLength = message.trim().length;
  const showAnalyzing = previewBusy && !preview && trimmedLength >= 8 && !result;
  const showPlan = !!preview && trimmedLength >= 8 && !result;

  return (
    <div className="mx-auto w-full max-w-2xl">
      {/* BrandText: the brand name inside these strings renders two-tone
          (orange "Dūta") per the 2026-08-12 brand-colour instruction. */}
      <PageHeader
        eyebrow={t("eyebrow")}
        title={<BrandText text={t("title")} />}
        subtitle={<BrandText text={t("subtitle")} />}
      />
      <div className="mt-6 space-y-5">
        <ResearchEntryCard />

        {needsPhone === true && phoneNumber !== null && (
          <Card className="p-5">
            <form onSubmit={savePhone} className="space-y-3">
              <Label htmlFor="qt-phone">{t("phoneCardLabel")}</Label>
              <div className="flex gap-2">
                <Input
                  id="qt-phone"
                  type="tel"
                  className="flex-1"
                  placeholder={t("phoneInputPlaceholder")}
                  value={phoneDraft}
                  onChange={(e) => setPhoneDraft(e.target.value)}
                  required
                />
                <Button type="submit" disabled={phoneBusy}>
                  {phoneBusy ? t("saving") : t("save")}
                </Button>
              </div>
              {phoneError && <p className="text-sm text-destructive">{phoneError}</p>}
              <p className="text-xs text-muted-foreground">{t("yourOwnNumberHint")}</p>
            </form>
          </Card>
        )}

        <Card className="gap-3 p-4">
          <Textarea
            id="qt-task"
            aria-label={t("textareaPlaceholder")}
            aria-describedby="qt-task-help"
            className="min-h-24 resize-y border-0 bg-transparent px-0 py-0 text-base shadow-none focus-visible:ring-0"
            placeholder={t("textareaPlaceholder")}
            autoFocus
            onKeyDown={(e) => {
              // Cmd/Ctrl+Enter = "Get it done" - same guarded requestRun()
              // path as the button, nothing runs without the confirm step.
              if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
                e.preventDefault();
                requestRun();
              }
            }}
            value={message}
            onChange={(e) => {
              const value = e.target.value;
              setMessage(value);
              if (validationMessage) setValidationMessage(null);
              // Editing the message after a run finished (or failed) means
              // this is the start of a NEW task - clear the stale outcome so
              // the normal preview/plan flow can take over again instead of
              // staying stuck on the previous DONE/error card.
              if (result || error) {
                setResult(null);
                setError(null);
                setTrace([]);
              }
            }}
            rows={3}
            disabled={busy}
          />
          <p id="qt-task-help" className="text-xs text-muted-foreground">
            {tHC("s1")}
          </p>
          <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
            <input
              ref={fileInputRef}
              type="file"
              accept=".md,.pdf,.docx,.html,.htm,.png,.jpg,.jpeg,.webp"
              style={{ display: "none" }}
              onChange={attachFile}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => fileInputRef.current?.click()}
              disabled={attachBusy || busy}
            >
              <Paperclip aria-hidden="true" size={13} /> {attachBusy ? t("checkingFile") : t("attachDocument")}
            </Button>
            {attachments.map((a, i) => (
              <Badge key={i} variant="secondary" className="gap-1.5">
                <CheckCircle2 aria-hidden="true" size={12} className="text-emerald-500" /> {a.name}
              </Badge>
            ))}
            <div className="flex-1" />
            {needsPhone && (
              <span id="qt-phone-needed" className="text-xs text-muted-foreground">
                {t("needPhoneOrLog")}
              </span>
            )}
            {/* One solid primary per view: while the confirm step is open
             * the real primary action is "Confirm & run" in the plan card,
             * so this demotes to the quiet style until confirm closes. */}
            <Button
              variant={showConfirm ? "outline" : "default"}
              onClick={requestRun}
              disabled={busy || !message.trim() || needsPhone}
              aria-describedby={needsPhone ? "qt-phone-needed" : undefined}
            >
              {busy ? (
                <>
                  <Loader2 aria-hidden="true" size={15} className="animate-spin" />
                  {trace.length > 0
                    ? t("workingOnItSteps", { count: trace.length, plural: trace.length === 1 ? "" : "s" })
                    : t("workingOnIt")}
                </>
              ) : (
                t("getItDone")
              )}
            </Button>
          </div>
          {validationMessage && <p className="text-sm text-destructive">{validationMessage}</p>}
        </Card>

        {!message.trim() && (
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((ex) => (
              <Button
                key={ex.key}
                type="button"
                variant={ex.featured ? "secondary" : "outline"}
                size="sm"
                className="h-auto rounded-full py-1.5 font-normal text-muted-foreground hover:text-foreground"
                onClick={() => setMessage(t(ex.key))}
              >
                {t(ex.key)}
              </Button>
            ))}
          </div>
        )}

        <AnimatePresence>
          {showAnalyzing && (
            <motion.div
              className="flex items-center gap-2 text-sm text-muted-foreground"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15, ease: "easeOut" }}
            >
              <Loader2 aria-hidden="true" size={14} className="animate-spin text-primary" />
              <BrandText text={t("analyzingText")} />
            </motion.div>
          )}
        </AnimatePresence>

        <AnimatePresence>
          {showPlan && (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
            >
              <QuickTaskPlanCard
                preview={preview}
                agents={agents}
                agentId={agentId}
                setAgentId={setAgentId}
                plannedPower={plannedPower}
                channel={channel}
                setChannel={setChannel}
                phoneNumber={phoneNumber}
                attachmentCount={attachments.length}
                busy={busy}
                showConfirm={showConfirm}
                onCancelConfirm={() => setShowConfirm(false)}
                onConfirm={confirmRun}
              />
            </motion.div>
          )}
        </AnimatePresence>

        <AnimatePresence>
          {(busy || trace.length > 0) && (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
            >
              <QuickTaskLiveTrace busy={busy} trace={trace} runPower={runPower} taskId={taskId} />
            </motion.div>
          )}
        </AnimatePresence>

        {error && (
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <AnimatePresence>
          {result && (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
            >
              <QuickTaskResultCard result={result} runPower={runPower} taskId={taskId} onReset={resetTask} />
            </motion.div>
          )}
        </AnimatePresence>

        {/* "task history should be in another page" - moved off this screen
         * into its own route; this is just a link out, the list/detail
         * viewer itself lives at /quick-task/history (QuickTaskHistoryList). */}
        <div className="flex justify-center pt-1">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground">
            <Link href="/quick-task/history">
              <History aria-hidden="true" size={13} /> {t("viewTaskHistory")}
            </Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
