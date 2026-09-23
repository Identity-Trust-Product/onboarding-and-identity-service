package com.identityos.onboarding_and_identity_service.dto;

import java.util.List;

public record UserMigrationResponse(
        String batchId,
        String applicationId,
        int totalRecords,
        int successCount,
        int failedCount,
        String status,
        List<UserMigrationResult> results
) {
}
