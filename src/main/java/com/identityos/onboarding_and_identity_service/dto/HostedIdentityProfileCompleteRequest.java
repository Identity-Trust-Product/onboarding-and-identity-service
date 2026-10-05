package com.identityos.onboarding_and_identity_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record HostedIdentityProfileCompleteRequest(
        @NotBlank String clientId,
        @NotBlank String username,
        @NotNull Map<String, Object> fields,
        Map<String, Object> verificationStatus
) {
}
