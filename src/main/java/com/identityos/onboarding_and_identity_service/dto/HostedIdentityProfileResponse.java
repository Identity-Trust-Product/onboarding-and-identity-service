package com.identityos.onboarding_and_identity_service.dto;

import java.util.Map;

public record HostedIdentityProfileResponse(
        String clientId,
        String organizationId,
        String applicationId,
        String applicationName,
        String username,
        Map<String, Object> fields,
        Map<String, Object> verificationStatus
) {
}
