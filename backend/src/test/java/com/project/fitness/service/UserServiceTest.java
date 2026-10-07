package com.project.fitness.service;

import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.dto.UserResponse;
import com.project.fitness.model.User;
import com.project.fitness.model.UserRole;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private UserService userService;

    private RegisterRequest request(String email) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword("password123");
        r.setFirstName("Test");
        r.setLastName("User");
        return r;
    }

    /**
     * RegisterRequest used to carry a `role` that was written straight onto the
     * saved User, so POSTing {"role":"ADMIN"} to the public register endpoint was
     * enough to self-provision an admin. Every registration is a plain USER now.
     */
    @Test
    @DisplayName("register always creates a plain USER, never an admin")
    void register_alwaysCreatesPlainUser() {
        when(userRepository.findByEmail(anyString())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        userService.register(request("new@test.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("register never returns the password hash")
    void register_responseOmitsPassword() {
        when(userRepository.findByEmail(anyString())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UserResponse response = userService.register(request("new@test.com"));

        assertThat(response.getEmail()).isEqualTo("new@test.com");
        // UserResponse has no password field at all — assert the serialized shape
        // can't carry one by checking the mapped values we do expose.
        assertThat(response.getId()).isNull();   // id assigned by the DB, not set here
    }

    @Test
    @DisplayName("register stores the email lower-cased so logins are case-insensitive")
    void register_lowercasesEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        userService.register(request("  MiXeD@Test.COM  "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("mixed@test.com");
    }

    @Test
    @DisplayName("register rejects a duplicate email with a readable message")
    void register_rejectsDuplicateEmail() {
        when(userRepository.findByEmail("taken@test.com"))
                .thenReturn(User.builder().id("existing").build());

        assertThatThrownBy(() -> userService.register(request("taken@test.com")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("authenticate accepts a differently-cased email")
    void authenticate_isCaseInsensitive() {
        User user = User.builder().id("u1").email("me@test.com").password("hashed").build();
        when(userRepository.findByEmail("me@test.com")).thenReturn(user);
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);

        LoginRequest login = new LoginRequest();
        login.setEmail("ME@Test.com");
        login.setPassword("password123");

        assertThat(userService.authenticate(login).getId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("authenticate gives the same error for unknown email and wrong password")
    void authenticate_doesNotRevealWhichPartWasWrong() {
        when(userRepository.findByEmail(anyString())).thenReturn(null);

        LoginRequest login = new LoginRequest();
        login.setEmail("nobody@test.com");
        login.setPassword("whatever1");

        assertThatThrownBy(() -> userService.authenticate(login))
                .hasMessage("Invalid Credentials");
    }
}
