package com.identityos.onboarding_and_identity_service.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record UserMigrationUserRequest(
        String externalUserId,
        @NotBlank String username,
        String email,
        String mobileNumber,
        String firstName,
        String lastName,
        String role,
        String status,
        String temporaryPassword,
        Map<String, Object> attributes
) {
}
