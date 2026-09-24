package com.identityos.onboarding_and_identity_service.dto;

import jakarta.validation.constraints.NotBlank;

public record HostedIdentityOtpVerifyRequest(
        @NotBlank String clientId,
        @NotBlank String fieldName,
        @NotBlank String value,
        @NotBlank String otp,
        String verificationId
) {
}
