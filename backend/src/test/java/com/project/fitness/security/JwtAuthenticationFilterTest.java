package com.project.fitness.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter Tests")
class JwtAuthenticationFilterTest {

    private static final String SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLXRoYXQtaXMtbG9uZy1lbm91Z2gtMjU2LWJpdHM=";

    private final JwtUtils jwtUtils = new JwtUtils();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationFilter filter() {
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 60_000);
        return new JwtAuthenticationFilter(jwtUtils);
    }

    @Test
    @DisplayName("sets the principal to the user id from the token subject")
    void setsPrincipalFromToken() throws Exception {
        JwtAuthenticationFilter filter = filter();
        String token = jwtUtils.generateToken("user-42", "USER");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("user-42");
    }

    /**
     * SecurityConfig protects /api/admin/** with .hasRole("ADMIN"), and hasRole()
     * looks for the authority "ROLE_ADMIN". Granting a bare "ADMIN" means that rule
     * can never match for anyone — so the prefix is asserted here directly.
     */
    @Test
    @DisplayName("grants role authorities with the ROLE_ prefix hasRole() expects")
    void grantsRolePrefixedAuthority() throws Exception {
        JwtAuthenticationFilter filter = filter();
        String token = jwtUtils.generateToken("admin-1", "ADMIN");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("leaves the context unauthenticated for an invalid token")
    void leavesContextEmptyForInvalidToken() throws Exception {
        JwtAuthenticationFilter filter = filter();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.real.token");
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
