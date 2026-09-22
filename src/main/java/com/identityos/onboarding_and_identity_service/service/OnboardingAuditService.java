package com.identityos.onboarding_and_identity_service.service;

import com.identityos.onboarding_and_identity_service.client.AuditClient;
import com.identityos.onboarding_and_identity_service.dto.ApplicationRegistrationRequest;
import com.identityos.onboarding_and_identity_service.dto.ApplicationResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityAuthResponse;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionRequest;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionResponse;
import com.identityos.onboarding_and_identity_service.dto.OrganizationAdminSyncResponse;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationRequest;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationResponse;
import com.identityos.onboarding_and_identity_service.dto.CreateIdentityRequest;
import com.identityos.onboarding_and_identity_service.dto.CreateIdentityResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OnboardingAuditService {
    private static final String ORIGIN = "onboarding-and-identity-service";

    private final AuditClient auditClient;

    public OnboardingAuditService(AuditClient auditClient) {
        this.auditClient = auditClient;
    }

    public void organizationRegistered(RegisterOrganizationRequest request, RegisterOrganizationResponse response) {
        Map<String, Object> body = mapOf(
                "organizationName", request.organizationName(),
                "organizationType", request.organizationType(),
                "countryCode", request.countryCode(),
                "officialEmail", request.officialEmail(),
                "representativeEmail", request.representativeEmail());

        Map<String, Object> metadata = mapOf(
                "verificationEmailSent", response.verificationEmailSent(),
                "keycloakUsername", response.keycloakUsername());

        record(base("ORGANIZATION_REGISTERED", "POST", "/api/v1/onboarding/organizations",
                "ORGANIZATION", response.organizationId())
                .put("actorUserId", response.organizationId())
                .put("actorRole", "ORGANIZATION_ADMIN")
                .put("organizationId", response.organizationId())
                .put("status", "SUCCESS")
                .put("body", body)
                .put("metadata", metadata));
    }

    public void organizationRegistrationFailed(RegisterOrganizationRequest request, String organizationId, RuntimeException exception) {
        record(base("ORGANIZATION_REGISTRATION_FAILED", "POST", "/api/v1/onboarding/organizations",
                "ORGANIZATION", organizationId)
                .put("actorRole", "ORGANIZATION_ADMIN")
                .put("organizationId", organizationId)
                .put("status", "FAILED")
                .put("body", mapOf(
                        "organizationName", request.organizationName(),
                        "organizationType", request.organizationType(),
                        "countryCode", request.countryCode(),
                        "officialEmail", request.officialEmail(),
                        "representativeEmail", request.representativeEmail()))
                .put("metadata", exceptionMetadata(exception)));
    }

    public void organizationApprovalUpdated(String organizationId, String decision) {
        record(base("ORGANIZATION_APPROVAL_UPDATED", "POST",
                "/api/v1/onboarding/organizations/" + organizationId + "/approval",
                "ORGANIZATION", organizationId)
                .put("actorRole", "PLATFORM_ADMIN")
                .put("organizationId", organizationId)
                .put("decision", decision)
                .put("status", "SUCCESS")
                .put("body", mapOf("decision", decision)));
    }

    public void organizationAdminSynced(OrganizationAdminSyncResponse response) {
        record(base("ORGANIZATION_ADMIN_SYNCED", "POST",
                "/api/v1/onboarding/organizations/" + response.organizationId() + "/admin-user",
                "ORGANIZATION", response.organizationId())
                .put("actorUserId", response.organizationId())
                .put("actorRole", "ORGANIZATION_ADMIN")
                .put("organizationId", response.organizationId())
                .put("status", "SUCCESS")
                .put("metadata", mapOf(
                        "keycloakUsername", response.keycloakUsername(),
                        "actionEmailSent", response.actionEmailSent(),
                        "message", response.message())));
    }

    public void applicationRegistered(
            String organizationId, ApplicationRegistrationRequest request, ApplicationResponse response) {
        record(base("APPLICATION_REGISTERED", "POST",
                "/api/v1/onboarding/organizations/" + organizationId + "/applications",
                "APPLICATION", response.applicationId())
                .put("actorUserId", organizationId)
                .put("actorRole", "ORGANIZATION_ADMIN")
                .put("organizationId", response.organizationId())
                .put("applicationId", response.applicationId())
                .put("status", response.status())
                .put("body", mapOf(
                        "applicationName", request.applicationName(),
                        "applicationType", request.applicationType(),
                        "redirectUri", request.redirectUri(),
                        "description", request.description()))
                .put("metadata", mapOf(
                        "applicationName", response.applicationName(),
                        "clientId", response.clientId(),
                        "createdAt", response.createdAt())));
    }

    public void applicationApprovalUpdated(String decision, ApplicationResponse response) {
        record(base("APPLICATION_APPROVAL_UPDATED", "POST",
                "/api/v1/onboarding/applications/" + response.applicationId() + "/approval",
                "APPLICATION", response.applicationId())
                .put("actorRole", "PLATFORM_ADMIN")
                .put("organizationId", response.organizationId())
                .put("applicationId", response.applicationId())
                .put("decision", decision)
                .put("status", response.status())
                .put("body", mapOf("decision", decision))
                .put("metadata", mapOf(
                        "applicationName", response.applicationName(),
                        "applicationType", response.applicationType())));
    }

    public void schemaVersionCreated(
            String organizationId,
            String applicationId,
            IdentitySchemaVersionRequest request,
            IdentitySchemaVersionResponse response) {
        record(base("SCHEMA_VERSION_CREATED", "POST",
                "/api/v1/onboarding/organizations/" + organizationId
                        + "/applications/" + applicationId + "/schemas",
                "IDENTITY_SCHEMA_VERSION", response.versionId())
                .put("actorUserId", organizationId)
                .put("actorRole", "ORGANIZATION_ADMIN")
                .put("organizationId", response.organizationId())
                .put("applicationId", response.applicationId())
                .put("schemaId", response.schemaId())
                .put("schemaVersionId", response.versionId())
                .put("status", response.status())
                .put("body", mapOf(
                        "schemaType", request.schemaType(),
                        "schemaName", request.schemaName(),
                        "changeSummary", request.changeSummary(),
                        "submitForApproval", request.submitForApproval()))
                .put("metadata", mapOf(
                        "schemaType", response.schemaType(),
                        "schemaName", response.schemaName(),
                        "versionNumber", response.versionNumber(),
                        "createdAt", response.createdAt())));
    }

    public void schemaVersionApprovalUpdated(String versionId, String decision) {
        record(base("SCHEMA_VERSION_APPROVAL_UPDATED", "POST",
                "/api/v1/onboarding/schemas/versions/" + versionId + "/approval",
                "IDENTITY_SCHEMA_VERSION", versionId)
                .put("actorRole", "PLATFORM_ADMIN")
                .put("schemaVersionId", versionId)
                .put("decision", decision)
                .put("status", "SUCCESS")
                .put("body", mapOf("decision", decision)));
    }

    public void hostedIdentityRegistered(
            String organizationId,
            String applicationId,
            String clientId,
            String externalUsername,
            HostedIdentityAuthResponse response) {
        record(base("IDENTITY_REGISTERED", "POST", "/api/v1/onboarding/identity/register",
                "HOSTED_IDENTITY", response.keycloakUserId() == null ? externalUsername : response.keycloakUserId())
                .put("actorUserId", externalUsername)
                .put("actorRole", "HOSTED_IDENTITY")
                .put("organizationId", organizationId)
                .put("applicationId", applicationId)
                .put("status", response.success() ? "SUCCESS" : "FAILED")
                .put("body", mapOf("clientId", clientId, "username", externalUsername))
                .put("metadata", mapOf(
                        "keycloakUserId", response.keycloakUserId(),
                        "message", response.message())));
    }

    public void hostedIdentityLogin(
            String organizationId,
            String applicationId,
            String clientId,
            String externalUsername,
            HostedIdentityAuthResponse response) {
        record(base("IDENTITY_LOGIN", "POST", "/api/v1/onboarding/identity/login",
                "HOSTED_IDENTITY", externalUsername)
                .put("actorUserId", externalUsername)
                .put("actorRole", "HOSTED_IDENTITY")
                .put("organizationId", organizationId)
                .put("applicationId", applicationId)
                .put("status", response.success() ? "SUCCESS" : "FAILED")
                .put("body", mapOf("clientId", clientId, "username", externalUsername))
                .put("metadata", mapOf(
                        "tokenType", response.tokenType(),
                        "expiresIn", response.expiresIn(),
                        "message", response.message())));
    }

    public void authenticationTested(CreateIdentityRequest request, CreateIdentityResponse response) {
        record(base("AUTHENTICATION_TESTED", "POST", "/api/v1/onboarding/test-authentication",
                "IDENTITY", request.getOrganizationId())
                .put("actorRole", "PLATFORM_ADMIN")
                .put("organizationId", request.getOrganizationId())
                .put("status", "SUCCESS")
                .put("body", mapOf(
                        "organizationId", request.getOrganizationId(),
                        "applicationId", request.getApplicationId()))
                .put("metadata", mapOf(
                        "success", response.isSuccess(),
                        "identityId", response.getIdentityId(),
                        "message", response.getMessage())));
    }

    public void authenticationTestFailed(CreateIdentityRequest request, RuntimeException exception) {
        record(base("AUTHENTICATION_TEST_FAILED", "POST", "/api/v1/onboarding/test-authentication",
                "IDENTITY", request.getOrganizationId())
                .put("actorRole", "PLATFORM_ADMIN")
                .put("organizationId", request.getOrganizationId())
                .put("status", "FAILED")
                .put("body", mapOf(
                        "organizationId", request.getOrganizationId(),
                        "applicationId", request.getApplicationId()))
                .put("metadata", exceptionMetadata(exception)));
    }

    public void operationFailed(
            String action,
            String method,
            String endpoint,
            String entityType,
            String entityId,
            String actorRole,
            String organizationId,
            String applicationId,
            String schemaVersionId,
            String decision,
            RuntimeException exception) {
        record(base(action, method, endpoint, entityType, entityId)
                .put("actorRole", actorRole)
                .put("organizationId", organizationId)
                .put("applicationId", applicationId)
                .put("schemaVersionId", schemaVersionId)
                .put("decision", decision)
                .put("status", "FAILED")
                .put("body", mapOf("decision", decision))
                .put("metadata", exceptionMetadata(exception)));
    }

    private AuditEvent base(String action, String method, String endpoint, String entityType, String entityId) {
        return new AuditEvent()
                .put("origin", ORIGIN)
                .put("action", action)
                .put("httpMethod", method)
                .put("endpoint", endpoint)
                .put("entityType", entityType)
                .put("entityId", entityId)
                .put("isoTime", OffsetDateTime.now().toString());
    }

    private void record(AuditEvent event) {
        try {
            auditClient.record(event.values);
        } catch (RuntimeException exception) {
            System.err.println("Audit event was not recorded: " + exception.getMessage());
        }
    }

    private Map<String, Object> exceptionMetadata(RuntimeException exception) {
        return mapOf(
                "errorType", exception.getClass().getSimpleName(),
                "errorMessage", exception.getMessage());
    }

    private Map<String, Object> mapOf(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keysAndValues.length; index += 2) {
            if (keysAndValues[index + 1] != null) {
                values.put(String.valueOf(keysAndValues[index]), keysAndValues[index + 1]);
            }
        }
        return values;
    }

    private static class AuditEvent {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private AuditEvent put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }
    }
}
