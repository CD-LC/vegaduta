package io.vegaduta.examples.governed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A support agent that can look up orders and refund them. Refunds need a human; look-ups do
 * not. Run it, then:
 *
 * <pre>
 * curl 'localhost:8080/chat?user=alice&amp;q=What is the status of order 42?'
 * curl 'localhost:8080/chat?user=alice&amp;q=Refund order 42, 500 cents'
 * curl localhost:8080/vegaduta/approvals
 * curl -X POST localhost:8080/vegaduta/approvals/{id}/approve -H 'content-type: application/json' -d '{"decidedBy":"bob"}'
 * </pre>
 */
@SpringBootApplication
public class GovernedToolsApplication {

  public static void main(String[] args) {
    SpringApplication.run(GovernedToolsApplication.class, args);
  }
}
