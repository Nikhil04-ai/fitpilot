package com.project.fitness.security;

import com.project.fitness.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = jwtUtils.getJwtFromHeader(request);

            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                String userId = jwtUtils.getUserIdFromToken(jwt);

                Claims claims = jwtUtils.getAllClaims(jwt);

                // A token issued before the account's last password change or reset is
                // refused (PLAN P4-3): its signature and expiry are still fine, which is
                // exactly why this has to ask the account. No authentication is set, so
                // the request is a 401, the code the frontend answers by refreshing, and
                // the refresh token is gone too, so it ends at the login screen.
                //
                // An account that no longer exists is not refused here. The request goes
                // on and the controller answers with the 409 the frontend recovers from
                // (PLAN P4-18); a 401 would only send it round the refresh loop.
                //
                // The chain is not continued from in here: an exception from further down
                // would land in the catch below and the chain would run a second time.
                Optional<Integer> current = userRepository.findTokenVersionById(userId);
                if (current.isPresent() && current.get() != jwtUtils.tokenVersionOf(claims)) {
                    log.debug("Refused a token from an earlier session of user {}", userId);
                } else {
                    authenticate(request, userId, claims);
                }
            }
        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String userId, Claims claims) {
        List<String> roles = claims.get("roles", List.class);

        // Spring's hasRole("ADMIN") checks for the authority "ROLE_ADMIN", so the
        // prefix has to be added here. Without it .hasRole(...) rules in
        // SecurityConfig can never match, and role-protected routes silently
        // reject everyone — including real admins.
        List<GrantedAuthority> authorities = List.of();
        if (roles != null) {
            authorities = roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(
                            role.startsWith("ROLE_") ? role : "ROLE_" + role))
                    .toList();
        }

        // Principal is the userId String — ActivityController reads it via authentication.getPrincipal()
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
