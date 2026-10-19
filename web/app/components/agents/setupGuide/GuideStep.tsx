import type { ReactNode } from "react";
import Link from "next/link";
import { type BookOpen, CheckCircle2, Circle, CircleHelp, ExternalLink } from "lucide-react";
import type { Tri } from "./workspaceSnapshot";

function StatusIcon({ status }: { status: Tri }) {
  if (status === "done") return <CheckCircle2 size={15} className="shrink-0 text-primary" aria-hidden="true" />;
  if (status === "todo") return <Circle size={15} className="shrink-0 text-muted-foreground" aria-hidden="true" />;
  return <CircleHelp size={15} className="shrink-0 text-muted-foreground" aria-hidden="true" />;
}

export interface StepProps {
  index: number;
  last: boolean;
  icon: typeof BookOpen;
  title: string;
  status: Tri;
  statusLabel: string;
  body: string;
  notes?: Array<string | null | undefined>;
  href?: string;
  linkLabel?: string;
  children?: ReactNode;
}

/** One numbered row of the setup checklist: icon rail, title, status, body, notes and an optional link. */
export default function Step({
  index,
  last,
  icon: Icon,
  title,
  status,
  statusLabel,
  body,
  notes,
  href,
  linkLabel,
  children,
}: StepProps) {
  return (
    <li className="flex gap-3.5">
      <div className="relative flex flex-col items-center">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Icon size={17} strokeWidth={2.1} />
        </span>
        {!last && <span className="mt-1 w-px flex-1 bg-border" aria-hidden="true" />}
      </div>
      <div className="min-w-0 flex-1 pb-6">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[11px] font-semibold tabular-nums text-muted-foreground">
            {String(index).padStart(2, "0")}
          </span>
          <span className="text-sm font-semibold text-foreground">{title}</span>
          <span className="inline-flex items-center gap-1 text-[11px] font-medium text-muted-foreground">
            <StatusIcon status={status} />
            {statusLabel}
          </span>
        </div>
        <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{body}</p>
        {(notes ?? [])
          .filter((n): n is string => Boolean(n))
          .map((note) => (
            <p key={note} className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
              {note}
            </p>
          ))}
        {children}
        {href && linkLabel && (
          <Link
            href={href}
            className="mt-2.5 inline-flex items-center gap-1.5 text-xs font-medium text-primary underline-offset-4 hover:underline"
          >
            {linkLabel}
            <ExternalLink size={12} aria-hidden="true" />
          </Link>
        )}
      </div>
    </li>
  );
}
