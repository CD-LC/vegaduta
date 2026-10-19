package io.vegaduta.agent.scanner.fixtures;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.RiskLabel;

/** An exfiltration sink that is MEDIUM risk, so the default policy would not gate it. */
public class MailTools {

  @Tool(description = "Send an email")
  @Governed(risk = RiskLabel.MEDIUM, exfiltrationSink = true)
  public void sendEmail(String to, String body) {}

  /** Found through @Governed alone, no framework annotation. */
  @Governed(risk = RiskLabel.HIGH, exfiltrationSink = true)
  public void postWebhook(String url, String payload) {}
}
