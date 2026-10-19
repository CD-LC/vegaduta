package com.agenticai.core.chat;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a block inside one transaction. {@link LlmCallLogService#record} used to be
 * {@code @Transactional} itself; it now decides first whether to hand the write to {@link
 * StreamEndExecutor}, and the transaction has to open on whichever thread runs the write, so the
 * boundary lives here.
 */
@Component
public class LlmCallLogTransactions {

    @Transactional
    public void run(Runnable work) {
        work.run();
    }
}
