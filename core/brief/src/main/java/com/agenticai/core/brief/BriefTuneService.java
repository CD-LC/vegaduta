package com.agenticai.core.brief;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.brief.dto.ApplyDtos.TuneResult;
import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Tune this": the planner reads the current manifest and an instruction and proposes a changed manifest.
 * The proposal is a plain {@link TuneResult} - nothing is saved or applied here. The caller accepts it by
 * sending {@code proposedBrief} (with {@code baseVersion}) to {@code POST /apply}, which validates it again,
 * saves it as the next version and applies it through the normal engine.
 *
 * <p>The model can never change the rung (it is forced back to the current one), and the proposal must pass the
 * same {@link BriefValidator} a saved brief does, so a proposal that would not apply is refused with the
 * validator's own readable errors instead of being offered.
 */
@Service
public class BriefTuneService {

    private static final int MAX_INSTRUCTION_CHARS = 2000;
    private static final int MAX_NARRATIVE_CHARS = 1500;

    private final AssistService assistService;
    private final BriefVersionStore store;
    private final BriefValidator validator;
    private final ObjectMapper objectMapper;

    public BriefTuneService(
        AssistService assistService, BriefVersionStore store, BriefValidator validator, ObjectMapper objectMapper
    ) {
        this.assistService = assistService;
        this.store = store;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public TuneResult tune(UUID briefId, String instruction) {
        String ask = instruction == null ? "" : instruction.strip();
        if (ask.isEmpty() || ask.length() > MAX_INSTRUCTION_CHARS) {
            throw new BriefValidationException("instruction", "Say what to change, in 1 to " + MAX_INSTRUCTION_CHARS + " characters.");
        }
        BriefEntity entity = store.require(briefId);
        Brief current = store.loadCurrent(entity);
        TrustRung rung = TrustRung.parseOrLowest(entity.getRung());

        String raw = assistService.complete(SYSTEM, "Instruction: " + ask + "\n\nCurrent brief:\n" + store.toJson(current));
        JsonNode reply = LlmJson.parseObject(objectMapper, raw);
        if (reply == null || !reply.path("brief").isObject()) {
            throw new BriefValidationException("tune",
                "The model's answer could not be understood - try again or rephrase the change.");
        }
        Brief proposed;
        try {
            proposed = objectMapper.treeToValue(reply.get("brief"), Brief.class);
        } catch (JsonProcessingException e) {
            throw new BriefValidationException("tune", "The model's proposal was not a readable brief - try again.");
        }
        // Autonomy is earned, never proposed: the rung stays exactly where it is.
        proposed = BriefVersionStore.withRung(proposed, rung);
        boolean applied = !BriefEntity.DRAFT.equals(entity.getStatus());
        BriefValidationResult result = validator.validate(proposed, !applied);
        if (!result.errors().isEmpty()) {
            throw new BriefValidationException(result.errors());
        }
        String narrative = reply.path("narrative").asText("");
        if (narrative.length() > MAX_NARRATIVE_CHARS) {
            narrative = narrative.substring(0, MAX_NARRATIVE_CHARS);
        }
        return new TuneResult(proposed,
            BriefJsonPatch.diffAsArray(objectMapper.valueToTree(current), objectMapper.valueToTree(proposed)),
            narrative, entity.getCurrentVersion());
    }

    private static final String SYSTEM = """
        You revise an existing "brief": one JSON manifest describing a whole AI solution (agents, workflows,
        tools, MCP servers, knowledge, secrets, channels, eval set, narrative, trust). You are given the change
        the user wants and the current brief.

        Return ONE JSON object and nothing else:
        {"brief": <the COMPLETE revised brief, same schema as the current one>, "narrative": "<two or three sentences on what you changed and why>"}

        Rules:
        - Change only what the instruction asks for. Keep every existing ref (a1, w1, t1, m1, k1, s1, p1, c1) and every
          workflow node id exactly as they are. New artifacts get the next free ref of their kind.
        - References between artifacts are by ref only, never by real id. Every ref you use must be declared in the brief.
        - Secrets are declared by NAME only. Never write a secret value, key, token or password anywhere.
        - Do not change trust.rung. Do not add approval nodes to workflow graphs; they are added automatically.
        - Keep the same top-level keys; leave unchanged sections exactly as given.
        """;
}
