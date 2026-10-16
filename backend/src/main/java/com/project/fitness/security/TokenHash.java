package com.project.fitness.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How a bearer secret is stored: the SHA-256 of it, as 64 lowercase hex characters.
 *
 * <p>Refresh tokens and password-reset tokens are both password equivalents, so only
 * this digest goes in the database. A read of the table, from a backup or a leaked
 * dump, then yields nothing that can be presented back to the API.
 *
 * <p>SHA-256, not BCrypt. BCrypt is deliberately slow to make guessing a human-chosen
 * password expensive. These tokens are random (a UUID's 122 bits, or 256 bits for a
 * reset), so there is nothing to guess, and the slowness would only give an attacker a
 * way to tie up threads by submitting garbage tokens. A plain digest also keeps the
 * lookup a single indexed equality match.
 */
public final class TokenHash {

    private TokenHash() {}

    public static String of(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing, nothing here is safe.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
