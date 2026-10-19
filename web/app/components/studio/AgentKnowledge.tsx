"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  UploadCloud,
  FileText,
  Trash2,
  Loader2,
  ClipboardPaste,
  FlaskConical,
  ShieldCheck,
  CheckCircle2,
  AlertTriangle,
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { studio, type DocumentLimits, type StudioAgent, type StudioDocument } from "./studioClient";
import { sampleDocument } from "./topicDraft";
import { TONES } from "./studioTheme";

// Documents for one agent: real chunk + embed + store into a collection that
// belongs to this agent alone, searched by the agent's own knowledge tool.
// Nothing here is a preview - a file that shows READY is searchable on the
// very next mission, and the trace will show the search when it happens.

type Props = {
  token: string;
  agent: StudioAgent;
  limits: DocumentLimits | undefined;
  readOnly: boolean;
  topicHint: string;
  onChanged: () => void;
};

type Pending = { key: string; name: string; state: "uploading" | "failed"; error?: string };

function kb(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${Math.round(bytes / 1024)} KB`;
}

export default function AgentKnowledge({ token, agent, limits, readOnly, topicHint, onChanged }: Props) {
  const [docs, setDocs] = useState<StudioDocument[] | null>(null);
  const [pending, setPending] = useState<Pending[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [drag, setDrag] = useState(false);
  const [pasteOpen, setPasteOpen] = useState(false);
  const [pasteTitle, setPasteTitle] = useState("");
  const [pasteText, setPasteText] = useState("");
  const inputRef = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async () => {
    const res = await studio.listDocuments(token, agent.id);
    if (res.ok) setDocs(res.value);
    else setDocs([]);
  }, [token, agent.id]);

  useEffect(() => {
    setDocs(null);
    setPending([]);
    setError(null);
    void load();
  }, [load]);

  const available = !!limits?.available;
  const maxDocs = limits?.maxDocs ?? 3;
  const maxBytes = limits?.maxBytes ?? 409600;
  const full = (docs?.length ?? 0) + pending.filter((p) => p.state === "uploading").length >= maxDocs;
  const disabled = readOnly || !available || full;

  const uploadFiles = async (files: File[]) => {
    setError(null);
    let room = maxDocs - (docs?.length ?? 0);
    for (const file of files) {
      if (room <= 0) {
        setError(`Each sandbox agent can hold ${maxDocs} documents. Remove one to add another.`);
        break;
      }
      if (file.size > maxBytes) {
        setError(`${file.name} is over the ${kb(maxBytes)} sandbox limit. Sign up for the full knowledge base.`);
        continue;
      }
      const key = `${file.name}-${Date.now()}`;
      setPending((p) => [...p, { key, name: file.name, state: "uploading" }]);
      const res = await studio.uploadDocument(token, agent.id, file);
      if (res.ok) {
        room -= 1;
        setPending((p) => p.filter((x) => x.key !== key));
        setDocs((d) => [...(d ?? []), res.value]);
        onChanged();
      } else {
        setPending((p) => p.map((x) => (x.key === key ? { ...x, state: "failed", error: res.error.message } : x)));
      }
    }
  };

  const addText = async (title: string, text: string) => {
    setError(null);
    const key = `text-${Date.now()}`;
    setPending((p) => [...p, { key, name: title, state: "uploading" }]);
    const res = await studio.uploadText(token, agent.id, title, text);
    if (res.ok) {
      setPending((p) => p.filter((x) => x.key !== key));
      setDocs((d) => [...(d ?? []), res.value]);
      setPasteOpen(false);
      setPasteText("");
      setPasteTitle("");
      onChanged();
    } else {
      setPending((p) => p.map((x) => (x.key === key ? { ...x, state: "failed", error: res.error.message } : x)));
    }
  };

  const remove = async (id: string) => {
    const before = docs;
    setDocs((d) => (d ?? []).filter((x) => x.id !== id));
    const res = await studio.deleteDocument(token, agent.id, id);
    if (!res.ok) {
      setDocs(before);
      setError(res.error.message);
    }
  };

  if (limits && !limits.available) {
    return (
      <Alert>
        <AlertTriangle aria-hidden="true" />
        <AlertTitle>Document search isn&apos;t switched on here yet</AlertTitle>
        <AlertDescription>
          Everything else in the studio works. In your own workspace, documents are unlimited and searched exactly like
          this.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <div className="text-sm font-medium">Give it something to read</div>
        <p className="text-xs text-muted-foreground">
          Files are split, embedded and searched by <b className="font-medium text-foreground">{agent.name}</b> alone -
          no other agent can see them. Try {topicHint}.
        </p>
      </div>

      <div
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled) setDrag(true);
        }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDrag(false);
          if (!disabled) void uploadFiles(Array.from(e.dataTransfer.files));
        }}
        data-tour="dropzone"
        className={`relative rounded-2xl border-2 border-dashed p-6 text-center transition-colors ${
          drag ? "border-sky-500 bg-sky-500/10" : "border-sky-500/30 bg-sky-500/[0.03]"
        } ${disabled ? "opacity-60" : ""}`}
      >
        <UploadCloud
          aria-hidden="true"
          className={`mx-auto mb-2 h-8 w-8 ${drag ? "text-sky-600" : "text-sky-600 dark:text-sky-400"}`}
        />
        <div className="text-sm font-medium">{drag ? "Drop to add" : "Drag a document here"}</div>
        <p className="mb-3 text-xs text-muted-foreground">
          {(limits?.extensions ?? [".md", ".txt", ".csv"]).join("  ")} · up to {kb(maxBytes)} · {maxDocs} per agent
        </p>
        <div className="flex flex-wrap justify-center gap-2">
          <Button size="sm" className={TONES.sky.cta} disabled={disabled} onClick={() => inputRef.current?.click()}>
            <UploadCloud aria-hidden="true" className="mr-1.5 h-3.5 w-3.5 shrink-0" /> Choose files
          </Button>
          <Button size="sm" variant="outline" disabled={disabled} onClick={() => setPasteOpen((v) => !v)}>
            <ClipboardPaste aria-hidden="true" className="mr-1.5 h-3.5 w-3.5 shrink-0" /> Paste notes
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={disabled}
            onClick={() => {
              const s = sampleDocument(agent.name.replace(/ agent$/i, ""));
              void addText(s.title, s.text);
            }}
          >
            <FlaskConical aria-hidden="true" className="mr-1.5 h-3.5 w-3.5 shrink-0" /> Try a sample
          </Button>
        </div>
        <input
          ref={inputRef}
          type="file"
          multiple
          hidden
          accept={(limits?.extensions ?? [".md", ".txt", ".csv"]).join(",")}
          onChange={(e) => {
            const files = Array.from(e.target.files ?? []);
            e.target.value = "";
            if (files.length) void uploadFiles(files);
          }}
        />
      </div>

      {pasteOpen && (
        <div className="space-y-2 rounded-xl border border-border/60 p-3">
          <Input
            value={pasteTitle}
            maxLength={80}
            placeholder="Title, e.g. Refund policy"
            onChange={(e) => setPasteTitle(e.target.value)}
          />
          <Textarea
            value={pasteText}
            rows={6}
            maxLength={100000}
            placeholder="Paste anything the agent should know - policies, FAQs, meeting notes…"
            onChange={(e) => setPasteText(e.target.value)}
          />
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={!pasteText.trim() || disabled}
              onClick={() => void addText(pasteTitle.trim() || "Pasted notes", pasteText)}
            >
              Add to knowledge
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setPasteOpen(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}

      {error && <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-2.5 text-sm">{error}</div>}
      {full && !readOnly && (
        <p className="text-xs text-muted-foreground">
          This agent is holding {maxDocs} documents - remove one to add another.
        </p>
      )}

      <ul className="space-y-2">
        {docs === null && <li className="h-12 animate-pulse rounded-xl bg-muted/50" />}
        {pending.map((p) => (
          <li key={p.key} className="flex items-center gap-3 rounded-xl border border-border/60 p-3 text-sm">
            {p.state === "uploading" ? (
              <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin text-muted-foreground shrink-0" />
            ) : (
              <AlertTriangle aria-hidden="true" className="h-4 w-4 text-destructive shrink-0" />
            )}
            <div className="min-w-0 flex-1">
              <div className="truncate font-medium">{p.name}</div>
              <div className="text-xs text-muted-foreground">
                {p.state === "uploading" ? "Reading, splitting and embedding…" : p.error}
              </div>
            </div>
            {p.state === "failed" && (
              <Button size="sm" variant="ghost" onClick={() => setPending((x) => x.filter((y) => y.key !== p.key))}>
                Dismiss
              </Button>
            )}
          </li>
        ))}
        {(docs ?? []).map((d) => (
          <li key={d.id} className="flex items-center gap-3 rounded-xl border border-border/60 p-3 text-sm">
            <FileText aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <div className="truncate font-medium">{d.name}</div>
              <div className="text-xs text-muted-foreground">
                {kb(d.sizeBytes)}
                {d.chunks != null ? ` · ${d.chunks} searchable passages` : ""}
              </div>
            </div>
            {d.status === "READY" ? (
              <Badge variant="secondary">
                <CheckCircle2 aria-hidden="true" /> searchable
              </Badge>
            ) : d.status === "FAILED" ? (
              <Badge variant="destructive">failed</Badge>
            ) : (
              <Badge variant="outline">
                <Loader2 aria-hidden="true" className="animate-spin" /> {d.status.toLowerCase()}
              </Badge>
            )}
            {!readOnly && (
              <button
                type="button"
                onClick={() => void remove(d.id)}
                aria-label={`Remove ${d.name}`}
                className="text-muted-foreground transition-colors hover:text-destructive"
              >
                <Trash2 aria-hidden="true" className="h-4 w-4 shrink-0" />
              </button>
            )}
          </li>
        ))}
        {docs !== null && docs.length === 0 && pending.length === 0 && (
          <li className="rounded-xl border border-dashed border-border/60 p-4 text-center text-xs text-muted-foreground">
            No documents yet. This agent will answer from the web and its own reasoning until you add some.
          </li>
        )}
      </ul>

      <p className="flex items-start gap-1.5 text-[11px] leading-relaxed text-muted-foreground">
        <ShieldCheck aria-hidden="true" className="mt-0.5 h-3 w-3 shrink-0" /> Deleted with the agent, and when the
        sandbox ends. Don&apos;t upload anything confidential.
      </p>
    </div>
  );
}
