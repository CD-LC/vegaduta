package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.device.ReadDeviceAppUsageToolCallback;
import com.agenticai.core.agents.tools.device.ReadDeviceNotificationsToolCallback;
import com.agenticai.core.agents.tools.device.ReadDeviceScreenToolCallback;
import com.agenticai.core.agents.tools.device.ReadDeviceSmsToolCallback;
import com.agenticai.core.agents.tools.media.GeneratedAttachmentResolver;
import com.agenticai.core.agents.tools.messaging.ChannelControlPort;
import com.agenticai.core.agents.tools.messaging.ChannelMediaSendPort;
import com.agenticai.core.agents.tools.messaging.ChannelMessageQueryPort;
import com.agenticai.core.agents.tools.messaging.ChannelMessagesToolCallback;
import com.agenticai.core.agents.tools.messaging.ChannelSendMessageToolCallback;
import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.agents.tools.messaging.ChannelSessionActionToolCallback;
import com.agenticai.core.agents.tools.messaging.ChannelSessionsToolCallback;
import com.agenticai.core.agents.tools.messaging.ChannelsOverviewToolCallback;
import com.agenticai.core.agents.tools.messaging.ForwardAttachmentToolCallback;
import com.agenticai.core.agents.tools.messaging.HumanHandoffPort;
import com.agenticai.core.agents.tools.messaging.RequestHumanToolCallback;
import com.agenticai.core.agents.tools.messaging.SearchConversationHistoryToolCallback;
import com.agenticai.core.agents.tools.messaging.TelegramMessagesToolCallback;
import com.agenticai.core.devices.DeviceDataIngestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
final class AgentChannelToolResolver {

    private final AgentToolGuard toolGuard;

    // #604 ("read all messages from telegram" not offered as a tool) -
    // ObjectProvider for the same reason every other optional capability
    // here uses one: core/omnichannel (which provides the real
    // implementation - see ChannelMessageQueryPort's javadoc for the
    // cross-module inversion this depends on) isn't guaranteed present in
    // every context this bean is constructed in (e.g. slim test contexts).
    private final ObjectProvider<ChannelMessageQueryPort> channelMessageQueryPort;
    // "forward a generated attachment to any channel" - same ObjectProvider
    // inversion as channelMessageQueryPort above, for the same reason: the
    // real per-channel send logic lives in core/omnichannel (see
    // ChannelMediaSendPort's javadoc). GeneratedAttachmentResolver lives in
    // core/agents itself (this module already depends on imagegen/
    // elevenlabs/sarvam/storage) so it's a plain required field, not a
    // provider - only the send side is optional.
    private final GeneratedAttachmentResolver generatedAttachmentResolver;
    private final ObjectProvider<ChannelMediaSendPort> channelMediaSendPort;
    // request_human agent tool - same cross-module inversion as
    // channelMessageQueryPort/channelMediaSendPort above, for the same
    // reason (see HumanHandoffPort's javadoc): the real escalation logic
    // lives in core/omnichannel's HandoffEscalationService, which already
    // depends on core/agents.
    private final ObjectProvider<HumanHandoffPort> humanHandoffPort;
    // Agent channel control (channel_send_message / channels_overview /
    // channel_list_sessions / channel_session_action) - same ObjectProvider
    // inversion as the ports above: the governed send path and the session
    // administration surface live in core/omnichannel (see ChannelSendPort /
    // ChannelControlPort javadocs). Absent port = slim context = no tools.
    private final ObjectProvider<ChannelSendPort> channelSendPort;
    private final ObjectProvider<ChannelControlPort> channelControlPort;
    // #157 follow-up - ObjectProvider for the same reason every other
    // optional capability here uses one: core/devices is always on the
    // classpath in the real app now that agents depends on it directly, but
    // this keeps slim test contexts (no DeviceDataIngestionService bean)
    // constructible the same way channelMessageQueryPort/graphMemoryService
    // already are.
    private final ObjectProvider<DeviceDataIngestionService> deviceDataIngestionService;

    AgentChannelToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<ChannelMessageQueryPort> channelMessageQueryPort,
            GeneratedAttachmentResolver generatedAttachmentResolver,
            ObjectProvider<ChannelMediaSendPort> channelMediaSendPort,
            ObjectProvider<HumanHandoffPort> humanHandoffPort,
            ObjectProvider<ChannelSendPort> channelSendPort,
            ObjectProvider<ChannelControlPort> channelControlPort,
            ObjectProvider<DeviceDataIngestionService> deviceDataIngestionService) {
        this.toolGuard = toolGuard;
        this.channelMessageQueryPort = channelMessageQueryPort;
        this.generatedAttachmentResolver = generatedAttachmentResolver;
        this.channelMediaSendPort = channelMediaSendPort;
        this.humanHandoffPort = humanHandoffPort;
        this.channelSendPort = channelSendPort;
        this.channelControlPort = channelControlPort;
        this.deviceDataIngestionService = deviceDataIngestionService;
    }

    private ToolCallback guardAndLog(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return toolGuard.guardAndLog(
                raw, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous);
    }

    /**
     * #604 - "when I asked to read all messages from telegram it is not choosing any telegram tool"
     * - unlike Google's tools (gated on whether the tenant connected an OAuth account), there's no
     * separate "connected" check here: every tenant with the Telegram channel wired at all already
     * has {@code core/omnichannel} on the classpath, so this is only ever empty when {@link
     * ChannelMessageQueryPort}'s bean isn't present (see that interface's javadoc for the
     * cross-module wiring).
     */
    List<ToolCallback> resolveTelegramTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        ChannelMessageQueryPort port = channelMessageQueryPort.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        ToolCallback readMessages = new TelegramMessagesToolCallback(port);
        // Cross-session conversation search (P1) - same port, same gating.
        // This is the tool that answers "what did we discuss with this
        // customer before", which the Redis session store structurally
        // cannot (TTL-bounded, per-session only).
        ToolCallback searchHistory = new SearchConversationHistoryToolCallback(port);
        return List.of(
                guardAndLog(
                        readMessages,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                guardAndLog(
                        searchHistory,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * Agent channel control ("agents should be able to control everything in channels") - the full
     * command surface, not just reads: {@code channels_overview} (what's connected), {@code
     * channel_list_sessions} (who's talking to us), {@code channel_session_action}
     * (approve/unpair/rebind a sender's trust grant), {@code channel_send_message} (outbound on any
     * connected channel) and {@code channel_read_messages} (the channel-generic sibling of
     * telegram_read_messages). Gated the same ObjectProvider way as everything else in this family
     * - the tools simply don't exist when core/omnichannel's ports aren't wired, rather than
     * existing and failing. Sends additionally pass through the platform's own approval gate (see
     * ChannelSendPort's javadoc), and every tool's shipped classification is declared in
     * BuiltInToolClassifications next to this wiring - "control" here means governed control, not a
     * bypass.
     */
    List<ToolCallback> resolveChannelControlTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<ToolCallback> raw = new ArrayList<>();
        ChannelControlPort controlPort = channelControlPort.getIfAvailable();
        if (controlPort != null) {
            raw.add(new ChannelsOverviewToolCallback(controlPort));
            raw.add(new ChannelSessionsToolCallback(controlPort));
            raw.add(new ChannelSessionActionToolCallback(controlPort));
        }
        ChannelSendPort sendPort = channelSendPort.getIfAvailable();
        if (sendPort != null) {
            // Attribution is the agent's own id, supplied by the provider -
            // the model can neither see nor override it (same reason
            // RequestHumanToolCallback threads sessionId through the ctor).
            raw.add(new ChannelSendMessageToolCallback(sendPort, "agent:" + agentId));
        }
        ChannelMessageQueryPort queryPort = channelMessageQueryPort.getIfAvailable();
        if (queryPort != null) {
            raw.add(new ChannelMessagesToolCallback(queryPort));
        }
        return raw.stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * "Forward a generated attachment to any channel" - same ObjectProvider gating as {@link
     * #resolveTelegramTools}, only empty when {@link ChannelMediaSendPort}'s bean isn't present
     * (see that interface's javadoc for the cross-module wiring). {@link
     * #generatedAttachmentResolver} itself is always present (a plain core/agents-local bean), so
     * this tool's absence is purely a "core/omnichannel isn't on the classpath" signal, not a
     * partial/broken state.
     */
    List<ToolCallback> resolveForwardAttachmentTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        ChannelMediaSendPort port = channelMediaSendPort.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        ToolCallback forwardAttachment =
                new ForwardAttachmentToolCallback(generatedAttachmentResolver, port);
        return List.of(
                guardAndLog(
                        forwardAttachment,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * {@code request_human} - same ObjectProvider gating as {@link #resolveTelegramTools}/ {@link
     * #resolveForwardAttachmentTools}, only empty when {@link HumanHandoffPort}'s bean isn't
     * present (i.e. {@code core/omnichannel} isn't on the classpath - see that interface's javadoc
     * for the cross-module wiring). Unlike those two, this is offered unconditionally once the port
     * exists - there's no per-tenant "connected" precondition, since escalating to a human is a
     * platform-level capability, not one that depends on an external account being linked. {@code
     * sessionId} is threaded straight through, unchanged, to {@link RequestHumanToolCallback} - see
     * that class's javadoc for why the model itself never supplies or can override it.
     */
    List<ToolCallback> resolveHumanHandoffTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        HumanHandoffPort port = humanHandoffPort.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        ToolCallback requestHuman = new RequestHumanToolCallback(port, sessionId);
        return List.of(
                guardAndLog(
                        requestHuman,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * #157 follow-up ("agents should be able to execute commands, action and perform all
     * activities") - the four {@code read_device_*} tools (see {@code
     * com.agenticai.core.agents.tools.device} package). Unlike Google's tools (gated on a
     * tenant-level OAuth connection), there's no "connected" precondition here beyond the module
     * being present - a tenant with zero paired devices simply gets a clear "no paired device
     * found" result from the tool itself (see {@code DeviceToolSupport#noDeviceError}) rather than
     * the tool being hidden entirely, consistent with how {@link #resolveTelegramTools} always
     * offers its tool once the module is on the classpath.
     */
    List<ToolCallback> resolveDeviceTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        DeviceDataIngestionService service = deviceDataIngestionService.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        List<ToolCallback> raw =
                List.of(
                        new ReadDeviceScreenToolCallback(service),
                        new ReadDeviceNotificationsToolCallback(service),
                        new ReadDeviceAppUsageToolCallback(service),
                        new ReadDeviceSmsToolCallback(service));
        return raw.stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }
}
