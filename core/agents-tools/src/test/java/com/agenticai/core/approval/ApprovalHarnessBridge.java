package com.agenticai.core.approval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds the REAL approval stack ({@link HumanApprovalGateService}, its package-private {@code
 * ApprovalRequestRecorder}, {@link ApprovalPolicyService}, {@link ApprovalActionExecutorRegistry})
 * over in-memory stand-ins for the two database repositories and the audit/event sinks. Lives in
 * this package only because the recorder is package-private; the end-to-end harness reaches it
 * through this class.
 *
 * <p>What is faked: persistence (a map instead of Postgres), the audit-log and event-bus writes
 * (recorded, not stored). What is real: every state transition, the replay envelope, the executor
 * registry lookup and the executor dispatch.
 */
public final class ApprovalHarnessBridge {

    private final Map<UUID, ApprovalRequest> store = new LinkedHashMap<>();
    private final Map<String, Boolean> policyOverrides = new LinkedHashMap<>();
    private final List<ApplicationEvent> publishedEvents = new CopyOnWriteArrayList<>();
    private final ApprovalRequestRepository requestRepository =
            mock(ApprovalRequestRepository.class);
    private final ApprovalPolicyOverrideRepository overrideRepository =
            mock(ApprovalPolicyOverrideRepository.class);
    private final List<Consumer<ApplicationEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher eventPublisher =
            event -> {
                if (event instanceof ApplicationEvent applicationEvent) {
                    publishedEvents.add(applicationEvent);
                    eventListeners.forEach(listener -> listener.accept(applicationEvent));
                }
            };
    private final ApprovalPolicyService policyService =
            new ApprovalPolicyService(overrideRepository);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApprovalHarnessBridge() {
        when(requestRepository.save(any(ApprovalRequest.class)))
                .thenAnswer(
                        inv -> {
                            ApprovalRequest request = inv.getArgument(0);
                            synchronized (store) {
                                if (request.getId() == null) {
                                    ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
                                }
                                store.put(request.getId(), request);
                            }
                            return request;
                        });
        when(requestRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(get(inv.getArgument(0))));
        when(requestRepository.findByIdForUpdate(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(get(inv.getArgument(0))));
        when(requestRepository.findByRequestedByAndStatusOrderByCreatedAtAsc(
                        org.mockito.ArgumentMatchers.anyString(),
                        any(ApprovalStatus.class)))
                .thenAnswer(
                        inv -> {
                            synchronized (store) {
                                return store.values().stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getRequestedBy()))
                                        .filter(r -> r.getStatus() == inv.getArgument(1))
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                        ApprovalRequest::getCreatedAt))
                                        .toList();
                            }
                        });
        when(overrideRepository.findByActionType(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv -> {
                            Boolean gated = policyOverrides.get(inv.<String>getArgument(0));
                            return gated == null
                                    ? Optional.empty()
                                    : Optional.of(
                                            new ApprovalPolicyOverride(inv.getArgument(0), gated));
                        });
    }

    /**
     * The tenant policy "this action type needs a human" ({@code PUT /api/approvals/policy/**}).
     */
    public void requireApprovalFor(String actionType) {
        policyOverrides.put(actionType, true);
    }

    /** The tenant policy "this action type runs without a human" (an explicit override, as an admin sets it). */
    public void allowWithoutApproval(String actionType) {
        policyOverrides.put(actionType, false);
    }

    public ApprovalPolicyService policyService() {
        return policyService;
    }

    public ApprovalRequestRepository requestRepository() {
        return requestRepository;
    }

    public ApplicationEventPublisher eventPublisher() {
        return eventPublisher;
    }

    /** Builds the gate service over {@code executors}, the same bean list the registry collects. */
    public HumanApprovalGateService gateService(List<ApprovalActionExecutor> executors) {
        ApprovalRequestRecorder recorder =
                new ApprovalRequestRecorder(
                        requestRepository,
                        mock(AuditLogService.class),
                        eventPublisher,
                        mock(WorkflowEventPublisher.class));
        return new HumanApprovalGateService(
                policyService,
                requestRepository,
                new ApprovalActionExecutorRegistry(executors),
                recorder,
                mock(FeedbackService.class),
                objectMapper,
                5,
                30,
                3600);
    }

    private ApprovalRequest get(UUID id) {
        synchronized (store) {
            return store.get(id);
        }
    }

    /** Every approval request ever recorded, oldest first. */
    public List<ApprovalRequest> requests() {
        synchronized (store) {
            return new ArrayList<>(store.values());
        }
    }

    /**
     * Delivers every event the approval stack and its executor publish to {@code listener}, in the
     * publishing thread, as Spring would deliver it to an {@code @EventListener} (for example an
     * agent job's approval sweep).
     */
    public void onEvent(Consumer<ApplicationEvent> listener) {
        eventListeners.add(listener);
    }

    /**
     * Application events the approval stack and its executor published (e.g. execution recorded).
     */
    public List<ApplicationEvent> publishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
