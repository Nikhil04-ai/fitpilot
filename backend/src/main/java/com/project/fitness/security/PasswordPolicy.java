package com.project.fitness.security;

import java.nio.charset.StandardCharsets;

/**
 * The upper bound every new password has to respect (PLAN P4-7).
 *
 * <p>BCrypt only hashes the first 72 bytes, and Spring Security's encoder throws an
 * {@code IllegalArgumentException} past that rather than silently truncating. That
 * exception isn't an exact {@code RuntimeException}, so GlobalExceptionHandler treated
 * it as a server fault: registering, changing or resetting to a long password was a
 * 500. It is checked up front instead, in bytes, because a 24-emoji password is 24
 * characters and 96 bytes.
 *
 * <p>Minimum lengths stay where they were (bean validation on registration, the
 * services for change and reset); this is only the limit they all lacked.
 */
public final class PasswordPolicy {

    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    /** Throws a 400-bound RuntimeException when BCrypt couldn't hash the password. */
    public static void requireHashable(String rawPassword) {
        if (rawPassword != null && rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new RuntimeException("Password is too long: keep it to 72 characters or fewer "
                    + "(fewer if it uses accented letters or emoji).");
        }
    }
}
