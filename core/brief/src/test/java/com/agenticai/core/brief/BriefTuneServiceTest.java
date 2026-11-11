package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.ApplyDtos.TuneResult;
import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Tune this": the instruction guard, the refusal of unreadable or invalid model answers, the rule
 * that the model can never change the trust rung, and the shape of an accepted proposal.
 */
class BriefTuneServiceTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final ObjectMapper mapper = new ObjectMapper();
    private AssistService assist;
    private BriefVersionStore store;
    private BriefValidator validator;
    private BriefTuneService service;
    private BriefEntity entity;
    private Brief current;

    @BeforeEach
    void setUp() throws Exception {
        assist = mock(AssistService.class);
        store = mock(BriefVersionStore.class);
        validator = mock(BriefValidator.class);
        service = new BriefTuneService(assist, store, validator, mapper);

        current = BriefFixtures.valid().build();
        entity = new BriefEntity(ID, "Invoice chaser", "goal", "en", "UNDER_WATCH", "tester");
        entity.touch("Invoice chaser", "goal", "en", 3);
        when(store.require(ID)).thenReturn(entity);
        when(store.loadCurrent(entity)).thenReturn(current);
        when(store.toJson(current)).thenReturn(mapper.writeValueAsString(current));
        when(validator.validate(any(Brief.class), anyBoolean()))
                .thenReturn(new BriefValidationResult(List.of(), List.of(), List.of()));
    }

    private String reply(String title, String rung, String narrative) throws Exception {
        Brief changed = BriefFixtures.valid().build();
        Brief proposed =
                new Brief(
                        changed.briefVersion(),
                        title,
                        changed.goal(),
                        changed.locale(),
                        changed.clarifications(),
                        new Brief.Trust(rung, List.of("w1.n3"), List.of()),
                        changed.agents(),
                        changed.procedures(),
                        changed.workflows(),
                        changed.tools(),
                        changed.mcpServers(),
                        changed.knowledge(),
                        changed.secrets(),
                        changed.channels(),
                        changed.evalSet(),
                        changed.rehearsal(),
                        changed.narrative());
        return "{\"brief\":"
                + mapper.writeValueAsString(proposed)
                + ",\"narrative\":\""
                + narrative
                + "\"}";
    }

    @Test
    void anEmptyOrOversizedInstructionIsRefusedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.tune(ID, null))
                .isInstanceOf(BriefValidationException.class);
        assertThatThrownBy(() -> service.tune(ID, "   "))
                .isInstanceOf(BriefValidationException.class);
        assertThatThrownBy(() -> service.tune(ID, "x".repeat(2001)))
                .isInstanceOf(BriefValidationException.class);
        verifyNoInteractions(store, assist);
    }

    @Test
    void anAcceptedProposalCarriesTheDiffTheNarrativeAndTheBaseVersion() throws Exception {
        when(assist.complete(anyString(), anyString()))
                .thenReturn("```json\n" + reply("Renamed", "UNDER_WATCH", "Renamed it.") + "\n```");

        TuneResult result = service.tune(ID, "  rename it  ");

        assertThat(result.proposedBrief().title()).isEqualTo("Renamed");
        assertThat(result.narrative()).isEqualTo("Renamed it.");
        assertThat(result.baseVersion()).isEqualTo(3);
        assertThat(result.diff().toString()).contains("/title");
        verify(assist)
                .complete(
                        anyString(),
                        org.mockito.ArgumentMatchers.contains("Instruction: rename it"));
    }

    @Test
    void theModelCannotRaiseTheTrustRung() throws Exception {
        when(assist.complete(anyString(), anyString()))
                .thenReturn(reply("Same", "AUTONOMOUS", "Tried to go autonomous."));

        TuneResult result = service.tune(ID, "make it autonomous");

        assertThat(result.proposedBrief().trust().rung()).isEqualTo("UNDER_WATCH");
    }

    @Test
    void anOverlongNarrativeIsTruncated() throws Exception {
        when(assist.complete(anyString(), anyString()))
                .thenReturn(reply("T", "UNDER_WATCH", "n".repeat(3000)));

        assertThat(service.tune(ID, "go").narrative()).hasSize(1500);
    }

    @Test
    void aDraftIsValidatedWithCapsAndAnAppliedBriefWithout() throws Exception {
        when(assist.complete(anyString(), anyString())).thenReturn(reply("T", "UNDER_WATCH", "ok"));

        service.tune(ID, "go");
        verify(validator).validate(any(Brief.class), eq(true));

        entity.setStatus(BriefEntity.APPLIED);
        service.tune(ID, "go");
        verify(validator).validate(any(Brief.class), eq(false));
    }

    @Test
    void anUnreadableAnswerIsRefused() {
        when(assist.complete(anyString(), anyString())).thenReturn("sorry, no idea");
        assertThatThrownBy(() -> service.tune(ID, "go"))
                .isInstanceOf(BriefValidationException.class)
                .hasMessageContaining("could not be understood");
    }

    @Test
    void anAnswerWhoseBriefIsNotAnObjectIsRefused() {
        when(assist.complete(anyString(), anyString())).thenReturn("{\"brief\":\"nope\"}");
        assertThatThrownBy(() -> service.tune(ID, "go"))
                .isInstanceOf(BriefValidationException.class);
    }

    @Test
    void aBriefThatDoesNotMatchTheSchemaIsRefused() {
        when(assist.complete(anyString(), anyString()))
                .thenReturn("{\"brief\":{\"briefVersion\":\"not-a-number\"}}");
        assertThatThrownBy(() -> service.tune(ID, "go"))
                .isInstanceOf(BriefValidationException.class)
                .hasMessageContaining("not a readable brief");
    }

    @Test
    void aProposalThatFailsValidationIsRefusedWithTheValidatorsErrors() throws Exception {
        when(assist.complete(anyString(), anyString())).thenReturn(reply("T", "UNDER_WATCH", "x"));
        when(validator.validate(any(Brief.class), anyBoolean()))
                .thenReturn(
                        new BriefValidationResult(
                                List.of(new Issue("agents[a1]", "bad")), List.of(), List.of()));

        assertThatThrownBy(() -> service.tune(ID, "go"))
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e ->
                                assertThat(e.getErrors())
                                        .extracting(Issue::section)
                                        .containsExactly("agents[a1]"));
    }
}
