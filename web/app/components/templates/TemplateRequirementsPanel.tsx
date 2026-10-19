"use client";

import Link from "next/link";
import { CheckCircle2, PlugZap } from "lucide-react";
import type { TemplateRequirement } from "../../lib/api";
import { Card, CardContent } from "../ui/card";

// "Needs setup": what an importer has to connect or switch on for the template to work as designed.
// Derived server-side from the config (TemplateReadinessService#requirements), so platform and community
// templates both show it. Built-in capabilities are listed without a link so nobody hunts for a switch.
export default function TemplateRequirementsPanel({
  requirements,
  className,
}: {
  requirements: TemplateRequirement[] | undefined;
  className?: string;
}) {
  if (!requirements) return null;
  const required = requirements.filter((r) => r.required);
  const optional = requirements.filter((r) => !r.required);

  if (requirements.length === 0) {
    return (
      <Card elevation="base" className={`gap-2 py-5 ${className ?? ""}`}>
        <CardContent className="space-y-1.5">
          <p className="flex items-center gap-2 text-sm font-medium text-foreground">
            <CheckCircle2
              size={15}
              strokeWidth={2}
              aria-hidden
              style={{ color: "var(--state-success, currentColor)" }}
            />
            Nothing to set up
          </p>
          <p className="text-sm text-muted-foreground">
            This template needs no connections or tools - it works as soon as you import it.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card elevation="base" className={`gap-3 py-5 ${className ?? ""}`} data-testid="template-requirements">
      <CardContent className="space-y-4">
        <p className="flex items-center gap-2 text-sm font-medium text-foreground">
          <PlugZap size={15} strokeWidth={2} aria-hidden />
          Needs setup
        </p>
        <p className="text-sm text-muted-foreground">
          After importing, activate these so the agent can do everything it was designed to do.
        </p>
        {required.length > 0 && <RequirementList title="Required" items={required} />}
        {optional.length > 0 && <RequirementList title="Good to know" items={optional} />}
      </CardContent>
    </Card>
  );
}

function RequirementList({ title, items }: { title: string; items: TemplateRequirement[] }) {
  return (
    <div className="space-y-2">
      <p className="text-[0.6875rem] font-semibold uppercase tracking-[0.12em] text-muted-foreground">{title}</p>
      <ul className="space-y-2">
        {items.map((r) => (
          <li
            key={`${r.type}:${r.id ?? ""}`}
            className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 rounded-lg border border-border/70 px-3 py-2.5"
          >
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-foreground">{r.label}</p>
              <p className="text-sm text-muted-foreground">{r.detail}</p>
            </div>
            {r.setupPath ? (
              <Link href={r.setupPath} className="shrink-0 text-sm font-medium text-primary hover:underline">
                Set up
              </Link>
            ) : (
              <span className="shrink-0 text-xs text-muted-foreground">Built in</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
