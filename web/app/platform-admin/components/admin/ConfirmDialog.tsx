"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../../components/ui/alert-dialog";
import { Textarea } from "../ui/textarea";

// Accessible replacement for window.confirm()/window.prompt() across the
// platform-admin console. Mount <ConfirmProvider> once (layout.tsx) and call
// `const confirm = useConfirm()` in any component:
//
//   if (!(await confirm({ title: "Suspend jane?", description: "...", destructive: true }))) return;
//   const r = await confirm({ title: "Stop tenant", reason: { label: "Reason", required: false } });
//   if (!r) return;  r.reason -> the typed text
//
// A plain string is shorthand for { title: "Are you sure?", description }.
// Resolves `null` on cancel, `{ reason }` on confirm. Outside a provider
// (isolated unit tests) it resolves null (cancel) so behaviour is
// stays safe.

export interface ConfirmOptions {
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  /** Show a reason textarea (replaces window.prompt). */
  reason?: { label?: string; placeholder?: string; required?: boolean };
}

export type ConfirmResult = { reason: string } | null;
type ConfirmFn = (opts: ConfirmOptions | string) => Promise<ConfirmResult>;

function normalise(o: ConfirmOptions | string): ConfirmOptions {
  return typeof o === "string" ? { title: "Are you sure?", description: o } : o;
}

const ConfirmContext = createContext<ConfirmFn | null>(null);

// Outside a provider (isolated unit tests) fail closed: nothing is confirmed.
function noProvider(): Promise<ConfirmResult> {
  return Promise.resolve(null);
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [opts, setOpts] = useState<ConfirmOptions | null>(null);
  const [reason, setReason] = useState("");
  const resolver = useRef<((r: ConfirmResult) => void) | null>(null);

  const confirm = useCallback<ConfirmFn>((o) => {
    // A second request while one is open cancels the first.
    resolver.current?.(null);
    setReason("");
    setOpts(normalise(o));
    return new Promise<ConfirmResult>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const settle = useCallback((r: ConfirmResult) => {
    resolver.current?.(r);
    resolver.current = null;
    setOpts(null);
  }, []);

  const value = useMemo(() => confirm, [confirm]);
  const needsReason = !!opts?.reason?.required;
  const blocked = needsReason && reason.trim().length === 0;

  return (
    <ConfirmContext.Provider value={value}>
      {children}
      <AlertDialog open={opts !== null} onOpenChange={(open) => !open && settle(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{opts?.title}</AlertDialogTitle>
            {opts?.description ? <AlertDialogDescription>{opts.description}</AlertDialogDescription> : null}
          </AlertDialogHeader>
          {opts?.reason ? (
            <div className="space-y-1.5">
              <label htmlFor="confirm-reason" className="text-sm font-medium text-foreground">
                {opts.reason.label ?? "Reason"}
                {opts.reason.required ? null : <span className="text-muted-foreground"> (optional)</span>}
              </label>
              <Textarea
                id="confirm-reason"
                value={reason}
                rows={3}
                maxLength={500}
                placeholder={opts.reason.placeholder}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel>{opts?.cancelLabel ?? "Cancel"}</AlertDialogCancel>
            <AlertDialogAction
              disabled={blocked}
              onClick={(e) => {
                e.preventDefault();
                if (blocked) return;
                settle({ reason: reason.trim() });
              }}
              className={
                opts?.destructive ? "bg-destructive text-destructive-foreground hover:bg-destructive/90" : undefined
              }
            >
              {opts?.confirmLabel ?? (opts?.destructive ? "Confirm" : "Continue")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  return useContext(ConfirmContext) ?? noProvider;
}
