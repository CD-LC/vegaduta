package com.agenticai.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.knowledge.KnowledgeDocument;
import com.agenticai.core.knowledge.KnowledgeDocumentRepository;
import com.agenticai.core.omnichannel.contact.ChannelContact;
import com.agenticai.core.omnichannel.contact.ChannelContactRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * The capped list endpoints (runs, contacts, KB documents, approvals) rely on Spring Data deriving
 * "ORDER BY ... DESC" from the method name and applying the {@link Pageable} argument as the
 * query's LIMIT. There is no JPA slice / Testcontainers infrastructure in this repo (no H2, no
 * Postgres image in the build), so this test pins the repository contract with Spring Data's own
 * query-method machinery instead of a database: each capped method must (1) derive newest-first
 * ordering from its name, (2) not hard-code its own Top/First limit, and (3) accept a {@link
 * Pageable} and return a plain {@code List} (so the LIMIT is applied, with no extra count query).
 * It does NOT execute SQL; see the commit message for what is unverified.
 */
class CappedListQueryDerivationTest {

    private record Case(
            Class<?> repo, Class<?> entity, String name, Class<?>[] args, String sortProperty) {}

    private static Stream<Case> cases() {
        return Stream.of(
                new Case(
                        ApprovalRequestRepository.class,
                        ApprovalRequest.class,
                        "findByStatusOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {ApprovalStatus.class, Pageable.class},
                        "createdAt"),
                new Case(
                        ApprovalRequestRepository.class,
                        ApprovalRequest.class,
                        "findAllByOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {Pageable.class},
                        "createdAt"),
                new Case(
                        ChannelContactRepository.class,
                        ChannelContact.class,
                        "findAllByOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {Pageable.class},
                        "createdAt"),
                new Case(
                        ChannelContactRepository.class,
                        ChannelContact.class,
                        "findByChannelOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {ChannelType.class, Pageable.class},
                        "createdAt"),
                new Case(
                        ChannelContactRepository.class,
                        ChannelContact.class,
                        "findByGroupIdOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {UUID.class, Pageable.class},
                        "createdAt"),
                new Case(
                        KnowledgeDocumentRepository.class,
                        KnowledgeDocument.class,
                        "findByCollectionIdOrderByCreatedAtDescIdDesc",
                        new Class<?>[] {UUID.class, Pageable.class},
                        "createdAt"),
                new Case(
                        WorkflowRunRepository.class,
                        WorkflowRun.class,
                        "findByWorkflowIdOrderByStartedAtDescIdDesc",
                        new Class<?>[] {UUID.class, Pageable.class},
                        "startedAt"));
    }

    @Test
    void everyCappedMethodIsNewestFirstPageableAndUnhardcoded() throws Exception {
        List<Case> all = cases().toList();
        assertEquals(7, all.size());
        for (Case c : all) {
            Method m = c.repo().getMethod(c.name(), c.args());

            PartTree tree = new PartTree(c.name(), c.entity());
            Sort.Order order = tree.getSort().getOrderFor(c.sortProperty());
            assertTrue(
                    order != null && order.isDescending(),
                    c.name() + " must order " + c.sortProperty() + " DESC");
            Sort.Order tie = tree.getSort().getOrderFor("id");
            assertTrue(
                    tie != null && tie.isDescending(),
                    c.name() + " must break ties on id DESC (stable offset paging)");
            assertFalse(
                    tree.isLimiting(),
                    c.name() + " must not hard-code Top/First; the Pageable is the limit");

            QueryMethod qm =
                    new QueryMethod(
                            m,
                            new DefaultRepositoryMetadata(c.repo()),
                            new SpelAwareProxyProjectionFactory());
            assertTrue(
                    qm.getParameters().hasPageableParameter(), c.name() + " must take a Pageable");
            assertFalse(
                    qm.isPageQuery(),
                    c.name() + " must return List (Page would add a count query)");
            assertFalse(qm.isSliceQuery(), c.name() + " must return List");
            assertTrue(
                    List.class.isAssignableFrom(m.getReturnType()),
                    c.name() + " must return a List");
        }
    }
}
