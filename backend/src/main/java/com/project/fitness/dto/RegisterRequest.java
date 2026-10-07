package com.project.fitness.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private String firstName;
    private String lastName;

    // NOTE: there is deliberately no `role` field. It used to be here and was passed
    // straight through to the saved User, so anyone could self-register as an admin
    // with {"role":"ADMIN"}. Roles are assigned server-side only.

    // Feature 1: body metrics at registration
    private Double weight;   // kg
    private Double height;   // cm
    private Integer age;
}
