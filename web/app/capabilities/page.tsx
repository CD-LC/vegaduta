import { redirect } from "next/navigation";

// /capabilities has no index of its own; the only capability surface today is
// the decision console, so the bare path lands there instead of a 404.
export default function CapabilitiesIndexPage() {
  redirect("/capabilities/decision");
}
