package com.agenticai.core.tenancy.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/demo/session}'s body - every field optional, and the
 * endpoint accepts no body at all. That is the product requirement: a
 * visitor gets a working sandbox from one click, with nothing typed.
 *
 * @param email OPTIONAL. Used for exactly one thing - the pre-expiry
 *     reminder - and for nothing else. No email means no reminder and a
 *     perfectly functional sandbox; it is never a precondition, and it does
 *     not create an account, a Keycloak user, or a marketing subscription.
 * @param businessName OPTIONAL. Purely cosmetic personalisation of the
 *     seeded agent's name/prompt ("Priya's Bakery assistant"). Sanitised and
 *     length-clamped before it reaches either.
 * @param persona OPTIONAL. One of the fixed one-click presets - {@code
 *     "kirana"}, {@code "clinic"}, {@code "tuition"}, {@code "restaurant"},
 *     {@code "salon"} (see {@link
 *     com.agenticai.core.tenancy.demo.DemoPersona}) - which gives the seeded
 *     agent a persona-specific system prompt with zero typing. Anything else
 *     (including a typo) is treated as absent, never as an error: nothing a
 *     visitor sends may fail sandbox creation. Composes with {@code
 *     businessName} - persona picks the prompt, businessName still names the
 *     agent.
 * @param blank OPTIONAL. {@code true} provisions the sandbox WITHOUT the
 *     seeded starter agent. The /try Agent Studio sets it: the studio builds
 *     agents from the visitor's own briefing, so a pre-seeded "Customer
 *     assistant" there is never used, and because agent visibility is by
 *     network address every seeded one showed up as a duplicate under "Also on
 *     this network" for everyone sharing the address. Absent or false keeps the
 *     legacy chat sandbox behaviour (which does need its agent).
 */
public record DemoSessionCreateRequest(
    @Email(message = "email must be a valid email address")
    @Size(max = 320, message = "email is too long")
    String email,

    @Size(max = 60, message = "businessName is too long")
    String businessName,

    @Size(max = 32, message = "persona is too long")
    String persona,

    Boolean blank
) {
    /** True only when the caller explicitly asked for a sandbox with no seeded agent. */
    public boolean wantsBlank() {
        return Boolean.TRUE.equals(blank);
    }
}
