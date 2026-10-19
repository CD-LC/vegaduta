"use client";

import Link from "next/link";
import { ArrowRight, Coffee, Mail, MessageSquareText, CalendarClock, ShieldCheck } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE, COFFEE_URL, HAS_COFFEE_LINK } from "../../lib/freeMode";
import { sendLandingEvent } from "./landingBeacon";
import { ENTITY_FACTS } from "../../lib/platform-facts";

// ---------------------------------------------------------------------------
// ContactBand - "talk to a human" (2026-09-12).
//
// Direct instruction this answers: "where is the banner asking visitors to
// contact us?" There wasn't one. Before this band the entire human-contact
// surface of the landing page was:
//   1. a mailto inside BusinessCaseBand - band 14 of 16, roughly thirteen
//      viewports down, and phrased as a case-study offer rather than "get in
//      touch";
//   2. a "Contact" link in the FOOTER's Legal column, next to Terms and
//      Privacy, which reads as a legal notice address rather than an invitation;
//   3. a third mailto in AutomateAnythingSection.tsx that no visitor can ever
//      reach, because that component was unmounted in the 2026-08-14 content
//      pass. Its `automate_contact_click` beacon is still whitelisted in
//      core's LandingEventController with zero call sites - which is why the
//      funnel read-out shows a contact counter that has been frozen for weeks
//      and looks like "nobody wants to talk to us".
//
// WHY MAILTO AND NOT A FORM. There is no lead-capture endpoint in core - I
// grepped for Contact/Lead/Enquiry/Inquiry/SalesRequest/DemoRequest controllers
// and there are none, and /api/public/* carries only the landing-event beacon.
// A form here would either POST into nothing or need a new core endpoint,
// tenant-less persistence, spam protection and an inbox nobody has agreed to
// watch. BusinessCaseBand reached the same conclusion and said so in its own
// header comment. So: mailto, with the subject and body prefilled the way
// EarnLanding.tsx:338 already prefills a subject, which at least removes the
// blank-page problem for the visitor.
//
// IF A REAL FORM IS EVER WANTED, the honest shape is: a core
// `PublicContactController` behind the same per-IP lid as LandingEventController,
// writing to a control-plane table, plus a decision about who reads it. That is
// a core change and belongs to core-3, not to a landing rebuild.
//
// RESPONSE-TIME PROMISE: there is deliberately NONE. No SLA, no "we reply
// within 24 hours", no "book a call" calendar - none of those is backed by
// anything in this repo or by any commitment on record, and inventing one is
// exactly the kind of claim the no-fiction rule exists to stop. The copy says
// what the visitor can control (what to send), not what we will do.
//
// PLACEMENT: directly before the closing CTA, so it catches the reader who has
// scrolled the whole page and still has a question - and it is ALSO surfaced
// as a "Contact" link in the nav and the hamburger sheet, so a visitor who
// wants a human in the first five seconds does not have to scroll fifteen
// bands to find one.
//
// Colors: theme tokens + alpha tints only, no `dark:` variants (see
// PlatformLedger.tsx's note on why). Since the 2026-09-12 marketing-system
// pass the surfaces, type roles and buttons all come from `.mkt-*`
// (app/marketing-system.css), whose tokens already carry both themes - which
// is also why the hand-rolled blurred glow div is gone: `.mkt-spot` paints the
// same accent pool from a radial-gradient with no full-viewport filter.
// Scroll entrance is the CSS-only `.mkt-reveal` / `.mkt-reveal-group` rather
// than the framer-motion <Reveal> wrapper, so nothing here can be left at
// opacity 0 if JS never runs.
// ---------------------------------------------------------------------------

const CONTACT_EMAIL = ENTITY_FACTS.contactEmail;

/**
 * Prefilled enquiry. Kept short on purpose: a long template reads as a form to
 * fill in, which is the friction this is trying to remove. The four prompts
 * are the four things that actually change the answer we can give.
 */
function mailtoHref(subject: string, body: string): string {
  return `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}

const REASONS = [
  { key: "fit", Icon: MessageSquareText, titleKey: "contact_r1_title", bodyKey: "contact_r1_body" },
  { key: "scale", Icon: CalendarClock, titleKey: "contact_r2_title", bodyKey: "contact_r2_body" },
  { key: "security", Icon: ShieldCheck, titleKey: "contact_r3_title", bodyKey: "contact_r3_body" },
];

export default function ContactBand({ signupHref }: { signupHref: string }) {
  const t = useTranslations("landing");

  return (
    <section id="contact" className="mkt-band mkt-band--ruled mkt-spot">
      <div className="mkt-band__inner mkt-card mkt-card--raised p-6 sm:p-10">
        <div className="mkt-head mkt-reveal">
          <p className="mkt-eyebrow">{t("contact_eyebrow")}</p>
          <h2 className="mkt-h2">{t("contact_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("contact_lead")}</p>
        </div>

        <ul className="mkt-reveal-group mt-8 grid gap-4 sm:grid-cols-3">
          {REASONS.map(({ key, Icon, titleKey, bodyKey }) => (
            <li key={key} className="mkt-card p-4">
              <Icon className="size-4 text-primary" aria-hidden="true" />
              <p className="mkt-card-title mt-2.5">{t(titleKey)}</p>
              <p className="mkt-small mkt-ink-2 mt-1.5">{t(bodyKey)}</p>
            </li>
          ))}
        </ul>

        <div className="mkt-reveal mt-8 flex flex-col gap-3 sm:flex-row sm:items-center">
          <a
            href={mailtoHref(t("contact_mailSubject"), t("contact_mailBody"))}
            onClick={() => sendLandingEvent("contact_band_click")}
            data-testid="contact-band-email"
            className="mkt-btn mkt-btn-primary mkt-btn-lg"
          >
            <Mail className="size-4" aria-hidden="true" />
            {t("contact_ctaEmail")}
          </a>
          {/* The account stays reachable from here too. Someone who wanted
              to ask a question often just wanted reassurance, and making
              them scroll back up to act on it is a needless loss. */}
          <Link
            href={signupHref}
            onClick={() => sendLandingEvent("contact_signup_click")}
            className="mkt-btn mkt-btn-secondary mkt-btn-lg"
          >
            {t("contact_ctaSignup")}
            <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          <p className="mkt-caption mkt-ink-2 sm:ml-1">
            {t("contact_orWrite")}{" "}
            <a
              href={`mailto:${CONTACT_EMAIL}`}
              onClick={() => sendLandingEvent("contact_band_click")}
              className="font-medium text-foreground underline underline-offset-2"
            >
              {CONTACT_EMAIL}
            </a>
          </p>
        </div>

        {/* Donations. Deliberately the LAST thing in the band and visually
            quieter than the CTAs above it: the ask must never read as a
            paywall on a product that is free until 2027, and it must not
            compete with signup for attention. It is a thank-you, not a toll.

            Reuses the existing `contact_band_click` beacon event rather than
            introducing a new one. A new event name has to be allowlisted in
            core's LandingEventController (both ALLOWED_EVENTS and
            ALLOWED_EVENTS_ORDERED) or the endpoint 204-drops it - and a
            silently-dropped event reads as "nobody clicked", which is
            indistinguishable from the feature not working. */}
        <div className="mkt-reveal mt-8 border-t border-border/60 pt-6">
          <p className="mkt-card-title">{t("donate_title")}</p>
          <p className="mkt-small mkt-ink-2 mt-2 max-w-3xl text-pretty">
            {t(FREE_MODE ? "donate_bodyFreeMode" : "donate_body")}
          </p>
          <p className="mkt-caption mkt-ink-2 mt-3">
            {HAS_COFFEE_LINK ? (
              // Free mode's ask is a Buy Me a Coffee page (maintainer decision);
              // when no URL was baked in the card falls back to the mailto
              // so the section never dangles a dead link.
              <a
                href={COFFEE_URL}
                target="_blank"
                rel="noopener noreferrer"
                onClick={() => sendLandingEvent("contact_band_click")}
                data-testid="contact-band-donate"
                className="inline-flex items-center gap-1.5 font-medium text-foreground underline underline-offset-2"
              >
                <Coffee className="size-3.5" aria-hidden="true" />
                {t("donate_ctaCoffee")}
              </a>
            ) : (
              <a
                href={mailtoHref(t("donate_mailSubject"), t("donate_mailBody"))}
                onClick={() => sendLandingEvent("contact_band_click")}
                data-testid="contact-band-donate"
                className="font-medium text-foreground underline underline-offset-2"
              >
                {t("donate_cta")}
              </a>
            )}
          </p>
          <p className="mkt-caption mkt-ink-2 mt-2">{t("donate_note")}</p>
        </div>
      </div>
    </section>
  );
}
