package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.RunContext;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Thread-bound {@link RunContext} for code paths that cannot pass one explicitly (a tool invoked by
 * Spring AI's tool loop on the calling thread). {@link GovernanceAdvisor} binds it for the duration
 * of a {@code ChatClient} call; applications can bind it themselves around any unit of work.
 *
 * <pre>{@code
 * try (var scope = RunContextHolder.bind(RunContext.builder().principal("alice").build())) {
 *   chatClient.prompt("...").call().content();
 * }
 * }</pre>
 */
public final class RunContextHolder {

  private static final ThreadLocal<@Nullable RunContext> CURRENT = new ThreadLocal<>();

  private RunContextHolder() {}

  public static Optional<RunContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Binds {@code context} to the current thread until the returned scope is closed. */
  public static Scope bind(RunContext context) {
    RunContext previous = CURRENT.get();
    CURRENT.set(context);
    return () -> {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    };
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
