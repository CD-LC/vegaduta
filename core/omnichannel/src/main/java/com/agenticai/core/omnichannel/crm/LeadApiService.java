package com.agenticai.core.omnichannel.crm;

import com.agenticai.core.omnichannel.crm.LeadController.ActivityResponse;
import com.agenticai.core.omnichannel.crm.LeadController.LeadResponse;
import com.agenticai.core.omnichannel.crm.LeadController.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing facade over {@link LeadService}: maps {@link Lead} / {@link LeadActivity} entities
 * to the controller's DTO records (and renders the CSV export) so {@link LeadController} never
 * touches JPA entities.
 */
@Service
public class LeadApiService {

    private final LeadService service;

    public LeadApiService(LeadService service) {
        this.service = service;
    }

    @Transactional(readOnly = true)
    public PageResponse list(LeadStage stage, String source, String q, int page, int size) {
        Page<Lead> result = service.list(
            stage, source, q,
            PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, LeadController.MAX_PAGE_SIZE)), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new PageResponse(
            result.getContent().stream().map(LeadApiService::toResponse).toList(),
            result.getTotalElements(), result.getNumber(), result.getSize()
        );
    }

    @Transactional
    public LeadResponse create(LeadService.LeadInput input) {
        return toResponse(service.create(input));
    }

    @Transactional(readOnly = true)
    public LeadResponse get(UUID id) {
        return toResponse(service.get(id));
    }

    @Transactional
    public LeadResponse update(UUID id, LeadService.LeadInput input) {
        return toResponse(service.update(id, input));
    }

    @Transactional
    public void delete(UUID id) {
        service.delete(id);
    }

    @Transactional
    public LeadResponse changeStage(UUID id, LeadStage stage, String note) {
        return toResponse(service.changeStage(id, stage, note));
    }

    @Transactional
    public ActivityResponse addActivity(UUID id, LeadActivityType type, String body, Map<String, Object> meta) {
        return toResponse(service.addActivity(id, type, body, meta));
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> activities(UUID id) {
        return service.activities(id).stream().map(LeadApiService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LeadService.StageTotal> pipeline() {
        return service.pipeline();
    }

    @Transactional
    public LeadService.ImportResult importRows(List<LeadCsv.ParsedRow> rows) {
        return service.importRows(rows);
    }

    @Transactional(readOnly = true)
    public String exportCsv() {
        return LeadCsv.export(service.exportAll());
    }

    private static LeadResponse toResponse(Lead l) {
        return new LeadResponse(
            l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getCompany(), l.getTitle(), l.getSource(),
            l.getStage(), l.getScore(), l.getOwner(), l.getValue(), l.tagList(), l.getNotes(),
            l.getLastContactedAt(), l.getLastRepliedAt(), l.getCreatedAt(), l.getUpdatedAt()
        );
    }

    private static ActivityResponse toResponse(LeadActivity a) {
        return new ActivityResponse(a.getId(), a.getLeadId(), a.getType(), a.getBody(), a.getMeta(), a.getCreatedAt());
    }
}
