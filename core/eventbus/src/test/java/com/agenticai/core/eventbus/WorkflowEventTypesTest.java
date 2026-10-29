package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowEventTypes} is a free-form constants class, not an enum - the literals below are
 * what publishers put on the wire and what webhook subscribers pattern-match against, so a typo or
 * a "harmless" rename here is a silent breaking change on both sides.
 */
class WorkflowEventTypesTest {

    @Test
    void theLiteralsAreTheWireContract() {
        assertEquals("channel.whatsapp.message.received",
            WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED);
        assertEquals("channel.messenger.message.received",
            WorkflowEventTypes.CHANNEL_MESSENGER_MESSAGE_RECEIVED);
        assertEquals("channel.instagram.message.received",
            WorkflowEventTypes.CHANNEL_INSTAGRAM_MESSAGE_RECEIVED);
        assertEquals("channel.slack.message.received", WorkflowEventTypes.CHANNEL_SLACK_MESSAGE_RECEIVED);
        assertEquals("channel.discord.message.received",
            WorkflowEventTypes.CHANNEL_DISCORD_MESSAGE_RECEIVED);
        assertEquals("channel.x.message.received", WorkflowEventTypes.CHANNEL_X_MESSAGE_RECEIVED);
        assertEquals("channel.telegram.message.received",
            WorkflowEventTypes.CHANNEL_TELEGRAM_MESSAGE_RECEIVED);
        assertEquals("channel.email.message.received", WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED);
        assertEquals("channel.teams.message.received", WorkflowEventTypes.CHANNEL_TEAMS_MESSAGE_RECEIVED);
        assertEquals("channel.sms.message.received", WorkflowEventTypes.CHANNEL_SMS_MESSAGE_RECEIVED);
        assertEquals("document.generation.completed", WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED);
        assertEquals("agent.message.completed", WorkflowEventTypes.AGENT_MESSAGE_COMPLETED);
        assertEquals("email.reply_received", WorkflowEventTypes.EMAIL_REPLY_RECEIVED);
        assertEquals("email.bounced", WorkflowEventTypes.EMAIL_BOUNCED);
        assertEquals("email.auto_reply_received", WorkflowEventTypes.EMAIL_AUTO_REPLY_RECEIVED);
        assertEquals("version.promoted", WorkflowEventTypes.VERSION_PROMOTED);
        assertEquals("version.rolled_back", WorkflowEventTypes.VERSION_ROLLED_BACK);
    }

    @Test
    void everyFieldIsAPublicStaticConstantSoTheClassStaysUninstantiable() throws Exception {
        for (var field : WorkflowEventTypes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            assertEquals(true,
                Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers),
                field.getName() + " must be a public static final constant");
        }
        var ctor = WorkflowEventTypes.class.getDeclaredConstructor();
        assertEquals(true, Modifier.isPrivate(ctor.getModifiers()));
    }
}
