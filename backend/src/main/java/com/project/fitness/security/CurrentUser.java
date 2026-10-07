package com.project.fitness.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Single source of truth for "who is making this request".
 *
 * Identity ALWAYS comes from the validated JWT (see {@link JwtAuthenticationFilter},
 * which sets the principal to the user id), never from a path variable, query
 * parameter or request body — a client controls all three and can put any other
 * user's id in them.
 *
 * See AGENT.md §1. Controllers call {@link #id()} instead of reading a userId
 * the caller supplied; where an existing URL still carries a userId segment (kept
 * so the frontend and API.md don't have to change), they call
 * {@link #requireSelf(String)} to reject a mismatch with 403.
 */
public final class CurrentUser {

    private CurrentUser() { }

    /** The authenticated user's id. Throws 403 if the request is not authenticated. */
    public static String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String userId)
                || userId.isBlank() || "anonymousUser".equals(userId)) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return userId;
    }

    /** The authenticated user's id, or null when the request carries no valid token. */
    public static String idOrNull() {
        try {
            return id();
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    /**
     * Asserts the caller is acting on their own data, and returns the authenticated id.
     * Use for endpoints whose URL still contains a userId segment.
     */
    public static String requireSelf(String requestedUserId) {
        String actual = id();
        if (requestedUserId != null && !requestedUserId.equals(actual)) {
            throw new AccessDeniedException("You can only access your own data.");
        }
        return actual;
    }

    /** Asserts the caller owns a resource, given that resource's owner id. */
    public static void requireOwner(String ownerId, String resourceName) {
        if (!id().equals(ownerId)) {
            throw new AccessDeniedException("You don't have access to this " + resourceName + ".");
        }
    }
}
