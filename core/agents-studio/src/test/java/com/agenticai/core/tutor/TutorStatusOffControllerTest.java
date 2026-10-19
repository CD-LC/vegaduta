package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.tutor.dto.TutorDtos.TutorStatus;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** SE-35: the availability probe answers 200 enabled:false while the tutor is off. */
class TutorStatusOffControllerTest {

    private final TutorStatusOffController controller = new TutorStatusOffController();

    @Test
    void onlyExistsWhenTheFlagIsOffOrMissing() {
        ConditionalOnProperty condition = TutorStatusOffController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("app.tutor", condition.prefix());
        assertEquals("enabled", condition.name()[0]);
        assertEquals("false", condition.havingValue());
        assertEquals(true, condition.matchIfMissing());
    }

    @Test
    void reportsDisabled() {
        Principal principal = () -> "u1";
        TutorStatus status = controller.status(principal);
        assertFalse(status.enabled());
        assertFalse(status.generationAvailable());
    }

    @Test
    void stillRequiresASignedInMember() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.status(null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
