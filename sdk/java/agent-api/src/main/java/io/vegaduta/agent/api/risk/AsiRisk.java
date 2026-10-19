package io.vegaduta.agent.api.risk;

/**
 * The OWASP Top 10 for Agentic Applications 2026 (ASI01 to ASI10). Every SDK control names the
 * items it mitigates, so audit events and reports can show coverage.
 *
 * <p>Titles follow the published list; see <a
 * href="https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/">OWASP
 * GenAI Security Project</a>.
 */
public enum AsiRisk {
  ASI01("Agent Goal Hijack"),
  ASI02("Tool Misuse and Exploitation"),
  ASI03("Identity and Privilege Abuse"),
  ASI04("Agentic Supply Chain Vulnerabilities"),
  ASI05("Unexpected Code Execution"),
  ASI06("Memory and Context Poisoning"),
  ASI07("Insecure Inter-Agent Communication"),
  ASI08("Cascading Failures"),
  ASI09("Human-Agent Trust Exploitation"),
  ASI10("Rogue Agents");

  private final String title;

  AsiRisk(String title) {
    this.title = title;
  }

  public String title() {
    return title;
  }

  /** The identifier as printed in the OWASP list, for example {@code ASI02}. */
  public String id() {
    return name();
  }
}
