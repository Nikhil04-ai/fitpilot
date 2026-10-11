package com.project.fitness.security;

import com.project.fitness.repository.UserRepository;
import io.jsonwebtoken.Jwts;
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

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter Tests")
class JwtAuthenticationFilterTest {

    private static final String SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLXRoYXQtaXMtbG9uZy1lbm91Z2gtMjU2LWJpdHM=";

    private final JwtUtils jwtUtils = new JwtUtils();
    // An unstubbed mock answers Optional.empty(), meaning "no such user", which the
    // filter lets through; the version tests below stub what they need.
    private final UserRepository userRepository = mock(UserRepository.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationFilter filter() {
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 60_000);
        return new JwtAuthenticationFilter(jwtUtils, userRepository);
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

    // ── Token version (PLAN P4-3, the access-token half) ─────────────────────

    private boolean authenticates(JwtAuthenticationFilter filter, String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    @Test
    @DisplayName("a token whose version matches the account's is accepted")
    void matchingVersionIsAccepted() throws Exception {
        JwtAuthenticationFilter filter = filter();
        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(3));

        assertThat(authenticates(filter, jwtUtils.generateToken("user-1", "USER", 3))).isTrue();
    }

    @Test
    @DisplayName("a token from before the version was bumped is refused")
    void staleVersionIsRefused() throws Exception {
        JwtAuthenticationFilter filter = filter();
        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(4));

        assertThat(authenticates(filter, jwtUtils.generateToken("user-1", "USER", 3))).isFalse();
    }

    @Test
    @DisplayName("a token from a later version than the account's is refused too")
    void newerVersionIsRefused() throws Exception {
        JwtAuthenticationFilter filter = filter();
        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(1));

        assertThat(authenticates(filter, jwtUtils.generateToken("user-1", "USER", 2))).isFalse();
    }

    /** Every token issued before this change has no version claim, and must keep working. */
    @Test
    @DisplayName("a token with no version claim counts as version 0")
    void tokenWithoutAClaimIsVersionZero() throws Exception {
        JwtAuthenticationFilter filter = filter();
        String legacy = Jwts.builder().subject("user-1").claim("roles", List.of("USER"))
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(jwtUtils.key()).compact();

        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(0));
        assertThat(authenticates(filter, legacy)).as("account still at 0").isTrue();

        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(1));
        assertThat(authenticates(filter, legacy)).as("account bumped to 1").isFalse();
    }

    /**
     * A deleted account is not this check's business: the request goes on, and the
     * controller answers it with the 409 the frontend recovers from (PLAN P4-18).
     * Refusing it here would turn that into a 401 and a refresh loop.
     */
    @Test
    @DisplayName("an account that no longer exists is left to the 409 path, not refused here")
    void missingAccountIsNotRefusedHere() throws Exception {
        JwtAuthenticationFilter filter = filter();
        when(userRepository.findTokenVersionById("ghost")).thenReturn(Optional.empty());

        assertThat(authenticates(filter, jwtUtils.generateToken("ghost", "USER", 5))).isTrue();
    }

    /**
     * The refusal used to continue the chain from inside the filter's try block, so an
     * exception from further down was caught there and the chain ran a second time.
     */
    @Test
    @DisplayName("a refused token still runs the rest of the chain exactly once, even when it throws")
    void refusedTokenRunsTheChainOnce() throws Exception {
        JwtAuthenticationFilter filter = filter();
        when(userRepository.findTokenVersionById("user-1")).thenReturn(Optional.of(4));
        FilterChain chain = mock(FilterChain.class);
        doThrow(new jakarta.servlet.ServletException("downstream")).when(chain).doFilter(any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtUtils.generateToken("user-1", "USER", 3));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .hasMessage("downstream");
        verify(chain, times(1)).doFilter(any(), any());
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
