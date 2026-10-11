package com.project.fitness.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.Key;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class JwtUtils {

    // FIX 2: secret and expiration read from application.properties / env vars
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration.ms}")
    private int jwtExpirationMs;

    /** Short, because it rides in every token. */
    private static final String TOKEN_VERSION_CLAIM = "tv";

    /**
     * Issues an access token that is good for as long as the account's token version
     * is still {@code tokenVersion} (PLAN P4-3). Production code passes
     * {@code user.getTokenVersion()}.
     */
    public String generateToken(String userId, String role, int tokenVersion) {
        return Jwts.builder()
                .subject(userId)
                .claim("roles", List.of(role))
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + jwtExpirationMs))
                .signWith(key())
                .compact();
    }

    /**
     * A token at version 0, which is where every new account starts. For tests and for
     * nothing else: a real token must carry the user's current version, or it dies the
     * first time the password changes.
     */
    public String generateToken(String userId, String role) {
        return generateToken(userId, role, 0);
    }

    /** The version a token was issued at. Tokens from before versions existed have none: 0. */
    public int tokenVersionOf(Claims claims) {
        Integer version = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
        return version == null ? 0 : version;
    }

    // FIX 1: return true is now INSIDE the try block; all catch paths return false
    public boolean validateJwtToken(String jwtToken) {
        try {
            Jwts.parser()
                    .verifyWith((SecretKey) key())
                    .build()
                    .parseSignedClaims(jwtToken);
            return true;

        // Logged at debug: an expired or absent token is the normal path (access
        // tokens last 48h and the frontend refreshes on 401), and a malformed one is
        // usually a probe. At error level these filled the log with entries that look
        // like server faults but aren't.
        } catch (ExpiredJwtException e) {
            log.debug("JWT token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.debug("JWT token unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.debug("JWT token malformed: {}", e.getMessage());
        } catch (io.jsonwebtoken.security.SecurityException e) {
            log.warn("JWT signature invalid — token was not issued by this server.");
        } catch (IllegalArgumentException e) {
            log.debug("JWT claims empty: {}", e.getMessage());
        }
        return false;
    }

    public Key key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String getJwtFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer "))
            return bearerToken.substring(7);
        return null;
    }

    public String getUserIdFromToken(String jwt) {
        return Jwts.parser()
                .verifyWith((SecretKey) key())
                .build()
                .parseSignedClaims(jwt)
                .getPayload()
                .getSubject();
    }

    public Claims getAllClaims(String jwt) {
        return Jwts.parser()
                .verifyWith((SecretKey) key())
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }
}
