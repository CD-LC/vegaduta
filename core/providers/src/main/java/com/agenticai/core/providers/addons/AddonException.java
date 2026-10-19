package com.agenticai.core.providers.addons;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error the add-on API reports with a stable machine-readable {@code code}. */
public class AddonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public AddonException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public AddonException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
