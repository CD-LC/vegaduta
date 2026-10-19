package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the virtual-thread carrier pinning seen in the 32-user kind benchmark:
 * Spring's {@code BufferingClientHttpResponseWrapper.getBody} was the frame in every pinned trace.
 * Main code must not construct {@code BufferingClientHttpRequestFactory} (use {@link
 * RequestBufferingClientHttpRequestFactory}, which buffers the request only).
 */
class NoSpringResponseBufferingGuardTest {

    @Test
    void mainCodeOfThisModuleNeverBuildsSpringsBufferingFactory() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> offenders =
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(
                                    p -> {
                                        try {
                                            return Files.readString(p)
                                                    .contains(
                                                            "new BufferingClientHttpRequestFactory(");
                                        } catch (IOException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "BufferingClientHttpRequestFactory also buffers responses and pins virtual-thread carriers: "
                            + offenders);
        }
    }
}
