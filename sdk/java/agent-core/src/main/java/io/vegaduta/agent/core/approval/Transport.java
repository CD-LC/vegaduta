package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.net.URI;

/**
 * Delivers a formatted notification. {@link JdkHttpTransport} posts it; {@link NoopTransport}
 * records it for tests. A host plugs in its own for a message bus or an SDK client.
 */
@Experimental
@FunctionalInterface
public interface Transport {

  /**
   * @param url where to deliver
   * @param contentType the body's media type
   * @param body the formatted message
   * @throws RuntimeException when delivery failed; the notifier decides what that means
   */
  void send(URI url, String contentType, String body);
}
