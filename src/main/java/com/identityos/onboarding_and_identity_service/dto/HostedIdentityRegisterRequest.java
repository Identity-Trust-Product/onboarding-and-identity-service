package com.identityos.onboarding_and_identity_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record HostedIdentityRegisterRequest(
        @NotBlank String clientId,
        String redirectUri,
        @NotNull Map<String, Object> fields,
        Map<String, Object> verificationStatus
) {
}
