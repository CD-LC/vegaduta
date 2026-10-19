package com.agenticai.core.agents.tools.classification;

/**
 * What a tool <b>can reach or reveal</b> - the capability half of a
 * {@link ToolClassification}.
 *
 * <p><b>Why this exists.</b> Before this enum the platform's only risk model for
 * a tool was {@code ApprovalPolicyService}'s sensitivity globs - a handful of
 * string patterns ({@code "channel.*.send"}, {@code "*.delete"}, {@code
 * "codeintel.workspace.run"}) that answer one narrow question: does this action
 * type need a human to click approve. That is a policy, not a classification.
 * It cannot answer "which of this agent's tools can move tenant data off the
 * platform", "which tools act with a stored credential", or "which calls in the
 * run that leaked data were credentialed" - all of which are the questions
 * actually asked during an incident, a security review and an enterprise
 * procurement questionnaire. A glob over action names also silently stops being
 * true the moment someone renames a tool.
 *
 * <p><b>Classify a tool by its most sensitive reachable action, always.</b>
 * Several first-party tools here are multi-action: {@code google_gmail} takes
 * {@code action: list | read | send} on one tool name; {@code google_drive}
 * takes {@code list | read | create}. The registry resolves by tool <i>name</i>
 * and never sees the arguments, so a tool that can both read and send is
 * {@link #EGRESS}, not {@link #READ_TENANT}. Classifying by the common case is
 * how a gate gets skipped by an attacker who simply passes the uncommon
 * argument. If that over-classification blocks legitimate work, the fix is to
 * split the tool into two tool names with two classifications - not to soften
 * the label.
 *
 * <p>The constants are declared in increasing order of blast radius, but no
 * behaviour depends on {@link Enum#ordinal()}; every derived predicate here is
 * an exhaustive switch so that adding a constant is a compile error at each
 * decision point rather than a silent default.
 *
 * @see ToolTrustTier for the other half - where the tool's data came from
 */
public enum ToolSensitivity {

    /**
     * Returns only information that is already public, and reveals nothing
     * about the tenant by returning it.
     *
     * <p>The subtlety worth stating: {@code READ_PUBLIC} describes the
     * <i>result</i>, not the request. {@code fetch_web_page} is
     * {@code READ_PUBLIC} - the page it returns is public - even though the
     * <i>choice</i> of URL is tenant-derived and even though its
     * {@link ToolTrustTier} is the most dangerous one there is. Sensitivity and
     * trust are orthogonal, and this is the clearest example of why they have
     * to be two fields: the web-fetch tool is simultaneously the lowest-stakes
     * thing to call and the highest-stakes thing to believe.
     */
    READ_PUBLIC,

    /**
     * Reads tenant-private data and returns it into the model's context:
     * knowledge-base chunks, stored memories, channel history, device
     * telemetry, calendar entries, the tenant's own configuration.
     *
     * <p>Operationally this is a <b>disclosure</b> capability, not a mutation
     * one, so it is not a taint sink ({@link #isTaintSink()} is false) - reading
     * tenant data is not the step that causes harm. The step that causes harm is
     * the later one that moves what was read somewhere an attacker can see it,
     * which is why {@link #EGRESS} exists as its own tier rather than being
     * folded into "write".
     *
     * <p>It is still the tier a data-residency or GDPR question is asked about,
     * and it is what makes a post-incident query like "which tools in this run
     * read tenant data before the egress call" answerable at all.
     */
    READ_TENANT,

    /**
     * Mutates tenant state inside the platform: indexes a document, writes a
     * memory or graph fact, updates a configuration row, creates a workspace.
     *
     * <p>A taint sink. The reason a <i>write</i> counts as a sink even when
     * nothing leaves the platform is the persistence channel: an injected
     * instruction that gets written into the knowledge base, a memory fact or a
     * playbook item outlives the turn that carried it and will be retrieved,
     * believed and acted on in some later run by some other agent. Blocking
     * exfiltration while leaving the write path open buys one turn of safety and
     * loses the next thousand.
     *
     * <p>Note that {@code WRITE_TENANT} says nothing about reversibility - that
     * is the separate {@link ToolClassification#irreversible()} flag, because
     * "indexes a document you can delete again" and "deletes a workspace" are
     * both writes and are nothing alike.
     */
    WRITE_TENANT,

    /**
     * Acts using a <b>stored credential</b> - an OAuth token, an API key from
     * the vault, a channel secret - so the call is made with the tenant's
     * identity and authority rather than the platform's.
     *
     * <p>This is the tier that makes prompt injection worth an attacker's time.
     * A tool in this tier does not need a vulnerability to do damage; it needs
     * only to be invoked with attacker-chosen arguments, because it is already
     * authorised. Nothing is broken, no sandbox is escaped, no credential is
     * stolen - the agent simply uses its own legitimate access on the
     * attacker's behalf. That is why the enforcement invariant is stated as
     * "tainted content must not reach a credentialed tool" rather than as
     * anything about model robustness.
     *
     * <p>A taint sink, and the tier where {@link ToolClassification#requiredScope()}
     * is most often non-null, since the scope is usually the narrowest honest
     * description of what the credential can do (e.g.
     * {@code https://www.googleapis.com/auth/gmail.send}).
     */
    CREDENTIALED,

    /**
     * Moves bytes <b>across the tenant boundary to a destination the caller
     * names</b>: sends an email or a chat message, posts to a webhook, uploads a
     * file, forwards an attachment, publishes a video.
     *
     * <p>The defining property is the controllable destination, not the size of
     * the payload. One outbound HTTP request whose URL came from the prompt is a
     * complete exfiltration primitive; so is one email whose recipient came from
     * a fetched web page. This is the terminal step of every realistic injection
     * chain in this platform's threat model, and it is the tier that almost
     * always deserves {@link ToolClassification#irreversible()} = true as well,
     * because a sent message cannot be recalled.
     *
     * <p>Kept distinct from {@link #CREDENTIALED} even though most egress tools
     * are also credentialed, because the two fail differently and a real system
     * has instances of each without the other: a tenant-configured outbound
     * webhook is egress with no stored credential, and a read-only Drive token
     * is credentialed with no egress. Declaring the more dangerous of the two is
     * the rule - see this enum's class javadoc.
     */
    EGRESS;

    /**
     * True when an invocation of a tool at this sensitivity must be refused (or
     * escalated to human approval) while the conversation carries taint from an
     * {@link ToolTrustTier#UNTRUSTED_EXTERNAL} source.
     *
     * <p>This is the single predicate the taint-propagation gate (agenda item 4)
     * consumes, which is why it lives on the enum rather than being re-derived
     * by each caller: there must be exactly one definition of "sink" in the
     * codebase, and it must be impossible to disagree with it by accident.
     *
     * <p>Reads are deliberately not sinks. Gating {@link #READ_TENANT} on taint
     * would break the most common legitimate shape there is - fetch a page, then
     * look something up about it - and would train operators to disable the gate.
     */
    public boolean isTaintSink() {
        return switch (this) {
            case READ_PUBLIC, READ_TENANT -> false;
            case WRITE_TENANT, CREDENTIALED, EGRESS -> true;
        };
    }

    /**
     * True for the three tiers where a mistake is not recoverable by retrying
     * differently - the input to
     * {@link ToolClassification#requiresHumanConfirmation()}.
     *
     * <p>Identical in value to {@link #isTaintSink()} today, and intentionally
     * <b>not</b> implemented by delegating to it. They are two different
     * questions that happen to have the same answer at this moment ("may tainted
     * data reach this" versus "is this consequential enough that a human should
     * see it first"), and collapsing them now would mean that the first time
     * they diverge - the likely case being a future {@code READ_TENANT} tier
     * split where bulk export needs confirmation but a single lookup does not -
     * someone has to untangle which call sites meant which.
     */
    public boolean isConsequential() {
        return switch (this) {
            case READ_PUBLIC, READ_TENANT -> false;
            case WRITE_TENANT, CREDENTIALED, EGRESS -> true;
        };
    }
}
