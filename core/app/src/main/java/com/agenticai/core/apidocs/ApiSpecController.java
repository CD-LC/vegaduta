package com.agenticai.core.apidocs;

import com.agenticai.core.agentspi.ApiSpecProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves this install's own OpenAPI 3.1 document, generated from the running code (the same
 * generator that writes {@code docs/api/openapi.json}), so the spec a person or agent reads always
 * matches the build that is running. Authenticated like the rest of {@code /api}; also what the
 * Assistant's {@code vegaduta_api_guide} tool reads (through {@link ApiSpecProvider}).
 */
@RestController
public class ApiSpecController implements ApiSpecProvider {

    private volatile String cached;

    @Override
    public String specJson() {
        String json = cached;
        if (json == null) {
            synchronized (this) {
                json = cached;
                if (json == null) {
                    json = OpenApiSpecGenerator.withDetectedVersion().generateJson();
                    cached = json;
                }
            }
        }
        return json;
    }

    @GetMapping(value = "/api/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String openApi() {
        return specJson();
    }
}
