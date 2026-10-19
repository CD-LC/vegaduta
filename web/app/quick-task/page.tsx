import { redirect } from "next/navigation";
import { assistantHref } from "../components/assistant/agentSelection";
import { firstParam, type SearchParams } from "../lib/searchParams";
import { isPersonalInstall } from "../lib/personalInstall";
import QuickTaskLegacy from "./QuickTaskLegacy";

/**
 * On a personal install Quick Task is the Assistant's "Quick task" mode (2026-10-18), so this
 * route redirects there; every other install keeps the page as it was. /quick-task/history stays.
 */
export default async function QuickTaskPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  if (!isPersonalInstall()) return <QuickTaskLegacy />;
  const sp = await searchParams;
  redirect(assistantHref({ mode: "task", prompt: firstParam(sp.prompt), from: "quickTask" }));
}
