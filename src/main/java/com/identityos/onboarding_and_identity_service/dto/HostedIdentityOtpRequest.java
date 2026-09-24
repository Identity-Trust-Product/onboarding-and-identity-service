package com.identityos.onboarding_and_identity_service.dto;

import jakarta.validation.constraints.NotBlank;

public record HostedIdentityOtpRequest(
        @NotBlank String clientId,
        @NotBlank String fieldName,
        @NotBlank String fieldType,
        @NotBlank String value
) {
}
