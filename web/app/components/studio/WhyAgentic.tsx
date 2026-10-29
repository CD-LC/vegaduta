"use client";

import {
  Brain,
  Hand,
  FileText,
  Plug,
  Gauge,
  ShieldCheck,
  SlidersHorizontal,
  NotebookPen,
  MessageSquareOff,
  Bot,
  Repeat,
  ScanSearch,
  Workflow,
  Radio,
} from "lucide-react";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../ui/accordion";
import { useTranslations } from "../../lib/i18n";

// The "why": what an agent is, what it is for, and - the part that matters most
// on this page - the handful of dials that make it precise. Plain claims only:
// no invented statistics, no promises about results. Every dial named here is
// a real control in the designer below the fold; the label says where.

const DIALS: { icon: typeof Brain; name: string; where: string; controls: string; turnUp: string; turnDown: string }[] =
  [
    {
      icon: NotebookPen,
      name: "Brief",
      where: "Build > Brief",
      controls: "The standing instructions: the job, the reader, the boundaries. It is read before every task.",
      turnUp: "Add specifics - who it serves, what a good result looks like, what it must never do.",
      turnDown: "Trim it if it starts ignoring parts; a shorter, sharper brief is followed more closely.",
    },
    {
      icon: Hand,
      name: "Hands (tools)",
      where: "Build > Hands",
      controls: "What it can actually do: search the web, read a page, call an API, run code, remember.",
      turnUp: "Add a tool when the job needs fresh facts or exact maths.",
      turnDown: "Remove every tool and it becomes a chatbot - useful to see what the tools were adding.",
    },
    {
      icon: FileText,
      name: "Knowledge",
      where: "Build > Knowledge",
      controls: "Your own documents. It searches them and answers from them, and the trace shows the search.",
      turnUp: "Upload the policy, price list or contract the answers depend on.",
      turnDown: "Delete a document and it stops using it immediately.",
    },
    {
      icon: Plug,
      name: "Connectors (MCP)",
      where: "Build > Connectors",
      controls:
        "Tools published to the MCP registry - calendars, code hosts, maps, data services - plugged in per agent.",
      turnUp: "Connect one when the job lives in another system.",
      turnDown: "Remove it and the agent can no longer reach that system.",
    },
    {
      icon: Brain,
      name: "Thinking depth",
      where: "Build > Behavior",
      controls: "How much it plans and checks itself: fast, careful, rigorous or planned.",
      turnUp: "Rigorous when a wrong answer is expensive - money, contracts, health-adjacent topics.",
      turnDown: "Fast for drafts, chat and anything you will review anyway.",
    },
    {
      icon: Gauge,
      name: "Autonomy and approvals",
      where: "Build > Behavior, and Chain > Approval",
      controls: "How freely it acts. In a workflow, an approval step pauses the run until you say yes.",
      turnUp: "Let it run on its own for jobs you already trust.",
      turnDown: "Keep sensitive actions - sending, paying, publishing - behind an approval.",
    },
    {
      icon: ShieldCheck,
      name: "Rules (guardrails)",
      where: "Build > Behavior",
      controls: "Checks that run on every turn: personal data, toxic tone, policy violations, jailbreak attempts.",
      turnUp: "Switch on personal-data and tone checks for anything customer-facing.",
      turnDown: "The studio's own safety rules stay on regardless.",
    },
    {
      icon: SlidersHorizontal,
      name: "Creativity and length",
      where: "Build > Behavior",
      controls: "How varied the wording is, and how long a reply may run.",
      turnUp: "Higher creativity for names, copy and brainstorming.",
      turnDown: "Zero for anything that must read the same every time.",
    },
  ];

export default function WhyAgentic() {
    const tHC = useTranslations("hcComponentsStudioWhyAgentic");
  return (
    <section aria-labelledby="why-agentic" className="space-y-6 rounded-2xl border border-border/60 bg-muted/20 p-5">
      <div>
        <h3 id="why-agentic" className="text-lg font-semibold tracking-tight">
          {tHC("hcComponentsStudioWhyAgentic.s19")}
        </h3>
        <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
          {tHC("hcComponentsStudioWhyAgentic.s18")}
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <div className="rounded-xl border border-border/60 bg-background/70 p-4">
          <div className="mb-1 flex items-center gap-2 text-sm font-semibold">
            <MessageSquareOff aria-hidden="true" className="h-4 w-4 shrink-0" /> {tHC("hcComponentsStudioWhyAgentic.s17")}
          </div>
          <ul className="space-y-1 text-sm text-muted-foreground">
            <li>{tHC("hcComponentsStudioWhyAgentic.s16")}</li>
            <li>{tHC("hcComponentsStudioWhyAgentic.s15")}</li>
            <li>{tHC("hcComponentsStudioWhyAgentic.s14")}</li>
          </ul>
        </div>
        <div className="rounded-xl border border-primary/40 bg-primary/5 p-4">
          <div className="mb-1 flex items-center gap-2 text-sm font-semibold">
            <Bot aria-hidden="true" className="h-4 w-4 shrink-0" /> {tHC("hcComponentsStudioWhyAgentic.s13")}
          </div>
          <ul className="space-y-1 text-sm text-muted-foreground">
            <li>{tHC("hcComponentsStudioWhyAgentic.s12")}</li>
            <li>{tHC("hcComponentsStudioWhyAgentic.s11")}</li>
            <li>{tHC("hcComponentsStudioWhyAgentic.s10")}</li>
          </ul>
        </div>
      </div>

      <div>
        <div className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {tHC("hcComponentsStudioWhyAgentic.s9")}
        </div>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
          <Use icon={Repeat} title={tHC("hcComponentsStudioWhyAgentic.s8")} body="The same research or check, every day, with sources." />
          <Use
            icon={ScanSearch}
            title={tHC("hcComponentsStudioWhyAgentic.s7")}
            body="Contracts, policies, manuals - answers with the line they came from."
          />
          <Use
            icon={Workflow}
            title={tHC("hcComponentsStudioWhyAgentic.s6")}
            body="Research, then draft, then wait for your approval, then send."
          />
          <Use
            icon={Radio}
            title={tHC("hcComponentsStudioWhyAgentic.s5")}
            body="Customer messages answered from your facts, escalated when unsure."
          />
        </div>
      </div>

      <div>
        <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {tHC("hcComponentsStudioWhyAgentic.s4")}
        </div>
        <p className="mb-2 text-sm text-muted-foreground">
          {tHC("hcComponentsStudioWhyAgentic.s3")}
        </p>
        <Accordion type="single" collapsible className="rounded-xl border border-border/60 bg-background/70 px-3">
          {DIALS.map((d) => {
            const Icon = d.icon;
            return (
              <AccordionItem key={d.name} value={d.name}>
                <AccordionTrigger>
                  <span className="flex items-center gap-2 text-sm">
                    <Icon className="h-4 w-4" /> {d.name}{" "}
                    <span className="text-xs font-normal text-muted-foreground">- {d.where}</span>
                  </span>
                </AccordionTrigger>
                <AccordionContent>
                  <div className="space-y-1.5 text-sm text-muted-foreground">
                    <p>
                      <b className="font-medium text-foreground">Controls:</b> {d.controls}
                    </p>
                    <p>
                      <b className="font-medium text-foreground">{tHC("hcComponentsStudioWhyAgentic.s2")}</b> {d.turnUp}
                    </p>
                    <p>
                      <b className="font-medium text-foreground">{tHC("hcComponentsStudioWhyAgentic.s1")}</b> {d.turnDown}
                    </p>
                  </div>
                </AccordionContent>
              </AccordionItem>
            );
          })}
        </Accordion>
      </div>
    </section>
  );
}

function Use({ icon: Icon, title, body }: { icon: typeof Repeat; title: string; body: string }) {
  return (
    <div className="rounded-xl border border-border/60 bg-background/70 p-3">
      <div className="mb-1 flex items-center gap-2 text-sm font-medium">
        <Icon className="h-4 w-4" /> {title}
      </div>
      <p className="text-xs text-muted-foreground">{body}</p>
    </div>
  );
}
