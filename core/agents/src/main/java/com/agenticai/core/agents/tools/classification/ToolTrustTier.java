package com.agenticai.core.agents.tools.classification;

/**
 * Where a tool's <b>code and returned bytes come from</b> - the provenance half
 * of a {@link ToolClassification}.
 *
 * <p><b>Why this exists as a declared enum rather than a heuristic.</b> An LLM
 * has no privilege separation: the system prompt, the user's message and a web
 * page a tool fetched all arrive as one undifferentiated token sequence. Every
 * prompt-level defence against injection is probabilistic, and adaptive attacks
 * against state-of-the-art prompt-level defences still report success rates
 * above 85% (see the 2026 information-flow-control literature cited in
 * {@code docs/} - Prismata, ceLLMate, OS-Harm). The only checkable invariant is
 * a dataflow one: <i>content that originated outside the trust boundary must
 * never reach a tool that can act with the tenant's authority.</i> You cannot
 * state that invariant, let alone enforce it, without a declared answer to
 * "where did this come from" per tool. This enum is that answer.
 *
 * <p><b>Trust is about the bytes, not about the vendor.</b> A tool can be
 * first-party, well-written, audited code and still be
 * {@link #UNTRUSTED_EXTERNAL}, because the question is not "do we trust the
 * author" but "can an attacker choose the content this returns". {@code
 * fetch_web_page} is first-party code; everything it hands the model was
 * written by whoever controls the page.
 *
 * @see ToolSensitivity for the other half - what a tool can reach or reveal
 * @see ToolClassification#producesTaintedOutput()
 */
public enum ToolTrustTier {

    /**
     * Code and data are both inside the platform's trust boundary: first-party
     * code in this repository, reading first-party state that no tenant and no
     * third party can author.
     *
     * <p>Operationally: output from a {@code TRUSTED_INTERNAL} tool does
     * <b>not</b> taint the conversation, so a downstream credentialed or egress
     * call is not gated on its account. Reserve it accordingly - the temptation
     * is to label anything written in-house as internal, which quietly
     * re-opens the exact hole the tier exists to close. The test is whether an
     * attacker can influence a single byte of the return value. If a tenant
     * user, an end customer, a web page, an email, an uploaded document or a
     * third-party API can, this is not the right tier.
     *
     * <p>Examples in this codebase: {@code report_progress} (writes run
     * progress to platform-owned tables and returns an acknowledgement),
     * {@code list_my_agents} / {@code list_my_workflows} (enumerate
     * platform-managed configuration rows). Note these are still not
     * {@link ToolDeterminism#PURE} - see that enum.
     */
    TRUSTED_INTERNAL,

    /**
     * The code is first-party but the <b>data is the tenant's own</b>: their
     * knowledge base, their agent memory, their connected Google account, their
     * enrolled devices, their channel history.
     *
     * <p>Operationally this is the tier that demands the most care, because it
     * is the one people reach for by default and it means two different things
     * at once. Tenant data is not attacker-<i>authored</i> in the general case,
     * but it is frequently attacker-<i>reachable</i>: anything a tenant's end
     * users can write into, anyone who can email or message that tenant can
     * write into. A support mailbox is tenant data and is also a
     * stranger-controlled input channel.
     *
     * <p>The rule this codebase applies: a tenant-scoped store whose contents
     * can be written by an unauthenticated or merely-external party is
     * classified {@link #UNTRUSTED_EXTERNAL} on the <i>read</i> side even
     * though the store is the tenant's. That is why {@code search_knowledge}
     * and the memory-recall tools are declared {@code UNTRUSTED_EXTERNAL} in
     * {@link BuiltInToolClassifications} and not here: a document ingested from
     * the web yesterday is still attacker-authored content today, and "taint
     * must survive storage" is the single most commonly missed case in every
     * implementation of this pattern.
     *
     * <p>{@code TENANT_SCOPED} output does not itself taint the conversation.
     * It is the tier for first-party reads and writes of state that only
     * authenticated members of the workspace can have authored.
     */
    TENANT_SCOPED,

    /**
     * Either the code or the returned content originates outside the trust
     * boundary. Output from these tools arrives <b>tainted</b>, permanently,
     * and taint is sticky through summarisation, through re-embedding and
     * through storage.
     *
     * <p>Four distinct sources land here, and it is worth naming all four
     * because teams usually remember the first and forget the rest:
     * <ol>
     *   <li><b>Third-party content fetched live</b> - {@code fetch_web_page}.
     *       The obvious one.</li>
     *   <li><b>Third-party MCP servers.</b> Every tool discovered from an MCP
     *       server that is not on the tenant's trusted list, including the
     *       server's own tool <i>descriptions</i>, which are attacker-authored
     *       text that this platform puts into the prompt. MCP tool names arrive
     *       namespaced as {@code mcp__<server>__<tool>} (see {@code
     *       NamespacedMcpToolCallback}), so a glob row of the form
     *       {@code mcp__somesrv__*} is the natural way to classify a server's
     *       whole surface at once.</li>
     *   <li><b>Tenant-authored code</b> - {@code UserToolScript} custom-code
     *       tools and marketplace templates. First-party runtime, foreign
     *       logic.</li>
     *   <li><b>Retrieval over a corpus that contains any of the above</b> -
     *       RAG and long-term memory. This is the one that gets missed, and it
     *       is the one that survives a process restart.</li>
     * </ol>
     *
     * <p>Consequence for enforcement (agenda item 4): once any
     * {@code UNTRUSTED_EXTERNAL} result has entered a turn, a subsequent call
     * to a tool whose {@link ToolSensitivity#isTaintSink()} is true must be
     * escalated to human approval rather than executed. Container isolation
     * does not help here - a sandbox answers "can the agent wreck the host",
     * never "can a web page make the agent exfiltrate tenant data using
     * credentials it is legitimately allowed to use".
     */
    UNTRUSTED_EXTERNAL;

    /**
     * True when output from this tier must be recorded as tainted by the
     * taint-propagation layer.
     *
     * <p>Written as an exhaustive switch rather than {@code this ==
     * UNTRUSTED_EXTERNAL} on purpose: if a fourth tier is ever added (a
     * plausible one is {@code PARTNER_ATTESTED} for an MCP server a tenant has
     * explicitly trusted), this stops compiling and forces an explicit decision
     * about it, instead of silently defaulting the new tier to "untainted" -
     * which is the failure direction that costs data.
     */
    public boolean producesTaintedOutput() {
        return switch (this) {
            case TRUSTED_INTERNAL, TENANT_SCOPED -> false;
            case UNTRUSTED_EXTERNAL -> true;
        };
    }
}
