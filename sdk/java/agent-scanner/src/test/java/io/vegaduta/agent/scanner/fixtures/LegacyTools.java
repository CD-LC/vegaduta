package io.vegaduta.agent.scanner.fixtures;

/** Framework tools nobody classified yet. */
public class LegacyTools {

  @Tool(description = "Delete a customer record")
  public void deleteCustomer(String id) {}

  @Tool(name = "legacy_export")
  public String export(String table) {
    return "";
  }
}
