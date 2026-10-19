package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCellResult;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabSimilarity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisAgentLabRunStore} against a mocked template: keys, TTLs and the stored JSON. The
 * Lua compare-and-delete itself only runs inside a real Redis and is not exercised here.
 */
class AgentLabRunStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisAgentLabRunStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        store = new RedisAgentLabRunStore(redis, objectMapper);
    }

    private static LabRunDocument document() {
        List<List<Double>> matrix = new ArrayList<>();
        matrix.add(Arrays.asList(1.0, null));
        matrix.add(Arrays.asList(null, 1.0));
        return new LabRunDocument(
            new LabRunResponse(
                "run-1", "agent-1", "CONSISTENCY", "RUNNING", 3, 2, false, null,
                1_790_000_000_000L, 1_790_000_012_000L, null,
                List.of(
                    new LabCellResult("k0", "DONE", "Thirty days.", false, 0.75, "SCORED", "Fine.", null, 2140L),
                    new LabCellResult("k1", "PENDING", null, false, null, "PENDING", null, null, null)),
                new LabSimilarity("JUDGE", "PARTIAL", List.of("k0", "k1"), matrix)),
            "u1");
    }

    @Test
    void aSavedDocumentReadsBackEqualIncludingNullVerdicts() {
        LabRunDocument doc = document();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        store.save("t1", doc);

        verify(values).set(eq("agentic:lab:run:t1:run-1"), json.capture(), eq(Duration.ofMinutes(60)));
        when(values.get("agentic:lab:run:t1:run-1")).thenReturn(json.getValue());
        assertEquals(Optional.of(doc), store.find("t1", "run-1"));
    }

    /** The response record is what the controller returns, so its JSON is the web contract. */
    @Test
    void theRunResponseSerialisesWithTheContractFieldNamesAndExplicitNulls() throws Exception {
        JsonNode run = objectMapper.readTree(objectMapper.writeValueAsString(document().run()));

        assertEquals(
            List.of("runId", "agentId", "kind", "status", "plannedCalls", "completedCalls", "cancelRequested",
                "errorMessage", "createdAtMs", "updatedAtMs", "finishedAtMs", "cells", "similarity"),
            names(run));
        assertTrue(run.get("errorMessage").isNull());
        assertTrue(run.get("finishedAtMs").isNull());
        assertTrue(run.get("createdAtMs").isIntegralNumber());
        assertEquals(
            List.of("key", "status", "answer", "answerTruncated", "score", "scoreStatus", "scoreFeedback",
                "errorMessage", "ms"),
            names(run.get("cells").get(0)));
        assertTrue(run.get("cells").get(1).get("score").isNull());
        assertEquals(List.of("method", "status", "keys", "matrix"), names(run.get("similarity")));
        assertTrue(run.get("similarity").get("matrix").get(0).get(1).isNull());
        assertEquals(1.0, run.get("similarity").get("matrix").get(0).get(0).asDouble());
    }

    @Test
    void theCapabilitiesAndCancelBodiesUseTheContractFieldNames() throws Exception {
        JsonNode caps = objectMapper.readTree(objectMapper.writeValueAsString(
            new AgentLabDtos.LabCapabilitiesResponse(true, null, 60, 120, 16, 8, 60)));
        assertEquals(
            List.of("allowed", "reason", "maxCells", "maxCallsPerRun", "maxSystemPrompts", "maxSimilarityCells",
                "runTtlMinutes"),
            names(caps));
        assertTrue(caps.get("reason").isNull());

        JsonNode cancel = objectMapper.readTree(objectMapper.writeValueAsString(
            new AgentLabDtos.LabCancelResponse("run-1", true)));
        assertEquals(List.of("runId", "cancelRequested"), names(cancel));
    }

    @Test
    void aRunRequestDeserialisesFromTheWebPayload() throws Exception {
        AgentLabDtos.LabRunRequest request = objectMapper.readValue("""
            {"kind":"KNOCKOUT","systemPrompts":["base","variant"],
             "cells":[{"key":"b0:i0","promptIndex":0,"message":"Q","expected":null},
                      {"key":"k0","promptIndex":null,"message":"Q",
                       "history":[{"role":"user","content":"Hi"}]}],
             "scoring":"JUDGE","similarity":"NONE"}""", AgentLabDtos.LabRunRequest.class);

        assertEquals("KNOCKOUT", request.kind());
        assertEquals(2, request.cells().size());
        assertEquals(Integer.valueOf(0), request.cells().get(0).promptIndex());
        assertNull(request.cells().get(0).history());
        assertNull(request.cells().get(1).promptIndex());
        assertEquals("user", request.cells().get(1).history().get(0).role());
    }

    @Test
    void aMissingDocumentIsEmpty() {
        when(values.get("agentic:lab:run:t1:run-1")).thenReturn(null);

        assertEquals(Optional.empty(), store.find("t1", "run-1"));
    }

    @Test
    void anUnreadableDocumentIsTreatedAsExpired() {
        when(values.get("agentic:lab:run:t1:run-1")).thenReturn("{not json");

        assertEquals(Optional.empty(), store.find("t1", "run-1"));
    }

    @Test
    void aRedisFailureOnReadPropagatesInsteadOfLookingLikeNotFound() {
        when(values.get("agentic:lab:run:t1:run-1")).thenThrow(new QueryTimeoutException("redis timeout"));

        // Not an empty Optional (that would read as "run not found"): a clean 503 that keeps the Redis cause.
        org.springframework.web.server.ResponseStatusException e = assertThrows(
            org.springframework.web.server.ResponseStatusException.class, () -> store.find("t1", "run-1"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        assertEquals(QueryTimeoutException.class, e.getCause().getClass());
    }

    @Test
    void deleteRemovesTheRunKey() {
        store.delete("t1", "run-1");

        verify(redis).delete("agentic:lab:run:t1:run-1");
    }

    @Test
    void theLockIsSetOnlyIfAbsentWithTheRunIdAsItsValue() {
        when(values.setIfAbsent("agentic:lab:active:t1:u1", "run-1", Duration.ofMinutes(25))).thenReturn(true);
        when(values.setIfAbsent("agentic:lab:active:t1:u1", "run-2", Duration.ofMinutes(25))).thenReturn(false);

        assertTrue(store.acquireLock("t1", "u1", "run-1"));
        assertFalse(store.acquireLock("t1", "u1", "run-2"));
    }

    @Test
    void aNullSetIfAbsentAnswerIsNotALock() {
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

        assertFalse(store.acquireLock("t1", "u1", "run-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseIsACompareAndDeleteScriptKeyedByTheLockAndTheRunId() {
        store.releaseLock("t1", "u1", "run-1");

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("agentic:lab:active:t1:u1")), eq("run-1"));
        assertEquals(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            script.getValue().getScriptAsString());
        assertEquals(Long.class, script.getValue().getResultType());
        // Never a plain DEL: that would free a newer run's lock.
        verify(redis, org.mockito.Mockito.never()).delete(anyList());
        verify(redis, org.mockito.Mockito.never()).delete(any(String.class));
    }

    @Test
    void lockHolderReadsTheLockValue() {
        when(values.get("agentic:lab:active:t1:u1")).thenReturn("run-1");

        assertEquals(Optional.of("run-1"), store.lockHolder("t1", "u1"));
        assertEquals(Optional.empty(), store.lockHolder("t1", "u2"));
    }

    @Test
    void theCancelFlagIsWrittenWithATtlAndReadWithoutBeingConsumed() {
        store.requestCancel("t1", "run-1");
        verify(values).set("agentic:lab:cancel:t1:run-1", "1", Duration.ofMinutes(30));

        when(redis.hasKey("agentic:lab:cancel:t1:run-1")).thenReturn(true);
        assertTrue(store.isCancelRequested("t1", "run-1"));
        assertTrue(store.isCancelRequested("t1", "run-1"));
        assertFalse(store.isCancelRequested("t1", "run-2"));
    }

    @Test
    void theLivenessMarkIsWrittenWithAShortTtlUnderItsOwnKey() {
        store.markAlive("t1", "run-1");
        verify(values).set("agentic:lab:alive:t1:run-1", "1", Duration.ofSeconds(90));

        when(redis.hasKey("agentic:lab:alive:t1:run-1")).thenReturn(true);
        assertTrue(store.isAlive("t1", "run-1"));
        assertFalse(store.isAlive("t1", "run-2"));
        // Three refresh periods: two refreshes in a row can be lost.
        assertEquals(
            Duration.ofSeconds(3L * AgentLabRunService.HEARTBEAT_SECONDS), RedisAgentLabRunStore.ALIVE_TTL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceIfUnchangedIsOneScriptThatComparesWhatTheReaderSawBeforeItWrites() throws Exception {
        LabRunDocument read = document();
        LabRunResponse r = read.run();
        LabRunDocument failed = new LabRunDocument(
            new LabRunResponse(r.runId(), r.agentId(), r.kind(), "FAILED", r.plannedCalls(), r.completedCalls(),
                false, "Stopped.", r.createdAtMs(), r.updatedAtMs() + 1, r.updatedAtMs() + 1, r.cells(),
                r.similarity()),
            read.requestedBy());
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertTrue(store.replaceIfUnchanged("t1", read, failed));

        verify(redis).execute(
            script.capture(), eq(List.of("agentic:lab:run:t1:run-1")),
            args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        // What the reader saw: status, updatedAtMs, completedCalls - then the replacement and its TTL.
        List<Object> sent = args.getAllValues();
        assertEquals(5, sent.size());
        assertEquals("RUNNING", sent.get(0));
        assertEquals("1790000012000", sent.get(1));
        assertEquals("2", sent.get(2));
        assertEquals(failed, objectMapper.readValue((String) sent.get(3), LabRunDocument.class));
        assertEquals("3600", sent.get(4));
        String lua = script.getValue().getScriptAsString();
        assertEquals(Long.class, script.getValue().getResultType());
        // Read, compare and write in the one script - never a GET here and a SET there.
        assertTrue(lua.contains("redis.call('get', KEYS[1])"), lua);
        assertTrue(lua.contains("doc.run.status ~= ARGV[1]"), lua);
        assertTrue(lua.contains("doc.run.updatedAtMs ~= tonumber(ARGV[2])"), lua);
        assertTrue(lua.contains("doc.run.completedCalls ~= tonumber(ARGV[3])"), lua);
        assertTrue(lua.contains("redis.call('set', KEYS[1], ARGV[4], 'EX', ARGV[5])"), lua);
        assertTrue(lua.indexOf("~= ARGV[1]") < lua.indexOf("redis.call('set'"), "compare before write");
        verify(values, org.mockito.Mockito.never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void replaceIfUnchangedReportsALostRace() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertFalse(store.replaceIfUnchanged("t1", document(), document()));

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertFalse(store.replaceIfUnchanged("t1", document(), document()));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
