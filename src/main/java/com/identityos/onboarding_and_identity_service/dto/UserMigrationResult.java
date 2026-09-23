package com.identityos.onboarding_and_identity_service.dto;

public record UserMigrationResult(
        int rowNumber,
        String username,
        String keycloakUsername,
        String keycloakUserId,
        String status,
        String message
) {
}
