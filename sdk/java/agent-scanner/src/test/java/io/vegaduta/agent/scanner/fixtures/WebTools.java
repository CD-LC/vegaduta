package io.vegaduta.agent.scanner.fixtures;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.RiskLabel;

/** Class-level @Governed: every tool method inherits "untrusted input, read-only". */
@Governed(risk = RiskLabel.LOW, sideEffecting = false, untrustedInput = true)
public class WebTools {

  @Tool(description = "Fetch a web page")
  public String fetchUrl(String url) {
    return "<html/>";
  }
}
