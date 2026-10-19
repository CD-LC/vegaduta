import { redirect } from "next/navigation";
import { assistantHref } from "../components/assistant/agentSelection";
import { firstParam, type SearchParams } from "../lib/searchParams";
import { isPersonalInstall } from "../lib/personalInstall";
import PlaygroundLegacy from "./PlaygroundLegacy";

/**
 * On a personal (one-person, local-auth) install the Playground is the Assistant's agent picker
 * (2026-10-18), so this route redirects there with ?agent= / ?prompt= carried over. Every other
 * install keeps the Playground page as it was.
 */
export default async function PlaygroundPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  if (!isPersonalInstall()) return <PlaygroundLegacy />;
  const sp = await searchParams;
  redirect(assistantHref({ agentId: firstParam(sp.agent), prompt: firstParam(sp.prompt), from: "playground" }));
}
