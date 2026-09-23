package com.identityos.onboarding_and_identity_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import com.identityos.onboarding_and_identity_service.client.AuthenticationClient;
import com.identityos.onboarding_and_identity_service.dto.ApprovalRequest;
import com.identityos.onboarding_and_identity_service.dto.ApplicationRegistrationRequest;
import com.identityos.onboarding_and_identity_service.dto.ApplicationResponse;
import com.identityos.onboarding_and_identity_service.dto.CreateIdentityRequest;
import com.identityos.onboarding_and_identity_service.dto.CreateIdentityResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityAuthResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityLoginRequest;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityRegisterRequest;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionRequest;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionResponse;
import com.identityos.onboarding_and_identity_service.dto.OrganizationAdminSyncResponse;
import com.identityos.onboarding_and_identity_service.dto.OrganizationProfileResponse;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationRequest;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationResponse;
import com.identityos.onboarding_and_identity_service.dto.UserMigrationRequest;
import com.identityos.onboarding_and_identity_service.dto.UserMigrationResponse;
import com.identityos.onboarding_and_identity_service.repository.OrganizationRepository;
import com.identityos.onboarding_and_identity_service.service.HostedIdentityService;
import com.identityos.onboarding_and_identity_service.service.IdentityUserMigrationService;
import com.identityos.onboarding_and_identity_service.service.OnboardingAuditService;
import com.identityos.onboarding_and_identity_service.service.OrganizationRegistrationService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final AuthenticationClient authenticationClient;
    private final HostedIdentityService hostedIdentityService;
    private final IdentityUserMigrationService identityUserMigrationService;
    private final OrganizationRegistrationService organizationRegistrationService;
    private final OrganizationRepository organizationRepository;
    private final OnboardingAuditService auditService;

    public OnboardingController(
            AuthenticationClient authenticationClient,
            HostedIdentityService hostedIdentityService,
            IdentityUserMigrationService identityUserMigrationService,
            OrganizationRegistrationService organizationRegistrationService,
            OrganizationRepository organizationRepository,
            OnboardingAuditService auditService) {
        this.authenticationClient = authenticationClient;
        this.hostedIdentityService = hostedIdentityService;
        this.identityUserMigrationService = identityUserMigrationService;
        this.organizationRegistrationService = organizationRegistrationService;
        this.organizationRepository = organizationRepository;
        this.auditService = auditService;
    }

    @PostMapping("/identity/register")
    public ResponseEntity<HostedIdentityAuthResponse> registerHostedIdentity(
            @Valid @RequestBody HostedIdentityRegisterRequest request) {
        return ResponseEntity.ok(hostedIdentityService.register(request));
    }

    @PostMapping("/identity/login")
    public ResponseEntity<HostedIdentityAuthResponse> loginHostedIdentity(
            @Valid @RequestBody HostedIdentityLoginRequest request) {
        return ResponseEntity.ok(hostedIdentityService.login(request));
    }

    @PostMapping("/identity-users/migrate")
    public ResponseEntity<UserMigrationResponse> migrateIdentityUsers(
            @Valid @RequestBody UserMigrationRequest request) {
        return ResponseEntity.ok(identityUserMigrationService.migrateUsers(request));
    }

    @GetMapping("/identity/schema")
    public ResponseEntity<IdentitySchemaVersionResponse> getHostedIdentitySchema(
            @RequestParam String clientId,
            @RequestParam String schemaType) {
        return organizationRepository.findApprovedSchemaForClient(clientId, schemaType)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/organizations")
    public ResponseEntity<RegisterOrganizationResponse> registerOrganization(
            @Valid @RequestBody RegisterOrganizationRequest request) {
        return ResponseEntity.ok(organizationRegistrationService.register(request));
    }

    @GetMapping("/organizations/{organizationId}")
    public ResponseEntity<OrganizationProfileResponse> getOrganization(
            @PathVariable String organizationId) {
        return getOrganizationProfileResponse(organizationId);
    }

    @GetMapping("/organizations/{organizationId}/profile")
    public ResponseEntity<OrganizationProfileResponse> getOrganizationProfile(
            @PathVariable String organizationId) {
        return getOrganizationProfileResponse(organizationId);
    }

    private ResponseEntity<OrganizationProfileResponse> getOrganizationProfileResponse(String organizationId) {
        return organizationRepository.findByOrganizationId(organizationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/organizations")
    public ResponseEntity<List<OrganizationProfileResponse>> listOrganizations(
            @RequestParam(required = false) String approvalStatus) {
        return ResponseEntity.ok(organizationRepository.findOrganizations(approvalStatus));
    }

    @PostMapping("/organizations/{organizationId}/approval")
    public ResponseEntity<Void> updateOrganizationApproval(
            @PathVariable String organizationId,
            @Valid @RequestBody ApprovalRequest request) {
        try {
            organizationRepository.updateApprovalStatus(organizationId, request.decision());
            auditService.organizationApprovalUpdated(organizationId, request.decision());
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "ORGANIZATION_APPROVAL_UPDATE_FAILED",
                    "POST",
                    "/api/v1/onboarding/organizations/" + organizationId + "/approval",
                    "ORGANIZATION",
                    organizationId,
                    "PLATFORM_ADMIN",
                    organizationId,
                    null,
                    null,
                    request.decision(),
                    exception);
            throw exception;
        }
    }

    @PostMapping("/organizations/{organizationId}/admin-user")
    public ResponseEntity<OrganizationAdminSyncResponse> syncOrganizationAdmin(
            @PathVariable String organizationId) {
        try {
            OrganizationAdminSyncResponse response = organizationRegistrationService.syncOrganizationAdmin(organizationId);
            auditService.organizationAdminSynced(response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "ORGANIZATION_ADMIN_SYNC_FAILED",
                    "POST",
                    "/api/v1/onboarding/organizations/" + organizationId + "/admin-user",
                    "ORGANIZATION",
                    organizationId,
                    "ORGANIZATION_ADMIN",
                    organizationId,
                    null,
                    null,
                    null,
                    exception);
            throw exception;
        }
    }

    @PostMapping("/organizations/{organizationId}/applications")
    public ResponseEntity<ApplicationResponse> registerApplication(
            @PathVariable String organizationId,
            @Valid @RequestBody ApplicationRegistrationRequest request) {
        try {
            ApplicationResponse response = organizationRepository.insertApplication(organizationId, request);
            auditService.applicationRegistered(organizationId, request, response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "APPLICATION_REGISTRATION_FAILED",
                    "POST",
                    "/api/v1/onboarding/organizations/" + organizationId + "/applications",
                    "APPLICATION",
                    null,
                    "ORGANIZATION_ADMIN",
                    organizationId,
                    null,
                    null,
                    null,
                    exception);
            throw exception;
        }
    }

    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationResponse>> listApplications(
            @RequestParam(required = false) String organizationId) {
        return ResponseEntity.ok(organizationRepository.findApplications(organizationId));
    }

    @GetMapping("/applications/client/{clientId}")
    public ResponseEntity<ApplicationResponse> getApplicationByClientId(
            @PathVariable String clientId) {
        return organizationRepository.findApplicationByClientId(clientId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/applications/{applicationId}/approval")
    public ResponseEntity<ApplicationResponse> updateApplicationApproval(
            @PathVariable String applicationId,
            @Valid @RequestBody ApprovalRequest request) {
        try {
            ApplicationResponse response = organizationRepository.updateApplicationStatus(applicationId, request.decision());
            auditService.applicationApprovalUpdated(request.decision(), response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "APPLICATION_APPROVAL_UPDATE_FAILED",
                    "POST",
                    "/api/v1/onboarding/applications/" + applicationId + "/approval",
                    "APPLICATION",
                    applicationId,
                    "PLATFORM_ADMIN",
                    null,
                    applicationId,
                    null,
                    request.decision(),
                    exception);
            throw exception;
        }
    }

    @PostMapping("/organizations/{organizationId}/applications/{applicationId}/schemas")
    public ResponseEntity<IdentitySchemaVersionResponse> createSchemaVersion(
            @PathVariable String organizationId,
            @PathVariable String applicationId,
            @Valid @RequestBody IdentitySchemaVersionRequest request) {
        try {
            IdentitySchemaVersionResponse response = organizationRepository.createSchemaVersion(organizationId, applicationId, request);
            auditService.schemaVersionCreated(organizationId, applicationId, request, response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "SCHEMA_VERSION_CREATION_FAILED",
                    "POST",
                    "/api/v1/onboarding/organizations/" + organizationId + "/applications/" + applicationId + "/schemas",
                    "IDENTITY_SCHEMA_VERSION",
                    null,
                    "ORGANIZATION_ADMIN",
                    organizationId,
                    applicationId,
                    null,
                    null,
                    exception);
            throw exception;
        }
    }

    @GetMapping("/schemas")
    public ResponseEntity<List<IdentitySchemaVersionResponse>> listSchemas(
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String applicationId) {
        return ResponseEntity.ok(organizationRepository.findSchemas(organizationId, applicationId));
    }

    @GetMapping("/organizations/{organizationId}/schemas/versions")
    public ResponseEntity<List<IdentitySchemaVersionResponse>> listOrganizationSchemaVersions(
            @PathVariable String organizationId,
            @RequestParam(required = false) String schemaType) {
        return ResponseEntity.ok(organizationRepository.findSchemaVersions(organizationId, schemaType));
    }

    @PostMapping("/schemas/versions/{versionId}/approval")
    public ResponseEntity<Void> updateSchemaVersionApproval(
            @PathVariable String versionId,
            @Valid @RequestBody ApprovalRequest request) {
        try {
            organizationRepository.updateSchemaVersionApproval(versionId, request.decision(), null);
            auditService.schemaVersionApprovalUpdated(versionId, request.decision());
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            auditService.operationFailed(
                    "SCHEMA_VERSION_APPROVAL_UPDATE_FAILED",
                    "POST",
                    "/api/v1/onboarding/schemas/versions/" + versionId + "/approval",
                    "IDENTITY_SCHEMA_VERSION",
                    versionId,
                    "PLATFORM_ADMIN",
                    null,
                    null,
                    versionId,
                    request.decision(),
                    exception);
            throw exception;
        }
    }

    @PostMapping("/test-authentication")
    public ResponseEntity<CreateIdentityResponse> testAuthentication(
        @RequestBody CreateIdentityRequest request,
        @RequestHeader("Authorization") String authorization) {

    try {
        CreateIdentityResponse response =
                authenticationClient.createIdentity(request, authorization);
        auditService.authenticationTested(request, response);

        return ResponseEntity.ok(response);
    } catch (RuntimeException exception) {
        auditService.authenticationTestFailed(request, exception);
        throw exception;
    }
}
}
