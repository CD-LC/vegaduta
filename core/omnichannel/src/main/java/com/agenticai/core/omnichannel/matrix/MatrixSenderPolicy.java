package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.omnichannel.session.ChannelSenderPolicy;
import com.agenticai.core.omnichannel.session.SenderAllowList;

/**
 * Matrix's {@link ChannelSenderPolicy}, built from the credential row. Default-deny in the sense
 * every channel here is: in {@link MatrixSenderMode#PAIRING} (default) a stranger can only ever
 * reach the admin-approval pairing flow; in {@link MatrixSenderMode#ALLOWLIST} a stranger is not
 * even answered. The bot's own events are always dropped (a bot must never talk to itself).
 */
public final class MatrixSenderPolicy implements ChannelSenderPolicy {

    private final String botUserId;
    private final MatrixSenderMode senderMode;
    private final MatrixJoinPolicy joinPolicy;
    private final SenderAllowList allowList;

    public MatrixSenderPolicy(
            String botUserId,
            MatrixSenderMode senderMode,
            MatrixJoinPolicy joinPolicy,
            String allowedSenders) {
        this.botUserId = botUserId;
        this.senderMode = senderMode == null ? MatrixSenderMode.PAIRING : senderMode;
        this.joinPolicy = joinPolicy == null ? MatrixJoinPolicy.OFF : joinPolicy;
        this.allowList = SenderAllowList.parse(allowedSenders);
    }

    public static MatrixSenderPolicy of(MatrixCredential credential) {
        return new MatrixSenderPolicy(
                credential.getBotUserId(),
                credential.getSenderMode(),
                credential.getJoinPolicy(),
                credential.getAllowedSenders());
    }

    @Override
    public Decision evaluate(String senderId) {
        if (senderId == null || senderId.isBlank() || senderId.equalsIgnoreCase(botUserId)) {
            return Decision.DROP;
        }
        if (senderMode == MatrixSenderMode.PAIRING) {
            return Decision.ADMIT;
        }
        return allowList.matches(senderId) ? Decision.ADMIT : Decision.DROP;
    }

    /** Join on invite only when the operator turned it on AND the inviter is on the allow-list. */
    public boolean mayJoinInviteFrom(String inviterId) {
        return joinPolicy == MatrixJoinPolicy.ALLOWLIST
                && inviterId != null
                && !inviterId.equalsIgnoreCase(botUserId)
                && allowList.matches(inviterId);
    }
}
