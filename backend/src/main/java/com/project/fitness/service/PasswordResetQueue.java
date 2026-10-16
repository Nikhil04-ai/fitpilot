package com.project.fitness.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs password-reset requests after the response has gone (PLAN P5-12).
 *
 * <p>{@code POST /api/auth/forgot-password} answers the same words whether or not the
 * address has an account, but it used to do the work first: for a real account it wrote
 * a token and sent the email before replying, so the reply came measurably later (7 ms
 * with mail switched off, and the mail provider's whole round trip with it on). How long
 * the answer took said whether the address was registered. Now the controller hands the
 * address here and replies at once, in the same time for every address.
 *
 * <p>One thread, so requests are handled strictly in order. Two requests for the same
 * account running side by side could each retire the outstanding link and each save a
 * new one, leaving two live links where "asking again invalidates the previous link"
 * promises one. The queue is bounded so a flood can't grow memory without limit. A
 * queued request is one address, so the bound is generous: at the auth rate limit of
 * 10 a minute per client, filling it takes a coordinated flood from many clients, and
 * until then a backlog only makes emails late. A request that doesn't fit is dropped and
 * logged, and the person can simply ask again. (100 overflowed in a deliberate local burst
 * of 240 back-to-back requests with the limit lifted.)
 */
@Slf4j
@Component
public class PasswordResetQueue {

    static final int CAPACITY = 1000;

    private final PasswordResetService passwordResetService;
    private final ThreadPoolExecutor executor;

    public PasswordResetQueue(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "password-reset");
                    t.setDaemon(true);
                    return t;
                },
                (runnable, pool) -> log.warn("Password reset queue full; a request was dropped."));
    }

    /** Queues a reset for this address and returns immediately. */
    public void submit(String email) {
        executor.execute(() -> {
            try {
                // Through the Spring proxy, so requestReset keeps its transaction.
                passwordResetService.requestReset(email);
            } catch (RuntimeException e) {
                // Nobody is waiting for an answer any more, so the log is the only place
                // a failure can go.
                log.error("Password reset request failed", e);
            }
        });
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
