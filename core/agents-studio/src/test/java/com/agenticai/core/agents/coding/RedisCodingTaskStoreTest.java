package com.agenticai.core.agents.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

/** Key layout, TTLs and the Redis-down mapping of {@link RedisCodingTaskStore}. */
class RedisCodingTaskStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ListOperations<String, String> lists;
    private RedisCodingTaskStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        store = new RedisCodingTaskStore(redis, mapper);
    }

    private static TaskDocument document(String id) {
        TaskView view =
                new TaskView(
                        id, "agent", "RUNNING", null, "plan", "fix it", null, "ZIP", null, "docker",
                        null, null, List.of(), List.of(), null, false, null, 0, false, null, false,
                        1L, 2L);
        return new TaskDocument(view, "user-1");
    }

    @Test
    void aTaskIsStoredAsJsonUnderItsTenantScopedKeyWithTheTaskTtl() throws Exception {
        store.save("acme", document("t1"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values)
                .set(
                        eq("agentic:coding:task:acme:t1"),
                        json.capture(),
                        eq(RedisCodingTaskStore.TASK_TTL));
        assertThat(mapper.readValue(json.getValue(), TaskDocument.class).requestedBy())
                .isEqualTo("user-1");
    }

    @Test
    void aStoredTaskRoundTripsAndAMissingOrUnreadableOneIsEmpty() throws Exception {
        when(values.get("agentic:coding:task:acme:t1"))
                .thenReturn(mapper.writeValueAsString(document("t1")));
        when(values.get("agentic:coding:task:acme:bad")).thenReturn("{not json");

        assertThat(store.find("acme", "t1"))
                .get()
                .extracting(d -> d.task().taskId())
                .isEqualTo("t1");
        assertThat(store.find("acme", "gone")).isEmpty();
        assertThat(store.find("acme", "bad")).isEmpty();
    }

    @Test
    void aDocumentWithoutATaskIsTreatedAsMissing() {
        when(values.get("agentic:coding:task:acme:null")).thenReturn("null");
        when(values.get("agentic:coding:task:acme:empty")).thenReturn("{\"requestedBy\":\"u\"}");

        assertThat(store.find("acme", "null")).isEmpty();
        assertThat(store.find("acme", "empty")).isEmpty();
    }

    @Test
    void deleteAndDiffOperationsUseTheirOwnKeys() {
        when(values.get("agentic:coding:diff:acme:t1")).thenReturn("--- a\n+++ b");

        store.delete("acme", "t1");
        store.saveDiff("acme", "t1", "d");
        store.deleteDiff("acme", "t1");

        verify(redis).delete("agentic:coding:task:acme:t1");
        verify(values).set("agentic:coding:diff:acme:t1", "d", RedisCodingTaskStore.TASK_TTL);
        verify(redis).delete("agentic:coding:diff:acme:t1");
        assertThat(store.findDiff("acme", "t1")).contains("--- a\n+++ b");
        assertThat(store.findDiff("acme", "none")).isEmpty();
    }

    @Test
    void theSubjectLockIsTakenOnlyWhenFreeAndReleasedByAScript() {
        when(values.setIfAbsent(
                        "agentic:coding:active:acme:u1", "t1", RedisCodingTaskStore.LOCK_TTL))
                .thenReturn(true);
        when(values.setIfAbsent(
                        "agentic:coding:active:acme:u2", "t2", RedisCodingTaskStore.LOCK_TTL))
                .thenReturn(false);
        when(values.get("agentic:coding:active:acme:u1")).thenReturn("t1");

        assertThat(store.acquireLock("acme", "u1", "t1")).isTrue();
        assertThat(store.acquireLock("acme", "u2", "t2")).isFalse();
        assertThat(store.acquireLock("acme", "u3", "t3")).isFalse();
        assertThat(store.lockHolder("acme", "u1")).contains("t1");
        assertThat(store.lockHolder("acme", "u9")).isEmpty();

        store.releaseLock("acme", "u1", "t1");
        verify(redis)
                .execute(
                        any(org.springframework.data.redis.core.script.RedisScript.class),
                        eq(List.of("agentic:coding:active:acme:u1")),
                        eq("t1"));
    }

    @Test
    void cancelDecisionAndLivenessMarksUseShortLivedKeys() {
        when(redis.hasKey("agentic:coding:cancel:acme:t1")).thenReturn(true);
        when(redis.hasKey("agentic:coding:alive:acme:t1")).thenReturn(true);
        when(values.get("agentic:coding:decision:acme:t1")).thenReturn("APPROVE");

        store.requestCancel("acme", "t1");
        store.setDecision("acme", "t1", "APPROVE");
        store.markAlive("acme", "t1");
        store.clearDecision("acme", "t1");

        verify(values).set("agentic:coding:cancel:acme:t1", "1", RedisCodingTaskStore.TASK_TTL);
        verify(values)
                .set(
                        "agentic:coding:decision:acme:t1",
                        "APPROVE",
                        RedisCodingTaskStore.DECISION_TTL);
        verify(values).set("agentic:coding:alive:acme:t1", "1", RedisCodingTaskStore.ALIVE_TTL);
        verify(redis).delete("agentic:coding:decision:acme:t1");
        assertThat(store.isCancelRequested("acme", "t1")).isTrue();
        assertThat(store.isCancelRequested("acme", "t2")).isFalse();
        assertThat(store.isAlive("acme", "t1")).isTrue();
        assertThat(store.isAlive("acme", "t2")).isFalse();
        assertThat(store.decision("acme", "t1")).contains("APPROVE");
        assertThat(store.decision("acme", "t2")).isEmpty();
    }

    @Test
    void indexingMovesATaskToTheFrontAndCapsTheList() {
        store.index("acme", "u1", "t9");

        InOrder order = inOrder(lists, redis);
        String key = "agentic:coding:recent:acme:u1";
        order.verify(lists).remove(key, 0, "t9");
        order.verify(lists).leftPush(key, "t9");
        order.verify(lists).trim(key, 0, RedisCodingTaskStore.INDEX_MAX - 1);
        order.verify(redis).expire(key, RedisCodingTaskStore.TASK_TTL);
    }

    @Test
    void recentListsPageThroughTheIndexAndTolerateAnEmptyOne() {
        String key = "agentic:coding:recent:acme:u1";
        when(lists.range(key, 0, 4)).thenReturn(List.of("a", "b"));
        when(lists.range(key, 10, 19)).thenReturn(List.of("c"));
        when(lists.size(key)).thenReturn(42L);

        assertThat(store.recent("acme", "u1", 5)).containsExactly("a", "b");
        assertThat(store.recent("acme", "u1", 10, 10)).containsExactly("c");
        assertThat(store.recent("acme", "u1", -3, 0)).isEmpty();
        assertThat(store.recent("acme", "none", 5)).isEmpty();
        assertThat(store.recentCount("acme", "u1")).isEqualTo(42);
        assertThat(store.recentCount("acme", "none")).isZero();
    }

    @Test
    void aRedisOutageBecomesA503() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(redis)
                .delete(anyString());

        assertThatThrownBy(() -> store.find("acme", "t1"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThatThrownBy(() -> store.delete("acme", "t1"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
