package com.project.fitness.security;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Identity checks that also confirm the account still exists (PLAN P4-18).
 *
 * <p>{@link CurrentUser} only proves who the token says you are. A token outlives its
 * account (a reset database, a deleted user), and a read that never looked the user up
 * answered {@code 200} with an empty page, so the person stayed "signed in" to a screen
 * that showed nothing. The 409 {@link AccountNotFoundException} produces is what the
 * frontend recovers from, so a read has to reach it.
 *
 * <p>For controllers, beside the identity check they already make. Services that
 * already load the user don't need it, which is why this is not applied everywhere:
 * one more query per request is the price, so it is spent only where nothing else
 * looks the user up. {@code DeadSessionReadsIntegrationTest} finds any read that
 * still skips it.
 */
@Component
@RequiredArgsConstructor
public class AccountGuard {

    private final UserRepository userRepository;

    /** {@link CurrentUser#requireSelf}, then proves the account exists. */
    public String requireSelf(String pathUserId) {
        return requireExists(CurrentUser.requireSelf(pathUserId));
    }

    /** {@link CurrentUser#id()}, then proves the account exists. */
    public String requireCurrent() {
        return requireExists(CurrentUser.id());
    }

    private String requireExists(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new AccountNotFoundException(userId);
        }
        return userId;
    }
}
