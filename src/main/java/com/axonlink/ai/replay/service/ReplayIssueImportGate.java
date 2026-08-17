package com.axonlink.ai.replay.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Prevents the incremental import and full refresh from running concurrently. */
@Component
public class ReplayIssueImportGate {

    private final Semaphore permit;

    public ReplayIssueImportGate() {
        this(new Semaphore(1));
    }

    ReplayIssueImportGate(Semaphore permit) {
        this.permit = permit;
    }

    public <T> T execute(CheckedOperation<T> operation) throws IOException {
        if (!permit.tryAcquire()) {
            throw new ReplayIssueImportBusyException();
        }
        try {
            return operation.run();
        } finally {
            permit.release();
        }
    }

    @FunctionalInterface
    public interface CheckedOperation<T> {
        T run() throws IOException;
    }
}
