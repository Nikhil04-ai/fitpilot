package com.project.fitness.service;

import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.dto.UserResponse;
import com.project.fitness.model.User;
import com.project.fitness.model.UserRole;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponse register(RegisterRequest request) {
        PasswordPolicy.requireHashable(request.getPassword());
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : null;

        // Checked here so the person gets a clear message instead of the generic
        // "that value is already in use" that the unique-constraint violation produces.
        if (email != null && (userRepository.findByEmail(email) != null
                || userRepository.findByEmail(request.getEmail().trim()) != null)) {
            throw new RuntimeException("An account with this email already exists. Try logging in instead.");
        }

        // Role is never taken from the request — see RegisterRequest.
        UserRole role = UserRole.USER;
        User user = User.builder()
                .email(email)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                // Feature 1: persist body metrics
                .weight(request.getWeight())
                .height(request.getHeight())
                .age(request.getAge())
                .build();

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    public UserResponse mapToResponse(User savedUser) {
        UserResponse response = new UserResponse();
        response.setId(savedUser.getId());
        response.setEmail(savedUser.getEmail());
        // password intentionally NOT set (Fix 3)
        response.setFirstName(savedUser.getFirstName());
        response.setLastName(savedUser.getLastName());
        response.setWeight(savedUser.getWeight());
        response.setHeight(savedUser.getHeight());
        response.setAge(savedUser.getAge());
        response.setCurrentStreak(savedUser.getCurrentStreak());
        response.setLongestStreak(savedUser.getLongestStreak());
        response.setCreatedAt(savedUser.getCreatedAt());
        response.setUpdatedAt(savedUser.getUpdatedAt());
        return response;
    }

    public User authenticate(LoginRequest loginRequest) {
        String raw = loginRequest.getEmail() != null ? loginRequest.getEmail().trim() : null;
        if (raw == null) throw new RuntimeException("Invalid Credentials");

        // New accounts are stored lower-cased; accounts created before that change
        // may be stored exactly as typed, so fall back to the raw form.
        User user = userRepository.findByEmail(raw.toLowerCase());
        if (user == null) user = userRepository.findByEmail(raw);
        if (user == null)
            throw new RuntimeException("Invalid Credentials");

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid Credentials");
        }

        return user;
    }
}
