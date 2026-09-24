package com.identityos.onboarding_and_identity_service.dto;

public record HostedIdentityOtpResponse(
        boolean success,
        String message,
        boolean verified,
        String verificationId
) {
}
