package com.identityos.onboarding_and_identity_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.identityos.onboarding_and_identity_service.client.KeycloakAdminClient;
import com.identityos.onboarding_and_identity_service.dto.ApplicationResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityAuthResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityLoginRequest;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityRegisterRequest;
import com.identityos.onboarding_and_identity_service.repository.OrganizationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class HostedIdentityService {
    private final OrganizationRepository organizationRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    public HostedIdentityService(
            OrganizationRepository organizationRepository,
            KeycloakAdminClient keycloakAdminClient) {
        this.organizationRepository = organizationRepository;
        this.keycloakAdminClient = keycloakAdminClient;
    }

    public HostedIdentityAuthResponse register(HostedIdentityRegisterRequest request) {
        ApplicationResponse application = approvedApplication(request.clientId(), request.redirectUri());
        String externalUsername = firstPresent(request.fields(), "username", "email", "mobile");
        String password = stringValue(request.fields().get("password"));
        String email = stringValue(request.fields().get("email"));

        if (externalUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username, email, or mobile is required.");
        }
        if (password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required.");
        }

        externalUsername = externalUsername(application.applicationId(), externalUsername);
        String keycloakUsername = keycloakUsername(application.applicationId(), externalUsername);
        try {
            String keycloakUserId = keycloakAdminClient.createApplicationUser(
                    keycloakUsername,
                    externalUsername,
                    password,
                    email,
                    application.organizationId(),
                    application.applicationId(),
                    request.clientId(),
                    request.fields());
            return new HostedIdentityAuthResponse(
                    true,
                    "Registration completed successfully.",
                    externalUsername,
                    keycloakUserId,
                    null,
                    null,
                    null,
                    null);
        } catch (IllegalStateException exception) {
            if (!exception.getMessage().toLowerCase().contains("already registered")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
            }
            return new HostedIdentityAuthResponse(
                    true,
                    "Registration completed successfully.",
                    externalUsername,
                    null,
                    null,
                    null,
                    null,
                    null);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                return new HostedIdentityAuthResponse(
                        true,
                        "Registration completed successfully.",
                        externalUsername,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            String detail = exception.getResponseBodyAsString();
            String message = detail == null || detail.isBlank()
                    ? "Unable to create user in Keycloak."
                    : "Unable to create user in Keycloak: " + detail;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, exception);
        }
    }

    public HostedIdentityAuthResponse login(HostedIdentityLoginRequest request) {
        ApplicationResponse application = approvedApplication(request.clientId(), request.redirectUri());
        String externalUsername = firstPresent(request.fields(), "username", "email", "mobile");
        String password = stringValue(request.fields().get("password"));

        if (externalUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username, email, or mobile is required.");
        }
        if (password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required.");
        }
        externalUsername = externalUsername(application.applicationId(), externalUsername);

        JsonNode token;
        try {
            token = authenticateApplicationUser(application.applicationId(), externalUsername, password);
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.", exception);
        } catch (HttpClientErrorException.BadRequest exception) {
            String detail = exception.getResponseBodyAsString();
            String message = detail == null || detail.isBlank()
                    ? "Invalid username or password."
                    : "Unable to login through Keycloak: " + detail;
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message, exception);
        }
        return new HostedIdentityAuthResponse(
                true,
                "Login successful.",
                externalUsername,
                null,
                token.path("access_token").asText(null),
                token.path("refresh_token").asText(null),
                token.path("token_type").asText("Bearer"),
                token.path("expires_in").isNumber() ? token.path("expires_in").asLong() : null);
    }

    private ApplicationResponse approvedApplication(String clientId, String redirectUri) {
        ApplicationResponse application = organizationRepository.findApplicationByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
        if (!"ACTIVE".equalsIgnoreCase(application.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Application is not active.");
        }
        if (redirectUri != null && !redirectUri.isBlank() && !redirectUri.equals(application.redirectUri())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Redirect URI is not registered for this application.");
        }
        return application;
    }

    private String keycloakUsername(String applicationId, String externalUsername) {
        return (applicationId + "_" + externalUsername.trim().replaceAll("[^A-Za-z0-9._@-]", "_")).toLowerCase();
    }

    private String externalUsername(String applicationId, String username) {
        String trimmed = username.trim();
        String prefix = applicationId.toLowerCase() + "_";
        return trimmed.toLowerCase().startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }

    private String legacyKeycloakUsername(String applicationId, String externalUsername) {
        return applicationId + "_" + externalUsername.trim().replaceAll("[^A-Za-z0-9._@-]", "_");
    }

    private JsonNode authenticateApplicationUser(String applicationId, String externalUsername, String password) {
        String normalizedUsername = keycloakUsername(applicationId, externalUsername);
        try {
            return authenticateUsernameWithSetupRepair(normalizedUsername, externalUsername, password);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.BadRequest exception) {
            String legacyUsername = legacyKeycloakUsername(applicationId, externalUsername);
            if (legacyUsername.equals(normalizedUsername)) {
                throw exception;
            }
            return authenticateUsernameWithSetupRepair(legacyUsername, externalUsername, password);
        }
    }

    private JsonNode authenticateUsernameWithSetupRepair(String keycloakUsername, String externalUsername, String password) {
        try {
            return keycloakAdminClient.authenticateApplicationUser(keycloakUsername, password);
        } catch (HttpClientErrorException.BadRequest exception) {
            if (!isAccountSetupError(exception)) {
                throw exception;
            }
            keycloakAdminClient.completeApplicationUserSetup(keycloakUsername, externalUsername, password);
            return keycloakAdminClient.authenticateApplicationUser(keycloakUsername, password);
        }
    }

    private boolean isAccountSetupError(RestClientResponseException exception) {
        String detail = exception.getResponseBodyAsString();
        return detail != null && detail.toLowerCase().contains("account is not fully set up");
    }

    private String firstPresent(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            String value = stringValue(fields.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
