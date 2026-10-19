package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.security.http.HttpStatusMapped;

public class MatrixNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public MatrixNotConfiguredException() {
        super("No active Matrix bot connected - POST /api/channels/matrix/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
