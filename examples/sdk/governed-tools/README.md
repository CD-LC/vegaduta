# Example: governed tools in a Spring Boot agent

A support agent with two tools. `orderStatus` is read-only and runs freely; `refund` is
high-risk and waits for a human. Approvals, effects and audit go to H2; everything the SDK
does is visible in the log under `io.vegaduta.agent.audit`.

```bash
# 1. build the SDK once (not yet on Maven Central)
mvn -f sdk/java/pom.xml -DskipTests install

# 2. run the example (needs Ollama with `ollama pull llama3.2`, or change the model starter)
mvn -f examples/sdk/governed-tools/pom.xml spring-boot:run

# 3. talk to it
curl 'localhost:8080/chat?user=alice&q=What is the status of order 42?'
curl 'localhost:8080/chat?user=alice&q=Refund order 42, 500 cents'
#   -> the model reports the refund is awaiting approval; nothing was refunded

# 4. review and decide (four-eyes: alice cannot approve her own request)
curl localhost:8080/vegaduta/approvals
curl -X POST localhost:8080/vegaduta/approvals/<id>/approve \
     -H 'content-type: application/json' -d '{"decidedBy":"bob"}'
#   -> the refund runs now, the result is journaled, the audit log shows APPROVED then SUCCESS
```

The only SDK-specific code in the application is the `@Governed` annotation on the two tool
methods and the one line that passes the caller's identity into the `ChatClient` call. The
test in `src/test` shows how to verify the governance behaviour without a model.
