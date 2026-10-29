"use client";

import { useState } from "react";
import { ShieldCheck, Fingerprint, Timer, Gauge, Ban, Sparkles, Ghost } from "lucide-react";
import { Button } from "../ui/button";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";

// The pledge: shown once, before the studio does anything. The sandbox is
// unlimited and anonymous, which is exactly why it needs a front door. It says,
// in plain words, what is allowed, what is not, that the visitor's network
// address is recorded with their sandbox (and shows it to them), and what the
// gateways are. The address is displayed, not hidden - a rule people can see
// they are being held to is one they are far more likely to keep.

export const PLEDGE_KEY = "vegaduta.studioPledge.v1";

export function pledgeAccepted(): boolean {
  try {
    return window.localStorage.getItem(PLEDGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function rememberPledge(): void {
  try {
    window.localStorage.setItem(PLEDGE_KEY, "1");
  } catch {
    // Private windows can refuse storage - the pledge simply shows again next visit.
  }
}

type Props = {
  open: boolean;
  ip: string | null;
  missionSeconds: number;
  runsPerMinute: number;
  onAccept: () => void;
};

export default function StudioPledge({ open, ip, missionSeconds, runsPerMinute, onAccept }: Props) {
  const [agree, setAgree] = useState(false);
  return (
    <AlertDialog open={open}>
      <AlertDialogContent className="max-w-xl">
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2">
            <ShieldCheck aria-hidden="true" className="h-5 w-5 text-primary shrink-0" /> The studio is open to everyone.
            Keep it that way.
          </AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-4 text-start text-sm">
              <p>
                No signup, no card and no message allowance - build as many agents and run as many missions as you like.
                In return, please refrain from any illegal or abusive activity.
              </p>

              <div className="flex items-start gap-3 rounded-xl border border-border/60 bg-muted/40 p-3">
                <Fingerprint aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                <div>
                  <div className="font-medium text-foreground">Your network address is recorded with this sandbox</div>
                  <div className="mt-0.5 font-mono text-xs text-foreground">{ip ?? "recorded on our side"}</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    Every run is logged against it. Misuse can be traced, blocked and - where the law requires -
                    reported.
                  </div>
                </div>
              </div>

              <ul className="grid gap-2 sm:grid-cols-2">
                <Rule
                  icon={Ban}
                  title="No illegal use"
                  body="Nothing that helps fraud, harm, harassment, or anything unlawful where you are."
                />
                <Rule
                  icon={Ghost}
                  title="No abuse of the service"
                  body="No scraping at scale, no bots, no trying to jailbreak the agents or the limits."
                />
                <Rule
                  icon={Sparkles}
                  title="Nothing private"
                  body="Don't paste passwords, card numbers or other people's personal data."
                />
                <Rule
                  icon={Gauge}
                  title="Fair use, not a quota"
                  body={`Up to ${runsPerMinute} runs a minute; repeated refusals by the safety rules pause your sandbox for a while.`}
                />
              </ul>

              <div className="flex items-center gap-2 rounded-lg bg-muted/40 p-2.5 text-xs text-muted-foreground">
                <Timer aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
                Every run has a {missionSeconds}-second time cap and is stopped when it hits it.
              </div>

              <label className="flex cursor-pointer items-start gap-2 text-sm text-foreground">
                <input type="checkbox" className="mt-1" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
                <span>I&apos;ll use the studio for legitimate work only, and I understand my address is recorded.</span>
              </label>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <Button disabled={!agree} onClick={onAccept}>
            I agree - open the studio
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function Rule({ icon: Icon, title, body }: { icon: typeof Ban; title: string; body: string }) {
  return (
    <li className="rounded-xl border border-border/60 p-2.5">
      <div className="mb-0.5 flex items-center gap-1.5 text-sm font-medium text-foreground">
        <Icon className="h-3.5 w-3.5" /> {title}
      </div>
      <div className="text-xs text-muted-foreground">{body}</div>
    </li>
  );
}
