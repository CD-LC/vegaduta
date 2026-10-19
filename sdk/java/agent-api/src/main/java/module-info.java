/**
 * Governance contracts for AI agents. No framework types; the JDK and JSpecify only.
 *
 * @see io.vegaduta.agent.api
 */
module io.vegaduta.agent.api {
  // transitive: @Nullable appears in exported signatures, so consumers compiling against this
  // module on the module path see the annotation type without declaring it themselves.
  requires static transitive org.jspecify;

  exports io.vegaduta.agent.api;
  exports io.vegaduta.agent.api.annotation;
  exports io.vegaduta.agent.api.approval;
  exports io.vegaduta.agent.api.audit;
  exports io.vegaduta.agent.api.autonomy;
  exports io.vegaduta.agent.api.budget;
  exports io.vegaduta.agent.api.gate;
  exports io.vegaduta.agent.api.guardrail;
  exports io.vegaduta.agent.api.hook;
  exports io.vegaduta.agent.api.journal;
  exports io.vegaduta.agent.api.mandate;
  exports io.vegaduta.agent.api.observe;
  exports io.vegaduta.agent.api.risk;
  exports io.vegaduta.agent.api.tool;
}
