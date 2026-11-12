package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * WP6 REST surface: a VIEWER sees only ACTIVE + TRUSTED items, the review queue and decisions take
 * EDITOR, reads are dark while the unified-memory flags are off, and erase runs the lineage
 * cascade. Backed by the in-memory storage, so the store invariants hold exactly as in Postgres.
 */
class MemoryItemControllerTest {

    private final Principal alice = () -> "alice";
    private StoreFixture f;
    private boolean editor = true;
    private MemoryItemController controller;
    private MemoryItemExportController exportController;

    /**
     * The {@link MemoryAgentAccess} the agents-turn adapter would provide for this test's caller.
     */
    private final MemoryAgentAccess access =
            new MemoryAgentAccess() {
                @Override
                public void requireViewer(UUID agentId) {}

                @Override
                public void requireEditor(UUID agentId) {
                    if (!editor) {
                        throw new ResourceAccessDeniedException();
                    }
                }

                @Override
                public boolean isEditor(UUID agentId) {
                    return editor;
                }
            };

    @BeforeEach
    void setUp() {
        f = new StoreFixture();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("access", access);
        ObjectProvider<MemoryAgentAccess> provider = beans.getBeanProvider(MemoryAgentAccess.class);
        controller = new MemoryItemController(provider, f.gate, f.review);
        exportController = new MemoryItemExportController(f.props, f.review);
        editor = true;
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private void viewerOnly() {
        editor = false;
    }

    private UUID pending(String text) {
        return f.sink.propose(f.proposal(MemoryKind.EPISODIC, text)).orElseThrow().id();
    }

    // ------------------------------------------------------------------ list / get

    @Test
    void aViewerSeesOnlyActiveTrustedItems() {
        viewerOnly();
        UUID active = f.active(MemoryKind.EPISODIC, "shipped on friday");
        UUID pending = pending("unreviewed");
        assertThat(controller.list(f.agent, null, null, 100))
                .extracting(MemoryItemController.ItemView::id)
                .containsExactly(active);
        assertThat(controller.get(f.agent, active).getBody().item().status()).isEqualTo("ACTIVE");
        // A pending row is invisible to a viewer: same 404 as a missing id.
        assertThat(controller.get(f.agent, pending).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(f.agent, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aViewerAskingForTheReviewQueueNeedsEditor() {
        viewerOnly();
        assertThatThrownBy(() -> controller.list(f.agent, "PENDING", null, 100))
                .isInstanceOf(ResourceAccessDeniedException.class);
    }

    @Test
    void anEditorSeesEveryStatusPlusLineageAndEvents() {
        UUID active = f.active(MemoryKind.EPISODIC, "a");
        UUID pending = pending("p");
        assertThat(controller.list(f.agent, null, null, 100))
                .extracting(MemoryItemController.ItemView::id)
                .containsExactlyInAnyOrder(active, pending);
        assertThat(controller.list(f.agent, "PENDING", null, 100))
                .extracting(MemoryItemController.ItemView::id)
                .containsExactly(pending);
        MemoryItemController.DetailView detail = controller.get(f.agent, active).getBody();
        assertThat(detail.events())
                .extracting(MemoryItemController.EventView::event)
                .containsExactly("CREATED", "APPROVED");
    }

    @Test
    void readsAreDarkWhileTheFlagsAreOff() {
        f.active(MemoryKind.EPISODIC, "a");
        f.props.setEnabled(false);
        assertThatThrownBy(() -> controller.list(f.agent, null, null, 100))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ------------------------------------------------------------------ decisions

    @Test
    void approveRecordsTheAuthenticatedHuman() {
        UUID id = pending("p");
        assertThat(controller.approve(f.agent, id, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        MemoryItem row = f.storage.items.get(id);
        assertThat(row.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(row.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(row.approvedBy()).isEqualTo("alice");
        // Re-approving an ACTIVE row conflicts, not 500s.
        assertThat(controller.approve(f.agent, id, alice).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aViewerCannotDecide() {
        viewerOnly();
        UUID id = pending("p");
        assertThatThrownBy(() -> controller.approve(f.agent, id, alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.erase(f.agent, id, alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
    }

    @Test
    void rejectAndRevertMoveBetweenStatuses() {
        UUID rejected = pending("r");
        assertThat(controller.reject(f.agent, rejected, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(f.storage.items.get(rejected).status()).isEqualTo(MemoryItemStatus.REJECTED);

        UUID active = f.active(MemoryKind.EPISODIC, "v");
        assertThat(controller.revert(f.agent, active, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(f.storage.items.get(active).status()).isEqualTo(MemoryItemStatus.REVERTED);
    }

    @Test
    void decidingWithoutAPrincipalIsForbidden() {
        UUID id = pending("p");
        assertThat(controller.approve(f.agent, id, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ edit and pin

    @Test
    void patchCreatesANewPendingVersionAndAnswersWithIt() {
        UUID active = f.active(MemoryKind.EPISODIC, "old text");
        var response =
                controller.edit(
                        f.agent,
                        active,
                        new MemoryItemController.EditRequest("  new text  "),
                        alice);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MemoryItemController.ItemView next = response.getBody();
        assertThat(next.content()).isEqualTo("new text");
        assertThat(next.status()).isEqualTo("PENDING");
        assertThat(next.trust()).isEqualTo("QUARANTINED");
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.supersedesId()).isEqualTo(active);
        // the active predecessor keeps serving until the new version is approved
        assertThat(f.storage.items.get(active).status()).isEqualTo(MemoryItemStatus.ACTIVE);
    }

    @Test
    void patchRejectsBlankTextUnknownItemsScreenedTextAndWrongStatus() {
        UUID active = f.active(MemoryKind.EPISODIC, "old");
        assertThatThrownBy(
                        () ->
                                controller.edit(
                                        f.agent,
                                        active,
                                        new MemoryItemController.EditRequest("  "),
                                        alice))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.edit(f.agent, active, null, alice))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(
                        controller
                                .edit(
                                        f.agent,
                                        UUID.randomUUID(),
                                        new MemoryItemController.EditRequest("x"),
                                        alice)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        controller
                                .edit(
                                        f.agent,
                                        active,
                                        new MemoryItemController.EditRequest("x".repeat(5000)),
                                        alice)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        UUID reverted = f.active(MemoryKind.EPISODIC, "r");
        controller.revert(f.agent, reverted, alice);
        assertThat(
                        controller
                                .edit(
                                        f.agent,
                                        reverted,
                                        new MemoryItemController.EditRequest("y"),
                                        alice)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(
                        controller
                                .edit(
                                        f.agent,
                                        active,
                                        new MemoryItemController.EditRequest("z"),
                                        null)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void editAndPinTakeEditorAndAreDarkWhileTheFlagIsOff() {
        UUID active = f.active(MemoryKind.EPISODIC, "a");
        viewerOnly();
        assertThatThrownBy(
                        () ->
                                controller.edit(
                                        f.agent,
                                        active,
                                        new MemoryItemController.EditRequest("b"),
                                        alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.pin(f.agent, active, alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.unpin(f.agent, active, alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
        editor = true;
        f.props.setEnabled(false);
        assertThatThrownBy(
                        () ->
                                controller.edit(
                                        f.agent,
                                        active,
                                        new MemoryItemController.EditRequest("b"),
                                        alice))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.pin(f.agent, active, alice))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.unpin(f.agent, active, alice))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(f.storage.items.get(active).pinned()).isFalse();
    }

    @Test
    void pinAndUnpinToggleTheFlagAndShowInTheView() {
        UUID active = f.active(MemoryKind.EPISODIC, "keep");
        assertThat(controller.pin(f.agent, active, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(f.agent, active).getBody().item().pinned()).isTrue();
        assertThat(controller.unpin(f.agent, active, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(f.agent, active).getBody().item().pinned()).isFalse();
        UUID pending = pending("p");
        assertThat(controller.pin(f.agent, pending, alice).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(controller.pin(f.agent, UUID.randomUUID(), alice).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.pin(f.agent, active, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ erasure

    @Test
    void eraseRemovesTheItemItsDerivedRowsAndTheirHistory() {
        // Root item plus a derived summary that names it as its memory_item source.
        UUID root = f.active(MemoryKind.EPISODIC, "the customer prefers email");
        UUID derived = UUID.randomUUID();
        MemoryItem derivedItem =
                MemoryItem.pending(
                        derived,
                        f.agent,
                        MemoryKind.SELF_MODEL,
                        "derived summary",
                        MemoryWriter.DISTILLER,
                        "DISTILLER",
                        null,
                        null,
                        List.of(),
                        true,
                        1,
                        List.of(),
                        1,
                        null,
                        f.clock.instant(),
                        null);
        f.storage.insertPending(
                derivedItem,
                StoreFixture.vector("derived summary"),
                List.of(
                        new MemoryItemSource(
                                "memory_item", root.toString(), MemoryTrustLevel.TRUSTED)),
                MemoryEvent.of(
                        derived,
                        f.agent,
                        MemoryEvent.Type.CREATED,
                        MemoryActor.system("test"),
                        null,
                        MemoryItemStatus.PENDING,
                        "KIND=SELF_MODEL",
                        f.clock.instant()));

        assertThat(controller.erase(f.agent, root, alice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(f.storage.items).doesNotContainKeys(root, derived);
        assertThat(f.storage.lineage).doesNotContainKeys(root, derived);
        // History of both rows is gone; one ERASED tombstone per erased id remains.
        assertThat(f.storage.events(root))
                .extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.ERASED);
        assertThat(f.storage.events(derived))
                .extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.ERASED);
        assertThat(controller.erase(f.agent, root, alice).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void eraseLeavesUnrelatedItemsAlone() {
        UUID keep = f.active(MemoryKind.EPISODIC, "keep");
        UUID gone = f.active(MemoryKind.EPISODIC, "gone");
        controller.erase(f.agent, gone, alice);
        assertThat(f.storage.items).containsKey(keep);
        assertThat(f.storage.events(keep)).hasSize(2);
    }

    // ------------------------------------------------------------------ per-user export

    @Test
    void exportReturnsEveryItemAboutTheCallerAcrossStatuses() {
        UUID active = f.active(MemoryKind.EPISODIC, "about user-1");
        UUID pending = pending("also about user-1");
        // An item about someone else does not leak into the export.
        f.sink.propose(
                new MemoryWriteProposal(
                        f.agent,
                        "bob",
                        MemoryKind.EPISODIC,
                        "about bob",
                        MemoryProvenanceRecord.TRUSTED,
                        null,
                        false));
        var response = exportController.export(() -> "user-1", 1000);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment");
        assertThat(response.getBody().items())
                .extracting(MemoryItemController.ItemView::id)
                .containsExactlyInAnyOrder(active, pending);
    }

    @Test
    void exportIsDarkWhileThePlatformFlagIsOff() {
        f.props.setEnabled(false);
        assertThatThrownBy(() -> exportController.export(alice, 100))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
