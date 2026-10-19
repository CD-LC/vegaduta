// Curated "Surprise me" prompts for the Create surface goal box. A mix of
// India-relevant small-business, education, service and community ideas;
// every one names who it is for and what it should do, so it also scores
// well on promptHints.

export const SURPRISE_PROMPTS: readonly string[] = [
  "Answer WhatsApp questions from my kirana store customers about prices and stock, and pass bulk orders to me",
  "Build a billing helper for my kirana shop that turns a spoken list of items into a GST-ready bill",
  "Schedule tuition classes for my 40 students, remind parents on WhatsApp the evening before, and rebook missed classes",
  "Take orders on WhatsApp for my home bakery in Pune, confirm delivery slots and collect UPI payment links",
  "Reply to customer questions about my salon's services and prices in Hindi and English, and book appointments",
  "Help farmers in my village ask in Kannada about mandi prices and weather, and summarise it in simple words",
  "Read my clinic's appointment requests, suggest a free slot and send the patient a reminder, but never give medical advice",
  "Draft polite payment reminders for my small business's overdue invoices and flag the ones above 50,000 rupees for me",
  "Answer parents' questions about school fees, holidays and exam dates from our circulars, and escalate complaints to the principal",
  "Screen job applications for my small factory in Surat, summarise each CV and shortlist the top five for a call",
  "Help my restaurant handle table bookings, today's specials and Zomato review replies in a friendly tone",
  "Track my tailoring shop's orders, tell customers when their outfit is ready and ask for feedback afterwards",
  "Explain government schemes like PM Kisan and Mudra loans to small business owners and check which ones they may qualify for",
  "Summarise my shop's daily sales messages into an evening report for me, with the best-selling items and low-stock warnings",
  "Run a quiz bot for Class 10 students that explains each wrong answer in simple English and tracks weak topics",
  "Collect rent receipts and maintenance requests for my apartment society and nudge residents who have not paid",
  "Answer questions about my travel agency's Char Dham and Goa packages, and capture the lead's dates and budget",
  "Help my gym members book classes, freeze memberships and get diet tips, and alert me about members who stopped coming",
  "Triage emails sent to my NGO, thank donors by name and pass volunteer sign-ups to the coordinator",
  "Create a landing page for my homestay in Coorg with a photo grid, room prices and an enquiry form",
  "Build an app for my cricket club to schedule matches, pick teams and split ground fees over UPI",
  "Monitor competitor prices for the electronics I sell online and tell me when I am more than 5 percent more expensive",
  "Help a first-time home buyer in Bengaluru compare loans, explain EMI and stamp duty, and list the documents needed",
  "Turn my YouTube cooking videos into blog posts and short Instagram captions for my followers in Tamil and English",
  "Greet visitors on my coaching institute's website, answer course and fee questions and book a demo class",
  "Answer questions on my pharmacy's WhatsApp about medicine availability and timings, and never suggest a dosage",
  "Keep track of my freelance projects, remind me of deadlines and draft a polite follow-up when a client goes quiet",
  "Help my temple trust answer devotees' questions on darshan timings and seva booking, and log donation enquiries",
  "Review contracts my small agency receives and highlight unusual payment terms and auto-renewal clauses for a lawyer to check",
  // Enterprise engineering: the ideas a platform team at a bank or a mainframe estate would actually type.
  "Explain mainframe abend codes like S0C7 and SB37 to the junior developers on our team and draft the JCL fix and safe rerun for each failed step",
  "Convert our bank's Jenkins pipelines to GitHub Actions and summarise what each stage does for the platform team",
  "Translate COBOL copybooks into Java records and JSON schemas for my team, and flag the packed-decimal fields that need care",
  "Help our payments team trace ISO 20022 pacs.008 rejects back to the SWIFT MT103 fields and draft the repair note",
  "Track reconciliation breaks between our core banking ledger and the UPI switch each morning and flag anything over 1 lakh for me",
  "Review change requests for our bank's release board and check maker-checker, test evidence and rollback plans for the team",
];

/** A random prompt, never the one currently in the box (when avoidable).
 * `rand` is injectable so tests are deterministic. */
export function pickSurprise(current = "", rand: () => number = Math.random): string {
  const pool = SURPRISE_PROMPTS.filter((p) => p !== current.trim());
  const list = pool.length ? pool : SURPRISE_PROMPTS;
  const i = Math.min(list.length - 1, Math.max(0, Math.floor(rand() * list.length)));
  return list[i];
}
