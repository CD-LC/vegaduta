"use client";

import { motion } from "motion/react";
import {
  ArrowRight,
  BookOpen,
  Calculator,
  Globe,
  GraduationCap,
  HandCoins,
  Sprout,
  Store,
  UserSearch,
  Wheat,
  FileSearch,
  GitPullRequest,
  Plane,
  ShieldCheck,
  Send,
} from "lucide-react";
import { TONES } from "./studioTheme";

// "Jobs that sound like a hire, not a tool." Each card is a brief the visitor
// can drop into the launcher with one click - it only FILLS the text box, the
// same rule as the idea chips (the agent is still composed from what they
// choose to say). Every card says plainly what runs in this sandbox and what
// a workspace adds (schedules, sending, connecting live systems): no invented
// results, no ROI numbers.

type Card = {
  icon: typeof Globe;
  title: string;
  line: string;
  job: string;
  does: { icon: typeof Globe; text: string }[];
  here: string;
  workspace: string;
};

const CARDS: Card[] = [
  {
    icon: HandCoins,
    title: "The invoice chaser",
    line: "Finds who has not paid, writes to each in their own language, and waits for your nod.",
    job: "Every Monday, list my unpaid invoices older than 15 days and draft a polite reminder for each customer, in the customer's language. Never send anything without my approval.",
    does: [
      { icon: BookOpen, text: "reads your sheet" },
      { icon: Calculator, text: "does the date maths in code" },
      { icon: ShieldCheck, text: "asks first" },
    ],
    here: "Reads a pasted invoice list, drafts every reminder, pauses for approval.",
    workspace: "Runs on a schedule and sends over WhatsApp or email.",
  },
  {
    icon: FileSearch,
    title: "The contract reader",
    line: "Reads the agreement you would never finish, flags the clauses that could cost you.",
    job: "Explain my rental agreement in plain English, and flag any clause that could cost me money or that is unusual. Quote the clause each time.",
    does: [
      { icon: BookOpen, text: "answers from your pages" },
      { icon: ShieldCheck, text: "quotes the clause" },
    ],
    here: "Upload the text; every answer cites the passage it used.",
    workspace: "PDFs, Word files and scanned pages with OCR.",
  },
  {
    icon: Globe,
    title: "The price watcher",
    line: "Opens competitor pages and reports what changed - no dashboard, no scraper script.",
    job: "Check three competitor websites I give you and tell me which prices or offers changed, with the page each came from.",
    does: [
      { icon: Globe, text: "reads live pages" },
      { icon: Calculator, text: "compares in code" },
    ],
    here: "Give it URLs and a question; watch every page it opens.",
    workspace: "Runs every morning and messages you the changes.",
  },
  {
    icon: GraduationCap,
    title: "The Class 10 tutor",
    line: "Asks one question at a time, explains mistakes simply, on a phone, in a language the child speaks.",
    job: "Tutor my daughter in Class 10 science: ask one question at a time, explain mistakes simply, and reply in Hindi when she writes in Hindi.",
    does: [
      { icon: BookOpen, text: "uses her textbook notes" },
      { icon: Send, text: "chats on Telegram" },
    ],
    here: "Chat with it in the studio or pair it with Telegram.",
    workspace: "Reaches her on WhatsApp, with progress reports to you.",
  },
  {
    icon: Store,
    title: "The shop-front assistant",
    line: "Answers customers from your real price list and hands the awkward ones to you.",
    job: "Answer customer messages for my small shop from my price list and delivery rules. If someone wants a refund or a discount, do not decide - summarise and hand it to me.",
    does: [
      { icon: BookOpen, text: "answers from your price list" },
      { icon: ShieldCheck, text: "escalates the hard ones" },
    ],
    here: "Upload a price list, share the link, let five people try it.",
    workspace: "Lives on your WhatsApp number and Instagram.",
  },
  {
    icon: UserSearch,
    title: "The CV screener",
    line: "Reads a stack of CVs against your job description and ranks the top with one line of why.",
    job: "Read the CVs I give you against this job description and rank the top five, with one honest line on why each made the list and one concern.",
    does: [
      { icon: BookOpen, text: "reads each CV" },
      { icon: ShieldCheck, text: "shows its reasoning" },
    ],
    here: "Paste text CVs; get the ranked list and the reasoning.",
    workspace: "PDF CVs, an approval step before anyone is contacted.",
  },
  {
    icon: Wheat,
    title: "The mandi analyst",
    line: "Pulls the week's prices and tells a farmer which day is best to sell.",
    job: "Summarise this week's mandi prices for tomatoes and onions near me and tell me the best day to sell, showing the numbers you used.",
    does: [
      { icon: Globe, text: "searches live prices" },
      { icon: Calculator, text: "runs the numbers" },
    ],
    here: "Live search plus real code execution, with the working shown.",
    workspace: "A weekly report on a schedule, in the farmer's language.",
  },
  {
    icon: GitPullRequest,
    title: "The code-review buddy",
    line: "Reads your pull request before you do and points out bugs, missing tests, unclear names.",
    job: "Review my pull requests before I do: point out likely bugs, missing tests and unclear names, and rank them by how much they matter.",
    does: [
      { icon: Globe, text: "reads the diff page" },
      { icon: Calculator, text: "runs snippets in code" },
    ],
    here: "Give it a public PR link; watch it read and reason.",
    workspace: "Connects to your code host through an MCP server.",
  },
  {
    icon: Plane,
    title: "The trip planner",
    line: "Plans four days inside your budget, checks the weather, tells you what to pack.",
    job: "Plan a 4-day trip within my budget, check the weather for each day and suggest what to pack.",
    does: [
      { icon: Globe, text: "checks live weather" },
      { icon: Calculator, text: "keeps to your budget" },
    ],
    here: "Live search and budget maths, end to end.",
    workspace: "Books nothing without your approval - a step you add.",
  },
];

export default function Possibilities({ onPick }: { onPick: (job: string) => void }) {
  return (
    <section aria-labelledby="possible-title" className="mt-24">
      <div className="max-w-2xl">
        <div className="mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-emerald-700 dark:text-emerald-300">
          <Sprout aria-hidden="true" className="h-4 w-4 shrink-0" /> What people hand to agents
        </div>
        <h2 id="possible-title" className="text-3xl font-semibold tracking-tight sm:text-4xl">
          Jobs that used to need a hire. Pick one, or invent your own.
        </h2>
        <p className="mt-3 text-muted-foreground">
          Each card fills the launcher with a brief - you still decide every word before anything is built. The sandbox
          runs the thinking, reading and tool use for real; a free workspace adds the schedules and the sending.
        </p>
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {CARDS.map((c, i) => (
          <motion.article
            key={c.title}
            initial={{ opacity: 0, y: 18 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-40px" }}
            transition={{ delay: (i % 3) * 0.08, duration: 0.4 }}
            whileHover={{ y: -4 }}
            className="group flex flex-col rounded-2xl border border-border/60 bg-background/60 p-5 backdrop-blur transition-colors hover:border-violet-400/60"
          >
            <div className="mb-3 flex items-center gap-2.5">
              <span className={`inline-flex h-9 w-9 items-center justify-center rounded-xl ${TONES.violet.soft}`}>
                <c.icon className="h-[18px] w-[18px]" />
              </span>
              <h3 className="font-semibold">{c.title}</h3>
            </div>
            <p className="text-sm text-muted-foreground">{c.line}</p>
            <div className="mt-3 flex flex-wrap gap-1.5">
              {c.does.map((d) => (
                <span
                  key={d.text}
                  className="inline-flex items-center gap-1 rounded-full border border-border/60 px-2 py-0.5 text-[11px] text-muted-foreground"
                >
                  <d.icon className="h-3 w-3" /> {d.text}
                </span>
              ))}
            </div>
            <dl className="mt-3 space-y-1.5 text-xs">
              <div className="flex gap-2">
                <dt className="w-[74px] shrink-0 font-medium text-emerald-700 dark:text-emerald-300">In this page</dt>
                <dd className="text-muted-foreground">{c.here}</dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-[74px] shrink-0 font-medium text-violet-700 dark:text-violet-300">Workspace</dt>
                <dd className="text-muted-foreground">{c.workspace}</dd>
              </div>
            </dl>
            <button
              type="button"
              onClick={() => onPick(c.job)}
              className="mt-4 inline-flex items-center gap-1.5 self-start rounded-lg bg-violet-500/10 px-3 py-1.5 text-sm font-medium text-violet-700 transition-colors hover:bg-violet-600 hover:text-white dark:text-violet-300"
            >
              Build this one{" "}
              <ArrowRight
                aria-hidden="true"
                className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5 shrink-0"
              />
            </button>
          </motion.article>
        ))}
      </div>
    </section>
  );
}
