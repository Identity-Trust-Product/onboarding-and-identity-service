package com.identityos.onboarding_and_identity_service.service;

import com.identityos.onboarding_and_identity_service.client.KeycloakAdminClient;
import com.identityos.onboarding_and_identity_service.dto.OrganizationAdminSyncResponse;
import com.identityos.onboarding_and_identity_service.dto.OrganizationProfileResponse;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationRequest;
import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationResponse;
import com.identityos.onboarding_and_identity_service.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class OrganizationRegistrationService {
    private final OrganizationRepository organizationRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final OnboardingAuditService auditService;
    private final JavaMailSender mailSender;
    private final String mailFrom;

    public OrganizationRegistrationService(
            OrganizationRepository organizationRepository,
            KeycloakAdminClient keycloakAdminClient,
            OnboardingAuditService auditService,
            JavaMailSender mailSender,
            @Value("${organization-registration.mail.from}") String mailFrom) {
        this.organizationRepository = organizationRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.auditService = auditService;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    @Transactional
    public RegisterOrganizationResponse register(RegisterOrganizationRequest request) {
        String organizationId = request.organizationId() == null || request.organizationId().isBlank()
            ? "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            : request.organizationId();

        try {
            organizationRepository.insert(request, organizationId);
            String adminEmail = request.officialEmail() == null || request.officialEmail().isBlank()
                            ? request.representativeEmail()
                            : request.officialEmail();
            keycloakAdminClient.createOrganizationAdmin(
                organizationId,
                    request.representativeName(),
                    adminEmail);
            boolean credentialEmailSent = sendTemporaryPasswordEmail(adminEmail, organizationId);

            RegisterOrganizationResponse response = new RegisterOrganizationResponse(
                organizationId,
                organizationId,
                    "Organization registered. The temporary one-time password was sent to the official email.",
                    credentialEmailSent);
            auditService.organizationRegistered(request, response);
            return response;
        } catch (RuntimeException exception) {
            auditService.organizationRegistrationFailed(request, organizationId, exception);
            throw exception;
        }
    }

    public OrganizationAdminSyncResponse syncOrganizationAdmin(String organizationId) {
        OrganizationProfileResponse organization = organizationRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization ID not found."));
        if (organization.officialEmail() == null || organization.officialEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization official email is required to create the admin user.");
        }

        boolean actionEmailSent = keycloakAdminClient.createOrganizationAdmin(
                organization.organizationId(),
                organization.organizationName() + " Admin",
                organization.officialEmail());
        boolean credentialEmailSent = sendTemporaryPasswordEmail(organization.officialEmail(), organization.organizationId());

        return new OrganizationAdminSyncResponse(
                organization.organizationId(),
                organization.organizationId(),
                "Organization admin user synced in Keycloak. Temporary password is the organization ID.",
                credentialEmailSent || actionEmailSent);
    }

    private boolean sendTemporaryPasswordEmail(String email, String organizationId) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("Identity OS organization admin credentials");
            message.setText("""
                    Your Identity OS organization admin account is ready.

                    Username: %s
                    One-time password: %s

                    You will be asked to change this password after your first login.
                    """.formatted(organizationId, organizationId));
            mailSender.send(message);
            return true;
        } catch (RuntimeException exception) {
            System.err.println("Organization admin credential email was not sent: " + exception.getMessage());
            return false;
        }
    }
}
