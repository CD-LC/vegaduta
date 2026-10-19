package io.vegaduta.agent.api.hook;

/**
 * A policy hook. Hooks are how a host, a security team or an external policy engine observe and
 * steer the pipeline without changing tool code.
 */
@FunctionalInterface
public interface Hook {

  HookDecision apply(HookEvent event);
}
