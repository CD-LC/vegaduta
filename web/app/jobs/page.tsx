import { redirect } from "next/navigation";
import { assistantHref } from "../components/assistant/agentSelection";
import { firstParam, type SearchParams } from "../lib/searchParams";
import { isPersonalInstall } from "../lib/personalInstall";
import JobsLegacy from "./JobsLegacy";

/**
 * On a personal install the Jobs launcher is the Assistant's "In background" mode and its Work
 * rail (2026-10-18), so this route redirects there; every other install keeps the Jobs page as
 * it was. The per-job page, /jobs/all and /jobs/schedules stay on both.
 */
export default async function JobsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  if (!isPersonalInstall()) return <JobsLegacy />;
  const sp = await searchParams;
  redirect(assistantHref({ agentId: firstParam(sp.agentId), mode: "background", from: "jobs" }));
}
