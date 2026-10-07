package com.project.fitness.exceptions;

/**
 * The request carried a perfectly valid JWT, but the user id inside it no longer
 * resolves to a row — the account was removed, or (far more commonly) the database
 * was reset/recreated after the browser had already saved a session.
 *
 * This is NOT a bad request: the client sent nothing wrong, and no amount of
 * retrying or correcting the payload will help. The only way out is a fresh login,
 * so it gets its own status (409) and its own message, which the frontend's
 * {@code api()} wrapper pattern-matches to clear the dead session automatically
 * (see AGENT.md §8).
 *
 * <p>Deliberately NOT thrown for "some other user doesn't exist" — looking up a
 * challenge opponent who isn't registered is an ordinary 400, and reporting it as
 * a dead session would log the caller out of their own perfectly good account.
 */
public class AccountNotFoundException extends RuntimeException {

    /** Substring the frontend matches on. Changing it breaks session self-healing. */
    public static final String MESSAGE =
            "Your session refers to an account that no longer exists in the database. "
            + "You've been signed out — please log in again.";

    private final String userId;

    public AccountNotFoundException(String userId) {
        super(MESSAGE);
        this.userId = userId;
    }

    /** For the server log only — never returned to the client. */
    public String getUserId() {
        return userId;
    }
}
