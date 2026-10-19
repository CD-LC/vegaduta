import { redirect } from "next/navigation";

// Old branded URL kept only as a redirect - the console lives on the generic
// capability surface at /capabilities/decision.
export default function JevCompatibilityPage() {
  redirect("/capabilities/decision");
}
