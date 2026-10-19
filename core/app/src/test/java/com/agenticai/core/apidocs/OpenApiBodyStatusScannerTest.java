package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@link OpenApiBodyStatusScanner} reads the success status a handler sets in its body: one
 * constant 2xx status is reported, anything ambiguous is not.
 */
class OpenApiBodyStatusScannerTest {

    @SuppressWarnings("unused")
    static class Handlers {
        ResponseEntity<String> created() {
            return ResponseEntity.created(URI.create("/x/1")).body("x");
        }

        ResponseEntity<String> statusConstant() {
            return ResponseEntity.status(HttpStatus.CREATED).body("x");
        }

        ResponseEntity<String> statusInt() {
            return ResponseEntity.status(201).body("x");
        }

        ResponseEntity<String> statusValueOf() {
            return ResponseEntity.status(HttpStatusCode.valueOf(202)).body("x");
        }

        ResponseEntity<Void> accepted() {
            return ResponseEntity.accepted().build();
        }

        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }

        ResponseEntity<String> constructor() {
            return new ResponseEntity<>("x", HttpStatus.CREATED);
        }

        ResponseEntity<String> createdOrNotFound(boolean found) {
            if (!found) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body("x");
        }

        ResponseEntity<String> okOrNoContent(Optional<String> v) {
            return v.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
        }

        ResponseEntity<String> fromVariable(HttpStatus status) {
            return ResponseEntity.status(status).body("x");
        }

        CompletableFuture<ResponseEntity<String>> inLambda() {
            return CompletableFuture.supplyAsync(() -> "x")
                    .thenApply(b -> ResponseEntity.status(HttpStatus.ACCEPTED).body(b));
        }

        ResponseEntity<String> viaHelper() {
            return createdHelper("x");
        }

        private ResponseEntity<String> createdHelper(String body) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        void servletStatus(HttpServletResponse response) {
            response.setStatus(HttpServletResponse.SC_CREATED);
        }

        String plain() {
            return "x";
        }
    }

    private final OpenApiBodyStatusScanner scanner = new OpenApiBodyStatusScanner();

    private Optional<Integer> status(String name) {
        for (Method m : Handlers.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return scanner.successStatus(m);
            }
        }
        throw new AssertionError("no method " + name);
    }

    @Test
    void reportsTheOneConstantStatus() {
        assertThat(status("created")).contains(201);
        assertThat(status("statusConstant")).contains(201);
        assertThat(status("statusInt")).contains(201);
        assertThat(status("statusValueOf")).contains(202);
        assertThat(status("accepted")).contains(202);
        assertThat(status("noContent")).contains(204);
        assertThat(status("constructor")).contains(201);
        assertThat(status("servletStatus")).contains(201);
    }

    @Test
    void ignoresErrorStatusesAlongsideTheSuccessOne() {
        assertThat(status("createdOrNotFound")).contains(201);
    }

    @Test
    void followsLambdasAndSameClassHelpers() {
        assertThat(status("inLambda")).contains(202);
        assertThat(status("viaHelper")).contains(201);
    }

    @Test
    void staysSilentWhenAmbiguousOrUnknown() {
        assertThat(status("okOrNoContent")).as("200 via method reference plus 204").isEmpty();
        assertThat(status("fromVariable")).isEmpty();
        assertThat(status("plain")).isEmpty();
    }
}
