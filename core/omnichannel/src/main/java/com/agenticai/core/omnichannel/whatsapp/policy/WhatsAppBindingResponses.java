package com.agenticai.core.omnichannel.whatsapp.policy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** HTTP mapping of a {@link WhatsAppBindingPolicy.Outcome}, shared by both binding endpoints. */
public final class WhatsAppBindingResponses {

    private WhatsAppBindingResponses() {}

    public static ResponseEntity<Map<String, Object>> toResponse(WhatsAppBindingPolicy.Outcome o) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (o.status()) {
            case BOUND:
                body.put("code", "BOUND");
                body.put("warnings", o.reasons());
                return ResponseEntity.ok(body);
            case TASK_REQUIRED:
                body.put("code", "WHATSAPP_TASK_REQUIRED");
                body.put(
                        "message",
                        "Declare what this WhatsApp agent is for before attaching it to a number.");
                body.put(
                        "validTasks",
                        Arrays.stream(WhatsAppTask.values()).map(Enum::name).toList());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
            case OPEN_ENDED_WARNING:
                body.put("code", "WHATSAPP_OPEN_ENDED_AGENT");
                body.put(
                        "message",
                        "This agent looks like an open-ended assistant. WhatsApp's Business Solution"
                                + " Terms restrict general-purpose AI assistants. Use a task-specific"
                                + " prompt, or resend with acknowledgeOpenEnded=true to proceed (the"
                                + " override is logged).");
                body.put("reasons", o.reasons());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            default:
                body.put("code", "AGENT_NOT_FOUND");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }
}
