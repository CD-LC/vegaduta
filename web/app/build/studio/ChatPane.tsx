"use client";

import { Check, Cloud, History, Loader2, Paperclip, MousePointerClick, Send, Square, Wrench, X, GitCompare, Mic } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Textarea } from "../../components/ui/textarea";
import { HARNESS_LINES } from "./runtime";
import { ATTACH_ACCEPT, MAX_ATTACHMENTS } from "./attachments";
import { fixRequest, locateRuntimeError } from "./scope";
import { firstBrokenScript } from "./patch";
import type { ProjectSession } from "./useProjectSession";
import { SOURCE_LABEL, timeAgo } from "./sessionTypes";

/** Left column: the conversation with the builder, the request box, attachments and the version history. */
/** What the live-output box shows before the model's first word arrives.
 * On-device engines stream tokens, so `stream` fills quickly; the cloud
 * legs answer in ONE non-streamed reply, so without this the box would sit
 * on a dead "waiting" line for the whole turn. Phase the note on elapsed
 * time so it keeps moving and stays honest about why nothing is arriving. */
function waitingLine(engineLabel: string, elapsed: number): string {
  const device = /^on your device/i.test(engineLabel || "");
  if (elapsed < 15) {
    return device ? "Waking up the on-device model…" : "Sending your request to the model…";
  }
  if (elapsed < 45) {
    return device
      ? "Model is ready — the first words are on their way…"
      : "The model is writing — cloud replies arrive all at once, not word-by-word…";
  }
  return `Still writing (${elapsed}s) — a whole-app change can take a couple of minutes on this model. Stop cancels safely.`;
}

export default function ChatPane({ s }: { s: ProjectSession }) {
  const {
    attached, busy, dictation, editable, elapsed, fileInputRef, html, inspect, lastFixRef, log, pickFiles, project, pushLog, request, restore, runEdit, runtimeError, scopeAsk, selected, send, setAttached, setDiffFrom, setInspect, setRequest, setScopeAsk, setSelected, stop, stream, undo, undoTarget
  } = s;
  return (
    <div className="order-2 flex min-h-0 flex-col gap-3 lg:order-1">
      <fieldset disabled={!editable} className="m-0 flex min-w-0 flex-col gap-2 rounded-2xl border border-border bg-card p-3">
        {selected && (
          <div className="flex items-center gap-2 rounded-lg bg-orange-500/10 px-2 py-1.5 text-xs text-orange-700 dark:text-orange-300">
            <MousePointerClick aria-hidden="true" size={12} />
            <span className="min-w-0 flex-1 truncate">
              Targeting &lt;{selected.tag}&gt; {selected.text ? `"${selected.text.slice(0, 30)}"` : ""}
            </span>
            <button type="button" onClick={() => setSelected(null)} aria-label="Clear target" className="rounded p-0.5 hover:bg-orange-500/20">
              <X aria-hidden="true" size={12} />
            </button>
          </div>
        )}
        <Textarea
          value={request}
          onChange={(e) => setRequest(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent?.isComposing) {
              e.preventDefault();
              send();
            }
          }}
          rows={3}
          placeholder={selected ? "What should change here? e.g. make it bigger and green" : "Ask for a change, e.g. add a dark mode switch, or add a search box"}
          className="resize-none"
          aria-label="Ask for a change"
        />
        {attached.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {attached.map((a) => (
              <span key={a.id} className="inline-flex max-w-full items-center gap-1.5 rounded-full border border-border bg-secondary px-2 py-1 text-[11px] text-foreground">
                {a.kind === "image" && a.dataUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={a.dataUrl} alt="" className="size-4 rounded object-cover" />
                ) : (
                  <Paperclip aria-hidden="true" size={11} />
                )}
                <span className="max-w-[9rem] truncate">{a.name}</span>
                <button type="button" aria-label={`Remove ${a.name}`} onClick={() => setAttached((l) => l.filter((x) => x.id !== a.id))} className="rounded p-0.5 hover:bg-background">
                  <X aria-hidden="true" size={10} />
                </button>
              </span>
            ))}
          </div>
        )}
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept={ATTACH_ACCEPT}
          className="hidden"
          onChange={(e) => void pickFiles(e.target.files)}
          aria-label="Attach a file"
        />
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <Button
              variant={inspect ? "default" : "outline"}
              size="sm"
              aria-pressed={inspect}
              onClick={() => setInspect((v) => !v)}
              className="gap-1.5"
              title="Tap an element in the preview to target it"
            >
              <MousePointerClick aria-hidden="true" size={13} /> {inspect ? "Tap an element…" : "Select"}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => fileInputRef.current?.click()}
              disabled={!!busy || attached.length >= MAX_ATTACHMENTS}
              className="gap-1.5"
              title="Attach an image or a data file (csv, json, txt) for the AI to use"
              aria-label="Attach a file"
            >
              <Paperclip aria-hidden="true" size={13} /> Attach
            </Button>
            {dictation.supported && (
              <Button
                variant={dictation.listening ? "default" : "outline"}
                size="sm"
                onClick={dictation.listening ? dictation.stop : dictation.start}
                disabled={!!busy}
                aria-pressed={dictation.listening}
                className="gap-1.5"
                title="Dictate your request (your browser's speech service transcribes it)"
                aria-label={dictation.listening ? "Stop dictation" : "Dictate your request"}
              >
                <Mic aria-hidden="true" size={13} /> {dictation.listening ? "Listening…" : "Dictate"}
              </Button>
            )}
          </div>
          {busy ? (
            <Button size="sm" variant="secondary" onClick={stop} className="gap-1.5">
              <Square aria-hidden="true" size={12} /> Stop
            </Button>
          ) : (
            <Button size="sm" onClick={() => send()} disabled={!request.trim()} className="gap-1.5">
              <Send aria-hidden="true" size={13} /> Change it
            </Button>
          )}
        </div>
        {dictation.error && (
          <p role="alert" className="text-xs text-destructive">
            {dictation.error}
          </p>
        )}
        <div className="flex flex-wrap gap-1.5">
          {["Add a second screen", "Make it more colourful", "Add a camera button", "Add a file upload for my users", "Call my agent's endpoint"].map((s) => (
            <button
              key={s}
              type="button"
              disabled={!!busy}
              onClick={() => setRequest(s)}
              className="rounded-full border border-border px-2.5 py-1 text-[11px] text-muted-foreground transition hover:border-primary/50 hover:text-foreground disabled:opacity-50"
            >
              {s}
            </button>
          ))}
        </div>
      </fieldset>

      {scopeAsk && !busy && (
        <div role="alertdialog" aria-labelledby="scope-ask-title" className="flex flex-col gap-2 rounded-2xl border border-amber-500/40 bg-amber-500/[0.06] p-3 text-xs">
          <p id="scope-ask-title" className="font-medium text-foreground">This changes the whole page</p>
          <p className="text-muted-foreground">
            Big all-at-once changes are harder for the AI to get right, and more of your app can shift than you meant. It is usually easier to
            tap <span className="font-medium text-foreground">Select</span>, pick the part you want changed, and describe it - one part at a time.
          </p>
          <p className="text-muted-foreground">If you wish to continue with the whole page anyway, click Yes.</p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={() => send({ wholePage: true })}>
              Yes, continue
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="gap-1.5"
              onClick={() => {
                setScopeAsk(null);
                setInspect(true);
              }}
            >
              <MousePointerClick aria-hidden="true" size={12} /> Select a part instead
            </Button>
          </div>
        </div>
      )}

      {busy && (
        <div className="flex flex-col gap-2 rounded-2xl border border-primary/30 bg-primary/[0.04] p-3">
          <div className="flex items-center gap-2 text-xs font-medium text-foreground">
            <Loader2 aria-hidden="true" size={13} className="animate-spin text-primary" /> {busy.label}
            <span className="rounded-full bg-background px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
              {busy.engineLabel}
            </span>
            <span className="ml-auto tabular-nums text-muted-foreground">{elapsed}s</span>
          </div>
          <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-neutral-950 p-2 font-mono text-[10px] leading-snug text-emerald-300">
            {stream ? stream.slice(-1800) : waitingLine(busy.engineLabel, elapsed)}
          </pre>
        </div>
      )}

      {runtimeError && !busy && (
        <div className="flex flex-col gap-2 rounded-2xl border border-destructive/40 bg-destructive/5 p-3 text-xs">
          <p className="font-medium text-destructive">The app hit an error</p>
          <p className="break-words font-mono text-[11px] text-muted-foreground">{runtimeError.message}</p>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              className="gap-1.5"
              onClick={() => {
                const where = locateRuntimeError(html, runtimeError, HARNESS_LINES);
                const broken = /syntaxerror|unexpected token/i.test(runtimeError.message)
                  ? firstBrokenScript(html)
                  : null;
                const repeated = lastFixRef.current === runtimeError.message;
                lastFixRef.current = runtimeError.message;
                pushLog({ kind: "user", text: repeated ? "Fix the error (again, differently)" : "Fix the error" });
                void runEdit(fixRequest(runtimeError, where, repeated, broken));
              }}
            >
              <Wrench aria-hidden="true" size={12} /> Fix it with AI
            </Button>
            {undoTarget() >= 0 && (
              <Button size="sm" variant="ghost" onClick={undo}>
                Undo last change
              </Button>
            )}
          </div>
        </div>
      )}

      {log.length > 0 && (
        <div className="flex max-h-64 flex-col gap-1.5 overflow-auto rounded-2xl border border-border bg-card p-3">
          {log.map((e) => (
            <div key={e.id} className={e.kind === "user" ? "self-end rounded-xl bg-primary px-2.5 py-1.5 text-xs text-primary-foreground" : "text-xs"}>
              {e.kind === "user" ? (
                e.text
              ) : (
                <span className={e.kind === "error" ? "text-destructive" : "text-foreground"}>
                  {e.kind === "result" ? <Check aria-hidden="true" size={11} className="mr-1 inline text-emerald-500" /> : null}
                  {e.text}
                  {e.engine && (
                    <span className="ml-1 text-muted-foreground">
                      · {e.engine === "device" ? `on your device${e.model ? ` (${e.model})` : ""} · free` : `cloud${e.model ? ` (${e.model})` : ""}`}
                      {e.ms ? ` · ${Math.round(e.ms / 1000)}s` : ""}
                    </span>
                  )}
                  {e.cloudRetry && (
                    <button
                      type="button"
                      disabled={!!busy}
                      onClick={() => void runEdit(e.cloudRetry!.req, { prefer: "cloud", target: e.cloudRetry!.target })}
                      className="ml-2 inline-flex items-center gap-1 rounded-full border border-sky-500/40 bg-sky-500/10 px-2 py-0.5 font-medium text-sky-700 transition hover:bg-sky-500/20 disabled:opacity-50 dark:text-sky-300"
                    >
                      <Cloud aria-hidden="true" size={10} /> Use the cloud for this one
                    </button>
                  )}
                </span>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-1 rounded-2xl border border-border bg-card p-3">
        <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold text-foreground">
          <History aria-hidden="true" size={13} /> Versions
          <span className="ml-auto font-normal text-muted-foreground">{project.versions.length}</span>
        </div>
        <div className="flex max-h-56 flex-col gap-0.5 overflow-auto">
          {[...project.versions].reverse().map((v, ri) => {
            const i = project.versions.length - 1 - ri;
            const isCurrent = ri === 0;
            return (
              <div key={v.at + "-" + i} className="group flex items-center gap-2 rounded-lg px-1.5 py-1 text-xs hover:bg-secondary">
                <span className={`size-1.5 shrink-0 rounded-full ${isCurrent ? "bg-primary" : "bg-muted-foreground/40"}`} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-foreground">{v.note}</span>
                  <span className="text-[10px] text-muted-foreground">
                    {SOURCE_LABEL[v.source]} · {timeAgo(v.at)}
                  </span>
                </span>
                {!isCurrent && (
                  <>
                    <button
                      type="button"
                      onClick={() => setDiffFrom(i)}
                      aria-label={`Compare "${v.note}" with the current version`}
                      title="Compare with the current version"
                      className="rounded p-1 text-muted-foreground transition hover:bg-secondary hover:text-foreground sm:opacity-0 sm:group-hover:opacity-100 sm:focus:opacity-100 [@media(hover:none)]:opacity-100"
                    >
                      <GitCompare aria-hidden="true" size={12} />
                    </button>
                    {editable && (
                      <button type="button" onClick={() => restore(i)} className="rounded px-1.5 py-0.5 text-[11px] text-primary transition hover:bg-primary/10 sm:opacity-0 sm:group-hover:opacity-100 sm:focus:opacity-100 [@media(hover:none)]:opacity-100">
                        Restore
                      </button>
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
