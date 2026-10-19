// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).
import { useEffect, useRef, useState } from "react";
import { Loader2, Paperclip, Palette, Archive } from "lucide-react";
import GenerateImageDialog from "./GenerateImageDialog";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../components/ui/alert-dialog";
import { appBuilder } from "./lib";
import { sanitizeTypedToken, validTokenValue } from "./tokenValue";
import { BUILD_STRINGS as S } from "./buildStrings";
import { uploadAssets } from "./uploadAssets";

/* ------------------------------------------------------------------ */
/* Design panel — assets strip + design-token editor. Token edits write  */
/* src/theme.css via the apply endpoint directly (deterministic file     */
/* write, no model round-trip — free and instant).                        */

const TOKEN_FIELDS = [
  { key: "--brand", label: "Accent", type: "color" },
  { key: "--brand-ink", label: "Accent text", type: "color" },
  { key: "--bg", label: "Background", type: "color" },
  { key: "--ink", label: "Text", type: "color" },
  { key: "--surface", label: "Cards", type: "color" },
  { key: "--line", label: "Borders", type: "color" },
  { key: "--radius", label: "Rounding", type: "text" },
];

function readToken(css, key) {
  const m = css.match(new RegExp(`${key.replace(/[-]/g, "\\-")}\\s*:\\s*([^;]+);`));
  return m ? m[1].trim() : "";
}

export default function DesignPanel({ project, fileSet, onFiles, assets, onAssetsChanged, onError, onUseAsset, disabled }: any) {
  const [busy, setBusy] = useState(false);
  const assetFileRef = useRef(null);
  const [assetPendingDelete, setAssetPendingDelete] = useState(null);
  const [deletingAsset, setDeletingAsset] = useState(false);

  const themeFile = (fileSet || []).find((f) => /theme\.css$/.test(f.path));
  const tokens = {};
  if (themeFile) {
    for (const f of TOKEN_FIELDS) tokens[f.key] = readToken(themeFile.content, f.key);
  }

  // Token edits stage locally, then flush as ONE applyEdits call. A colour
  // picker fires onChange on every drag step (and the rounding field on every
  // keystroke): the old apply-per-change version spammed the server AND
  // silently dropped the final value whenever an earlier write was still in
  // flight (the `if (busy) return` guard). Staging fixes both - the inputs
  // update instantly from `pending`, and 600ms after the last change every
  // pending token commits at once. While a write is in flight the flush
  // waits, so edits apply in order on top of the latest file content instead
  // of racing last-write-wins.
  const [pending, setPending] = useState({});
  const pendingRef = useRef({});
  const busyRef = useRef(false);
  const timerRef = useRef(null);
  const themeRef = useRef({ path: "", content: "" });
  themeRef.current = { path: themeFile?.path || "", content: themeFile?.content || "" };

  // Which project the staged edits belong to, so a flush that fires after the
  // project changed can never write A's tokens into B's theme file.
  const pendingProjectRef = useRef(project.project_id);
  useEffect(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    pendingRef.current = {};
    setPending({});
    pendingProjectRef.current = project.project_id;
  }, [project.project_id]);

  const flushTokens = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (busyRef.current) {
      timerRef.current = setTimeout(flushTokens, 400);
      return;
    }
    if (pendingProjectRef.current !== project.project_id) {
      pendingRef.current = {};
      setPending({});
      return;
    }
    // Only complete, safe values are written (hex colours; digits + unit for
    // rounding). Half-typed ones stay staged so the user can finish typing.
    const staged = pendingRef.current;
    const edits = {};
    const incomplete = {};
    for (const [key, value] of Object.entries(staged)) {
      const ok = validTokenValue(key, value);
      if (ok) edits[key] = ok;
      else incomplete[key] = value;
    }
    pendingRef.current = incomplete;
    setPending(incomplete);
    const { path, content } = themeRef.current;
    if (!path || !Object.keys(edits).length) return;
    let next = content;
    for (const [key, value] of Object.entries(edits)) {
      next = next.replace(
        new RegExp(`(${key.replace(/[-]/g, "\\-")}\\s*:\\s*)[^;]+;`),
        `$1${value};`
      );
    }
    if (next === content) return;
    busyRef.current = true;
    setBusy(true);
    appBuilder
      .applyEdits(project.project_id, {
        instruction: `Design token change: ${Object.entries(edits)
          .map(([k, v]) => `${k} = ${v}`)
          .join(", ")}`,
        files: [{ path, content: next }],
      })
      .then((r) => {
        onFiles?.(r?.files || [{ path, content: next }]);
      })
      .catch((e) => onError(e.message))
      .finally(() => {
        busyRef.current = false;
        setBusy(false);
      });
  };

  const applyToken = (key, rawValue) => {
    if (!themeFile || disabled) return;
    const value = sanitizeTypedToken(key, rawValue);
    pendingProjectRef.current = project.project_id;
    pendingRef.current = { ...pendingRef.current, [key]: value };
    setPending(pendingRef.current);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(flushTokens, 600);
  };

  const uploadAsset = (fileList) =>
    disabled ? Promise.resolve() : uploadAssets(project.project_id, fileList, onError, onAssetsChanged);

  return (
    <Card className="h-[min(560px,85dvh)] overflow-auto">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Palette aria-hidden="true" size={14} className="text-primary" /> Design
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {disabled && (
          <p
            role="status"
            className="flex items-start gap-1.5 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-xs text-muted-foreground"
          >
            <Archive size={13} className="mt-0.5 shrink-0" aria-hidden="true" /> This project is archived - restore it
            to edit the theme or add assets.
          </p>
        )}
        {/* Brand tokens — deterministic edits, no AI needed. */}
        <section>
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Theme
          </h3>
          {themeFile ? (
            <div className="grid grid-cols-2 gap-3">
              {TOKEN_FIELDS.map((f) => {
                const shown = pending[f.key] ?? tokens[f.key];
                return (
                  <label key={f.key} className="flex items-center gap-2 text-xs">
                    {f.type === "color" ? (
                      <input
                        type="color"
                        className="h-7 w-9 shrink-0 cursor-pointer rounded border border-border bg-transparent p-0.5"
                        value={/^#[0-9a-fA-F]{6}$/.test(shown) ? shown : "#888888"}
                        disabled={disabled}
                        onChange={(e) => applyToken(f.key, e.target.value)}
                      />
                    ) : (
                      <Input
                        className="h-7 w-16 shrink-0 px-1 text-xs"
                        value={shown ?? ""}
                        inputMode="decimal"
                        disabled={disabled}
                        onChange={(e) => applyToken(f.key, e.target.value)}
                      />
                    )}
                    <span className="truncate text-foreground">{f.label}</span>
                  </label>
                );
              })}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              This template has no <code className="font-mono">src/theme.css</code> yet — ask the builder to
              &quot;set up a theme file&quot; and this panel will light up.
            </p>
          )}
          {busy && (
            <p className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
              <Loader2 aria-hidden="true" size={12} className="animate-spin" /> Committing token change…
            </p>
          )}
        </section>

        {/* Asset strip — uploads land under assets/ on the branch. */}
        <section>
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Assets <span className="font-normal normal-case">(in assets/ on your branch)</span>
          </h3>
          <input
            ref={assetFileRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              void uploadAsset(e.target.files);
              e.target.value = "";
            }}
          />
          <div className="flex flex-wrap gap-2">
            {(assets || []).map((a) => (
              <span
                key={a.path}
                className="group inline-flex items-center gap-1.5 rounded-lg border border-border bg-muted/40 px-2 py-1 text-xs text-foreground"
              >
                {a.path}
                <button
                  type="button"
                  className="border-0 bg-transparent p-0 text-muted-foreground opacity-0 shadow-none transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 focus-visible:opacity-100 hover:text-destructive"
                  aria-label={S.deleteAssetLabel(a.path)}
                  disabled={disabled}
                  onClick={() => setAssetPendingDelete(a)}
                >
                  ×
                </button>
              </span>
            ))}
            <Button
              variant="outline"
              size="sm"
              className="h-7 gap-1.5 text-xs"
              disabled={disabled}
              onClick={() => assetFileRef.current?.click()}
            >
              <Paperclip aria-hidden="true" size={12} /> Upload
            </Button>
            <GenerateImageDialog
              disabled={disabled}
              projectId={project.project_id}
              onGenerated={onAssetsChanged}
              onUseInApp={onUseAsset}
            />
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Upload a logo, font, or photo — then just say &quot;use my logo in the header&quot; in the chat.
          </p>
        </section>
      </CardContent>

      <AlertDialog open={!!assetPendingDelete} onOpenChange={(open) => !open && setAssetPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{S.deleteAssetTitle(assetPendingDelete?.path ?? "")}</AlertDialogTitle>
            <AlertDialogDescription>{S.deleteAssetBody}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingAsset}>{S.cancel}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deletingAsset}
              onClick={(e) => {
                e.preventDefault();
                if (!assetPendingDelete) return;
                setDeletingAsset(true);
                appBuilder
                  .deleteAsset(project.project_id, assetPendingDelete.path)
                  .then(() => onAssetsChanged?.())
                  .catch((err) => onError(err.message))
                  .finally(() => {
                    setDeletingAsset(false);
                    setAssetPendingDelete(null);
                  });
              }}
            >
              {deletingAsset ? S.deleting : S.deleteAsset}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
