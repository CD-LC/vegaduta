package com.agenticai.core.agents.tools.classification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiled-in risk declarations for the platform's <b>first-party</b> tools - the ones whose
 * {@code ToolCallback} lives in this repository and whose name is a compile-time constant.
 *
 * <h2>Why declarations live in code and not only in the database</h2>
 *
 * A first-party tool's classification is a property of the code that implements it, so the
 * declaration belongs next to the code and must travel with it in the same commit. Concretely:
 * adding a {@code send_slack_message} tool and adding the line here that says it is {@code
 * EGRESS}/{@code irreversible} should be the same pull request, reviewed by the same person. If the
 * only home for classifications were {@code tool_classification} rows, a new tool would ship
 * unclassified and be <i>blocked</i> in every tenant until somebody remembered to write SQL - which
 * in practice means the first incident response is "disable the gate", and the gate never comes
 * back on.
 *
 * <p>The database table is for the two things code cannot know: per-tenant overrides, and tools
 * that do not exist at compile time (MCP-discovered tools, {@code UserToolScript} custom-code
 * tools, marketplace templates). Stored rows win over these declarations - see {@link
 * ToolClassificationRegistry} for the exact order - so a tenant admin can always tighten (or, with
 * eyes open, loosen) what the platform shipped.
 *
 * <h2>What is deliberately NOT declared here</h2>
 *
 * <ul>
 *   <li><b>MCP tools.</b> {@code mcp__<server>__<tool>} names are discovered at runtime from
 *       servers this platform does not control, and their descriptions are attacker-authorable
 *       prompt content. There is no {@code mcp__*} fallback entry on purpose: an MCP tool nobody
 *       classified resolves to {@link ToolClassificationDecision.Reason#UNCLASSIFIED} and is
 *       blocked. That is the quarantine-until-classified behaviour the design calls for, and it is
 *       achieved by the absence of a rule rather than by the presence of one, which is the only
 *       version of it that cannot be accidentally deleted.
 *   <li><b>Tenant custom-code tools.</b> {@code UserToolScriptCallback} takes its name from a
 *       tenant-authored row, so the name space is unbounded and the code is foreign. Same
 *       treatment: unclassified, blocked, until the tenant declares it.
 *   <li><b>The {@code core/mcpserver} tools</b> ({@code call_agent}, {@code call_workflow}, {@code
 *       list_my_agents}, {@code list_my_workflows}, {@code search_knowledge}). Those are the
 *       surface this platform <i>exposes</i> to outside MCP clients, not tools its own agents call,
 *       so they are dispatched by a different path and classifying them here would imply a gate
 *       that does not run.
 * </ul>
 *
 * <h2>Two conservative conventions used throughout, stated once</h2>
 *
 * <ol>
 *   <li><b>{@code needsToolContext} is {@code true} for every entry.</b> The flag exists so a
 *       wiring check can refuse to bind an identity-dependent tool into a path with no {@code
 *       ToolContext} (a sweeper, an inbound A2A call, an edge execution). Declaring {@code true}
 *       therefore fails <i>closed</i>: the worst case is a refusal to bind, never a null-tenant
 *       execution. Each tool that is proven not to need identity can be narrowed later with
 *       evidence; none has been proven yet, so none is narrowed.
 *   <li><b>Sensitivity is the most sensitive reachable action</b>, because the registry resolves by
 *       tool name and never sees the arguments. Several first-party tools are multi-action on one
 *       name - {@code google_gmail} is {@code list|read|send}, {@code google_drive} is {@code
 *       list|read|create} - and a tool that can send is {@code EGRESS} even when it is usually used
 *       to read. See {@link ToolSensitivity}.
 * </ol>
 *
 * <p>Every entry below carries the reason for its least obvious dimension. Where a classification
 * is a judgement call rather than a fact, the comment says so.
 */
public final class BuiltInToolClassifications {

    /**
     * Synthetic author recorded on seeded rows and reported by the drift check, so a console can
     * distinguish shipped opinions from human edits.
     */
    public static final String DECLARED_BY = "platform:built-in";

    private static final Map<String, ToolClassification> EXACT;

    /**
     * Wildcard declarations, in declaration order, consulted only after every exact name misses.
     * Ordered rather than a map because overlapping patterns must resolve deterministically: first
     * match wins, so the most specific pattern has to be declared first.
     */
    private static final List<PatternDeclaration> PATTERNS;

    static {
        Map<String, ToolClassification> exact = new LinkedHashMap<>();

        // ---- Web ingress: the canonical taint source -----------------------
        // READ_PUBLIC and UNTRUSTED_EXTERNAL at the same time is not a
        // contradiction - it is the clearest demonstration of why trust and
        // sensitivity are separate fields. Fetching a public page reveals
        // nothing and is the lowest-stakes call in the platform; *believing*
        // what it returns is the highest-stakes thing the platform does.
        exact.put(
                "fetch_web_page",
                new ToolClassification(
                        "fetch_web_page",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // deep_research searches the public web and READS full third-party pages, returning their
        // text to the model - the same taint source as fetch_web_page, so it is classified
        // identically. verify_citations only compares model-supplied quotes with page text that
        // deep_research already fetched this turn, but it echoes slices of that third-party text
        // back, so it carries the same trust tier (a laundering path otherwise).
        exact.put(
                "deep_research",
                new ToolClassification(
                        "deep_research",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "verify_citations",
                new ToolClassification(
                        "verify_citations",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // http_request is fetch_web_page's general-purpose sibling (any method,
        // headers, body) and is deliberately classified IDENTICALLY: the
        // response is the same untrusted third-party taint source, and the
        // call itself is the same outbound request to a public host from this
        // JVM. Vault-secret headers never reach the model (HttpRequestExecutor
        // resolves and redacts them inside the call boundary), so the tool
        // reveals nothing the model did not already write. Per-tool approval
        // for mutating calls remains ApprovalPolicyService's job, same as for
        // every other built-in.
        // Known limit (safe mode): a read can carry a NAMED credential and return private tenant
        // data from an
        // authenticated API, yet classification is by tool name and cannot see arguments, so it
        // stays READ_PUBLIC
        // and the lethal-trifecta detector under-reports the private-data leg for an agent holding
        // only this
        // tool plus http_request_write. A per-mode declaration or a separate credentialed tool name
        // would fix it.
        exact.put(
                "http_request",
                new ToolClassification(
                        "http_request",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // http_request_write exists only in http_request's safe mode
        // (app.tools.http-request.safe-mode,
        // default off), where http_request is narrowed to GET/HEAD/OPTIONS - which is why the
        // READ_PUBLIC
        // declaration above is accurate there - and the state-changing verbs move here.
        // Classification is
        // by tool name and never sees arguments, so the split is what lets each half be declared
        // honestly:
        // POST/PUT/PATCH/DELETE to a URL the model chose is a complete exfiltration primitive
        // (EGRESS), the
        // far end may act on it and a retry would double-send (DURABLE), and it cannot be undone
        // from here
        // (irreversible). EGRESS + irreversible satisfies requiresHumanConfirmation(), so the
        // existing
        // confirmation gate prompts for every call by default; it is deliberately NOT on the gate's
        // exempt list. UNTRUSTED_EXTERNAL because the response is third-party data like any other
        // web
        // ingress (EGRESS-only classification excludes it from the trifecta's untrusted-input leg;
        // the
        // read tool carries that leg).
        exact.put(
                "http_request_write",
                new ToolClassification(
                        "http_request_write",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // a2a_call_agent hands a message to an EXTERNAL A2A agent and returns its reply.
        // UNTRUSTED_EXTERNAL: the reply is authored by a third-party agent (a prompt-injection
        // carrier exactly like a fetched page). EGRESS + DURABLE + irreversible: the message
        // leaves the platform and the remote may act on it, so a blind retry would double-send
        // and the send cannot be undone - same shape as google_gmail's send half. Offered only
        // when app.a2a.client.enabled and gated by an operator host allow-list.
        exact.put(
                "a2a_call_agent",
                new ToolClassification(
                        "a2a_call_agent",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- Knowledge base / RAG ------------------------------------------
        // search_knowledge_base is UNTRUSTED_EXTERNAL even though the corpus is
        // the tenant's own. The corpus is fed by ingestion that includes fetched
        // web pages and uploaded documents, so a retrieved chunk can be
        // attacker-authored text that was written to storage days earlier. Taint
        // that does not survive storage is not taint tracking - it is the single
        // most commonly missed case in every implementation of this pattern, and
        // declaring the read side untrusted is the only place it can be caught
        // without re-deriving provenance per chunk (which is the follow-on work).
        exact.put(
                "search_knowledge_base",
                new ToolClassification(
                        "search_knowledge_base",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // Collection metadata only - names and counts, no document content, so no
        // path for authored content to arrive.
        exact.put(
                "list_knowledge_collections",
                new ToolClassification(
                        "list_knowledge_collections",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // WRITE_TENANT + DURABLE, but NOT irreversible: an indexed document can be
        // deleted again. This is the distinction that keeps requiresHumanConfirmation()
        // from firing on every knowledge write, which would train operators to
        // approve without reading.
        exact.put(
                "add_to_knowledge_base",
                new ToolClassification(
                        "add_to_knowledge_base",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Agent / tenant / shared-graph memory --------------------------
        // Recall is UNTRUSTED_EXTERNAL for exactly the same storage-survives-taint
        // reason as knowledge search: a fact remembered from a fetched page is
        // still attacker-authored when it is recalled next month. The shared-graph
        // variants are worse, not better - a fact written by one agent is read by
        // another, so the blast radius crosses agents inside the tenant.
        exact.put(
                "recall_related_facts",
                new ToolClassification(
                        "recall_related_facts",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "recall_shared_graph_facts",
                new ToolClassification(
                        "recall_shared_graph_facts",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // The write side is the persistence channel the taint gate has to close:
        // WRITE_TENANT is a taint sink precisely so an injected instruction cannot
        // be laundered into long-term memory and re-read later as a trusted fact.
        exact.put(
                "remember_fact",
                new ToolClassification(
                        "remember_fact",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "remember_shared_fact",
                new ToolClassification(
                        "remember_shared_fact",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Data tables (app.data-tables.*, default OFF) -------------------
        // data_table_query reads the tenant's own tables. UNTRUSTED_EXTERNAL for the same
        // storage-survives-taint reason as knowledge search and memory recall: a cell an earlier
        // run copied from a fetched page or an inbound message is still attacker-authored when
        // it is read back next week. READ_TENANT: nothing it returns leaves the workspace.
        exact.put(
                "data_table_query",
                new ToolClassification(
                        "data_table_query",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // data_table_write is a tenant-scoped DURABLE side effect, not egress: rows persist and a
        // blind retry would double-insert, but nothing leaves the workspace. WRITE_TENANT makes it
        // a taint sink (an injected instruction cannot launder itself into a table unchallenged).
        // Not irreversible: rows can be edited or deleted again, and update_rows/delete_rows
        // refuse an empty filter, so one call cannot wipe a table - which is also why it stays
        // off the always-confirm path (requiresHumanConfirmation) like add_to_knowledge_base.
        exact.put(
                "data_table_write",
                new ToolClassification(
                        "data_table_write",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Scheduled tasks (app.scheduled-tasks.*, default OFF) ------------------
        // schedule_task creates a RECURRING, UNATTENDED run of the calling agent, optionally
        // delivering its
        // output to an address the caller names. A tenant-scoped DURABLE write (the task row
        // persists, and a
        // blind retry would create a second one), TENANT_SCOPED rather than egress because the call
        // itself
        // sends nothing - every later delivery goes through the channel approval gate. WRITE_TENANT
        // makes it a
        // taint sink, so an injected instruction cannot quietly plant a standing job. Marked
        // irreversible on
        // purpose, and unlike data_table_write that is what puts it on the human-confirmation path
        // (requiresHumanConfirmation = irreversible AND consequential): it grants standing
        // authority to spend
        // budget and message people with no person present, which this platform cannot take back
        // once a run
        // has fired. It is also on AgentToolGuard.ALWAYS_CONFIRM_TOOLS so a fully autonomous agent
        // still asks.
        exact.put(
                "schedule_task",
                new ToolClassification(
                        "schedule_task",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- Custom code tools (app.tool-sandbox, see CreateCustomToolToolCallback) ----
        // create_custom_tool writes a model-authored program as a DISABLED draft and records a
        // custom_code_tool.enable approval request; the executor for that action type is the only
        // path that can enable it, so the human review step is structural and holds for every
        // autonomy tier - this call does not need the classification confirmation gate on top of
        // it. The draft write is TENANT_SCOPED (the call sends nothing off-platform), DURABLE (the
        // row survives the turn; a blind retry would create a second one), consequential (a queued
        // approval a human must adjudicate) but reversible - the draft is deleted, not merely
        // undone - so requiresHumanConfirmation stays false.
        exact.put(
                "create_custom_tool",
                new ToolClassification(
                        "create_custom_tool",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Agent jobs (app.agent-jobs.*) ------------------
        // continue_in_background hands the unfinished task to a background agent_job: a
        // tenant-scoped DURABLE write (the job row persists; a blind retry would submit a
        // second one) whose every call inside it still passes the normal per-turn ceiling
        // and the approval gate - it buys more turns, not more privilege. Reversible: the
        // job is cancelable while queued or running, and a finished one produced output,
        // not standing authority (unlike schedule_task's recurring unattended runs). So it
        // stays off the human-confirmation path; the per-agent allow-list is the opt-in.
        exact.put(
                "continue_in_background",
                new ToolClassification(
                        "continue_in_background",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Google Workspace (verified scopes from GoogleScopes) ----------
        // google_gmail is UNTRUSTED_EXTERNAL on the READ side - an inbound mailbox
        // is a channel any stranger can write to, which is the whole point of a
        // support inbox - and EGRESS + irreversible on the SEND side. One tool
        // name, both halves, so it gets the worst of each. This single row is the
        // classic lethal combination in one callback: it reads attacker-authored
        // content and it can mail data to an address chosen at call time.
        exact.put(
                "google_gmail",
                new ToolClassification(
                        "google_gmail",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/gmail.send",
                        true,
                        true));
        // drive.file scope only sees files this app created or the user explicitly
        // opened with it, so admission of foreign content is a deliberate human
        // act - hence TENANT_SCOPED rather than UNTRUSTED_EXTERNAL. That is a
        // judgement call, and the residual risk (a user opening an attacker's
        // document) is real; revisit it if Drive content is ever ingested
        // automatically.
        exact.put(
                "google_drive",
                new ToolClassification(
                        "google_drive",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/drive.file",
                        false,
                        true));
        // EGRESS, not merely CREDENTIALED: create_event accepts an `attendees`
        // list of arbitrary email addresses, and Google mails an invitation to
        // each. A calendar invite is an outbound message with a caller-chosen
        // recipient and cannot be unsent - so irreversible too. This is the entry
        // most likely to surprise a reader, which is why it is spelled out.
        exact.put(
                "google_calendar",
                new ToolClassification(
                        "google_calendar",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/calendar.events",
                        true,
                        true));
        // Booking tools, split from google_calendar so reads and writes are judged apart. Free-slot
        // search reads busy time only. Move and cancel change an appointment a customer may rely on
        // and, with notify_attendees, make Google email every attendee: irreversible EGRESS, so the
        // confirmation gate asks a human. Neither is on the confirmation-exempt list.
        exact.put(
                "google_calendar_find_free_slots",
                new ToolClassification(
                        "google_calendar_find_free_slots",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        "https://www.googleapis.com/auth/calendar.events",
                        false,
                        true));
        exact.put(
                "google_calendar_move_event",
                new ToolClassification(
                        "google_calendar_move_event",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/calendar.events",
                        true,
                        true));
        exact.put(
                "google_calendar_cancel_event",
                new ToolClassification(
                        "google_calendar_cancel_event",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/calendar.events",
                        true,
                        true));
        exact.put(
                "google_sheets",
                new ToolClassification(
                        "google_sheets",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/spreadsheets",
                        false,
                        true));
        // Read-only analytics, but still CREDENTIALED: it acts with the tenant's
        // OAuth token. "Read-only" describes the API surface, not the authority
        // being exercised.
        exact.put(
                "youtube_content_performance",
                new ToolClassification(
                        "youtube_content_performance",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        "https://www.googleapis.com/auth/youtube.readonly",
                        false,
                        true));
        // Publishing is the most irreversible action in the platform: the bytes
        // become public under the tenant's channel identity, and a takedown is
        // not an undo.
        exact.put(
                "upload_video_to_youtube",
                new ToolClassification(
                        "upload_video_to_youtube",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        "https://www.googleapis.com/auth/youtube.upload",
                        true,
                        true));

        BuiltInToolClassificationsExtended.declare(exact);

        EXACT = Collections.unmodifiableMap(exact);

        List<PatternDeclaration> patterns = new ArrayList<>();
        // Agent-to-agent delegation. AgentToolProvider#resolveCollaboratorTools
        // names these "ask_agent_" + slugify(collaborator.name()), so the concrete
        // names are tenant data and only a pattern can cover them.
        //
        // UNTRUSTED_EXTERNAL because a delegated turn's answer is only as trusted
        // as the callee's own tools, and the callee may hold fetch_web_page. The
        // correct long-term answer is to compute a delegation's effective trust as
        // the maximum over the callee's bound tools; until that exists, assuming
        // the worst is the fail-closed choice.
        //
        // WRITE_TENANT rather than EGRESS is a deliberate floor, not an upper
        // bound: a sub-agent can certainly write (it has the memory tools), so
        // WRITE_TENANT is provable, whereas claiming EGRESS would be guessing at a
        // toolset this class cannot see. Under-declaring here is a known gap and
        // is listed as such - the effective-classification work above is what
        // closes it, not a bolder guess.
        patterns.add(
                new PatternDeclaration(
                        "ask_agent_*",
                        new ToolClassification(
                                "ask_agent_*",
                                ToolTrustTier.UNTRUSTED_EXTERNAL,
                                ToolSensitivity.WRITE_TENANT,
                                ToolDeterminism.DURABLE,
                                null,
                                false,
                                true)));
        PATTERNS = Collections.unmodifiableList(patterns);
    }

    private BuiltInToolClassifications() {
        // static declaration table
    }

    /**
     * The compiled-in declaration for {@code toolName}, or {@code null} when the platform ships no
     * opinion about it.
     *
     * <p>Returns a bare {@code null} rather than a {@link ToolClassificationDecision} because this
     * is an internal lookup with exactly one caller ({@link ToolClassificationRegistry}), and that
     * caller's job is to convert a miss here into the blocked decision. Keeping the fail-closed
     * conversion in one place is the point; adding a second place that can decide what a miss means
     * is how the two places eventually disagree.
     *
     * <p>A pattern match is re-keyed to the concrete name via {@link
     * ToolClassification#withToolName(String)}, so the returned object always names the tool that
     * was asked about.
     */
    public static ToolClassification find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        ToolClassification exact = EXACT.get(toolName);
        if (exact != null) {
            return exact;
        }
        for (PatternDeclaration declaration : PATTERNS) {
            if (matchesGlob(declaration.pattern(), toolName)) {
                return declaration.classification().withToolName(toolName);
            }
        }
        return null;
    }

    /**
     * Every exact declaration, for the drift report and for the console's "what does the platform
     * ship" view. Unmodifiable, insertion-ordered so the report groups tools by family the way this
     * file does.
     */
    public static Map<String, ToolClassification> all() {
        return EXACT;
    }

    /** Every wildcard declaration, in first-match-wins order. */
    public static List<ToolClassification> allPatterns() {
        List<ToolClassification> out = new ArrayList<>(PATTERNS.size());
        for (PatternDeclaration declaration : PATTERNS) {
            out.add(declaration.classification());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Single-{@code *} glob match, character-identical in behaviour to {@code
     * ApprovalPolicyService#matchesGlob}.
     *
     * <p>Intentionally the same trivially-auditable quoting trick rather than a shared utility: the
     * two live in different Maven modules ({@code approval} does not depend on {@code agents} and
     * must not), and a four-line pure function duplicated with a comment pointing at its twin is
     * cheaper to verify than a new cross-module dependency created for it. {@code \Q...\E} quoting
     * means a tool name containing regex metacharacters cannot be interpreted as a pattern.
     */
    static boolean matchesGlob(String pattern, String value) {
        String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
        return value.matches(regex);
    }

    /** A wildcard declaration and the classification it confers. */
    private record PatternDeclaration(String pattern, ToolClassification classification) {}
}
