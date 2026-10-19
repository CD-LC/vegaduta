package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Delivers nothing and remembers what it was asked to deliver. For tests and dry runs. */
@Experimental
public final class NoopTransport implements Transport {

  /** One recorded delivery. */
  public record Sent(URI url, String contentType, String body) {}

  private final List<Sent> sent = new CopyOnWriteArrayList<>();

  @Override
  public void send(URI url, String contentType, String body) {
    sent.add(new Sent(url, contentType, body));
  }

  /** Everything sent so far, in order. */
  public List<Sent> sent() {
    return List.copyOf(sent);
  }

  public void clear() {
    sent.clear();
  }
}
