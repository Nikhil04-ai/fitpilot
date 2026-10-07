package com.project.fitness.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CurrentUser Tests")
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    @DisplayName("id() returns the authenticated user id")
    void id_returnsPrincipal() {
        authenticateAs("user-1");
        assertThat(CurrentUser.id()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("id() denies access when there is no authentication")
    void id_deniesWhenAnonymous() {
        assertThatThrownBy(CurrentUser::id).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("idOrNull() returns null instead of throwing when unauthenticated")
    void idOrNull_returnsNullWhenAnonymous() {
        assertThat(CurrentUser.idOrNull()).isNull();
    }

    @Test
    @DisplayName("requireSelf() allows the caller's own id")
    void requireSelf_allowsOwnId() {
        authenticateAs("user-1");
        assertThat(CurrentUser.requireSelf("user-1")).isEqualTo("user-1");
    }

    @Test
    @DisplayName("requireSelf() rejects another user's id — this is the IDOR guard")
    void requireSelf_rejectsOtherId() {
        authenticateAs("user-1");
        assertThatThrownBy(() -> CurrentUser.requireSelf("user-2"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("your own data");
    }

    @Test
    @DisplayName("requireOwner() rejects a resource owned by someone else")
    void requireOwner_rejectsOtherOwner() {
        authenticateAs("user-1");
        assertThatThrownBy(() -> CurrentUser.requireOwner("user-2", "goal"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("goal");
    }
}
