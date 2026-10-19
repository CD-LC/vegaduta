package com.agenticai.core.omnichannel.session;

import com.agenticai.core.agents.tools.messaging.ChannelMessageQueryPort;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * #604 - the {@code core/omnichannel} side of {@link ChannelMessageQueryPort}'s cross-module
 * inversion (see that interface's javadoc for why this can't just be a direct dependency). {@code
 * core/omnichannel} already depends on {@code core/agents}, so implementing an agents-defined
 * interface here - and letting Spring wire it back into {@code AgentToolProvider} as a plain bean
 * of that interface's type - adds no new compile-time edge in either direction.
 */
@Component
public class ChannelMessageQueryPortImpl implements ChannelMessageQueryPort {

    private final ChannelMessageLogRepository messageLogRepository;

    public ChannelMessageQueryPortImpl(ChannelMessageLogRepository messageLogRepository) {
        this.messageLogRepository = messageLogRepository;
    }

    @Override
    public List<ChannelMessageRecord> recentMessages(String channel, int limit) {
        Optional<ChannelType> resolved = parseChannel(channel);
        if (resolved.isEmpty()) {
            return List.of();
        }
        List<ChannelMessageLog> rows =
                messageLogRepository.findTop50ByChannelOrderByCreatedAtDesc(resolved.get());
        int cap = Math.max(1, Math.min(limit, rows.size()));
        return rows.stream()
                .limit(cap)
                .map(
                        row ->
                                new ChannelMessageRecord(
                                        row.getExternalId(), row.getBody(), row.getCreatedAt()))
                .toList();
    }

    @Override
    public List<ConversationSearchHit> searchMessages(
            String channel, String externalId, String query, int limit) {
        String needle = sanitizeLikeQuery(query);
        if (needle.isEmpty()) {
            return List.of();
        }
        Optional<ChannelType> resolved = parseChannel(channel);
        if (channel != null && !channel.isBlank() && resolved.isEmpty()) {
            // A caller who names a channel that doesn't exist gets an empty
            // result, not an unfiltered cross-channel search - the difference
            // between "no hits" and "surprisingly, hits from channels you
            // didn't ask about".
            return List.of();
        }
        String sender = externalId == null || externalId.isBlank() ? null : externalId.trim();
        List<ChannelMessageLog> rows;
        if (resolved.isPresent() && sender != null) {
            rows =
                    messageLogRepository
                            .findTop50ByChannelAndExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                                    resolved.get(), sender, needle);
        } else if (resolved.isPresent()) {
            rows =
                    messageLogRepository
                            .findTop50ByChannelAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                                    resolved.get(), needle);
        } else if (sender != null) {
            rows =
                    messageLogRepository
                            .findTop50ByExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                                    sender, needle);
        } else {
            rows =
                    messageLogRepository.findTop50ByBodyContainingIgnoreCaseOrderByCreatedAtDesc(
                            needle);
        }
        int cap = Math.max(1, Math.min(limit, rows.size()));
        return rows.stream()
                .limit(cap)
                .map(
                        row ->
                                new ConversationSearchHit(
                                        row.getChannel().name().toLowerCase(java.util.Locale.ROOT),
                                        row.getExternalId(),
                                        row.getDirection() == null
                                                ? "inbound"
                                                : row.getDirection()
                                                        .name()
                                                        .toLowerCase(java.util.Locale.ROOT),
                                        row.getAuthor(),
                                        row.getBody(),
                                        row.getCreatedAt()))
                .toList();
    }

    /**
     * {@code Containing} finders generate {@code LIKE %needle%} - a caller's own {@code %}/{@code
     * _} characters would act as wildcards and inflate results (harmless but wrong), and a
     * wildcard-only needle ("%%%") would scan the table for a meaningless match-everything search.
     * Stripped so the search is a literal-substring search, as the tool contract states.
     */
    private static String sanitizeLikeQuery(String query) {
        if (query == null) {
            return "";
        }
        String cleaned = query.replace("%", "").replace("_", "").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private static Optional<ChannelType> parseChannel(String channel) {
        if (channel == null) {
            return Optional.empty();
        }
        return Arrays.stream(ChannelType.values())
                .filter(type -> type.name().equalsIgnoreCase(channel.trim()))
                .findFirst();
    }
}
