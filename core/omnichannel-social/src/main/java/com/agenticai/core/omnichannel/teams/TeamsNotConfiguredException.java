package com.agenticai.core.omnichannel.teams;

public class TeamsNotConfiguredException extends RuntimeException {

    public TeamsNotConfiguredException() {
        super("No active Teams bot connected - POST /api/channels/teams/credential first.");
    }
}
