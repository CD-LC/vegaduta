"use client";

import { useEffect, useRef, useState } from "react";
import { Download, RotateCcw, Upload } from "lucide-react";
import { Button } from "../../components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { MAX_DATA_CHARS, dataSummary, dataToCsv, formatAppData, parseAppData, tabularKeys, type AppData } from "./dataTab";
import { useTranslations } from "../../lib/i18n";

function saveText(filename: string, text: string, mime: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

const fileSafe = (s: string) => s.replace(/[^a-z0-9-_ ]/gi, "").trim().replace(/\s+/g, "-").toLowerCase() || "app";

/** The app's saved data (what its localStorage holds in the preview): view,
 * edit as JSON, export JSON/CSV, import, reset. Applying reloads the preview
 * so the app starts from exactly this data. */
export function DataPanel({
  name,
  data,
  disabled,
  onApply,
}: {
  name: string;
  data: AppData;
  disabled?: boolean;
  onApply: (next: AppData) => void;
}) {
    const tHC = useTranslations("hcBuildStudioDataPanel");
  const [draft, setDraft] = useState(() => formatAppData(data));
  const [dirty, setDirty] = useState(false);
  const [msg, setMsg] = useState<{ tone: "ok" | "err"; text: string } | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const [csvKey, setCsvKey] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  // Follow the live data (the preview mirrors every save) unless the person is mid-edit.
  const formatted = formatAppData(data);
  useEffect(() => {
    if (!dirty) setDraft(formatted);
  }, [formatted, dirty]);

  const tables = tabularKeys(data);
  const { keys, bytes } = dataSummary(data);

  const apply = () => {
    const r = parseAppData(draft);
    if ("error" in r) return setMsg({ tone: "err", text: r.error });
    onApply(r.data);
    setDirty(false);
    setMsg({ tone: "ok", text: "Saved. The preview reloaded with this data." });
  };

  const onFile = async (f: File | undefined) => {
    if (!f) return;
    if (f.size > MAX_DATA_CHARS) return setMsg({ tone: "err", text: "That file is too large (limit about 200 KB)." });
    const text = await f.text();
    const r = parseAppData(text);
    if (fileRef.current) fileRef.current.value = "";
    if ("error" in r) return setMsg({ tone: "err", text: r.error });
    setDraft(formatAppData(r.data));
    setDirty(true);
    setMsg({ tone: "ok", text: `Loaded ${Object.keys(r.data).length} item(s) into the editor. Press Apply to use them.` });
  };

  return (
    <div className="flex flex-col gap-3">
      <div>
        <p className="text-sm font-semibold text-foreground">Data</p>
        <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
          {tHC("s12")} {keys} item{keys === 1 ? "" : "s"}, {Math.max(1, Math.round(bytes / 1024))} {tHC("s11")}
        </p>
      </div>
      <textarea
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value);
          setDirty(true);
        }}
        spellCheck={false}
        disabled={disabled}
        className="h-64 w-full resize-y rounded-lg border border-border bg-neutral-950 p-2 font-mono text-[11px] leading-snug text-neutral-100 disabled:opacity-60"
        aria-label={tHC("s10")}
      />
      {msg && (
        <p role={msg.tone === "err" ? "alert" : "status"} className={`text-xs ${msg.tone === "err" ? "text-destructive" : "text-emerald-600"}`}>
          {msg.text}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={!dirty || disabled} onClick={apply}>
          Apply
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={!dirty}
          onClick={() => {
            setDraft(formatted);
            setDirty(false);
            setMsg(null);
          }}
        >
          Discard
        </Button>
      </div>
      <div className="flex flex-col gap-2 border-t border-border pt-3">
        <p className="text-xs font-medium text-foreground">{tHC("s9")}</p>
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" variant="outline" className="gap-1.5" disabled={keys === 0} onClick={() => saveText(`${fileSafe(name)}-data.json`, formatted + "\n", "application/json")}>
            <Download aria-hidden="true" size={13} /> JSON
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="gap-1.5"
            disabled={keys === 0}
            onClick={() => saveText(`${fileSafe(name)}${csvKey ? "-" + fileSafe(csvKey) : "-data"}.csv`, dataToCsv(data, csvKey || undefined), "text/csv")}
          >
            <Download aria-hidden="true" size={13} /> CSV
          </Button>
          {tables.length > 0 && (
            <select value={csvKey} onChange={(e) => setCsvKey(e.target.value)} aria-label="Which data to export as CSV" className="rounded-md border border-border bg-background px-2 py-1.5 text-xs">
              <option value="">{tHC("s8")}</option>
              {tables.map((k) => (
                <option key={k} value={k}>
                  Table: {k}
                </option>
              ))}
            </select>
          )}
          <Button size="sm" variant="outline" className="gap-1.5" disabled={disabled} onClick={() => fileRef.current?.click()}>
            <Upload aria-hidden="true" size={13} /> {tHC("s7")}
          </Button>
          <input ref={fileRef} type="file" accept="application/json,.json" className="hidden" aria-label={tHC("s6")} onChange={(e) => void onFile(e.target.files?.[0])} />
        </div>
      </div>
      <div className="border-t border-border pt-3">
        <Button size="sm" variant="outline" className="gap-1.5 text-destructive" disabled={keys === 0 || disabled} onClick={() => setConfirmReset(true)}>
          <RotateCcw aria-hidden="true" size={13} /> {tHC("s1")}
        </Button>
      </div>
      <AlertDialog open={confirmReset} onOpenChange={setConfirmReset}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tHC("s5")}</AlertDialogTitle>
            <AlertDialogDescription>
              {tHC("s4")}{keys} item{keys === 1 ? "" : "s"}{tHC("s3")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tHC("s2")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => {
                onApply({});
                setDirty(false);
                setMsg({ tone: "ok", text: "Data reset. The preview reloaded." });
              }}
            >
              {tHC("s1")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
