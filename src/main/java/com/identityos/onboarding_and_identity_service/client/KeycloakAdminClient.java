package com.identityos.onboarding_and_identity_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Component
public class KeycloakAdminClient {
    private final RestClient restClient;
    private final String targetRealm;
    private final String adminRealm;
    private final String clientId;
    private final String userClientId;
    private final String username;
    private final String password;

    public KeycloakAdminClient(
            RestClient.Builder builder,
            @Value("${keycloak.admin.base-url}") String baseUrl,
            @Value("${keycloak.admin.target-realm}") String targetRealm,
            @Value("${keycloak.admin.admin-realm}") String adminRealm,
            @Value("${keycloak.admin.client-id}") String clientId,
            @Value("${keycloak.admin.user-client-id}") String userClientId,
            @Value("${keycloak.admin.username}") String username,
            @Value("${keycloak.admin.password}") String password) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.targetRealm = targetRealm;
        this.adminRealm = adminRealm;
        this.clientId = clientId;
        this.userClientId = userClientId;
        this.username = username;
        this.password = password;
    }

    public boolean createOrganizationAdmin(String organizationId, String representativeName, String email) {
        String token = requestAdminToken();
        String userId = findUserIdByUsername(token, organizationId);
        boolean userCreated = false;
        if (userId == null) {
            userId = createUser(token, organizationId, representativeName, email);
            userCreated = true;
        } else {
            updateUser(token, userId, organizationId, representativeName, email);
        }
        try {
            assignOrganizationAdminRole(token, userId);
        } catch (RuntimeException exception) {
            if (userCreated) {
                deleteUser(token, userId);
            }
            throw exception;
        }

        try {
            sendVerificationEmail(token, userId);
            return true;
        } catch (RuntimeException exception) {
            // SMTP is optional in local development; the user remains provisioned.
            System.err.println("Keycloak verification email was not sent: " + exception.getMessage());
            return false;
        }
    }

    public void ensureApplicationSuperAdmin(
            String adminUsername,
            String displayName,
            String email,
            String password,
            String organizationId,
            String applicationId,
            String clientId) {
        String token = requestAdminToken();
        String userId = findUserIdByUsername(token, adminUsername);
        if (userId == null) {
            userId = createSelfHostedAdminUser(
                    token,
                    adminUsername,
                    displayName,
                    email,
                    password,
                    organizationId,
                    applicationId,
                    clientId);
        } else {
            updateSelfHostedAdminUser(
                    token,
                    userId,
                    adminUsername,
                    displayName,
                    email,
                    organizationId,
                    applicationId,
                    clientId);
            if (password != null && !password.isBlank()) {
                resetApplicationUserPassword(token, userId, password);
            }
        }
        assignRealmRole(token, userId, "APPLICATION_SUPER_ADMIN", "Self-hosted application administrator");
        assignRealmRole(token, userId, "APPLICATION_USER", "End user registered through a third-party application");
    }

    public String createApplicationUser(
            String keycloakUsername,
            String externalUsername,
            String password,
            String email,
            String organizationId,
            String applicationId,
            String clientId,
            Map<String, Object> submittedFields) {
        String token = requestAdminToken();
        String userId = findUserIdByUsername(token, keycloakUsername);
        String usersGroupId = ensureGroupHierarchy(token, organizationId, applicationId);
        if (userId == null) {
            userId = createApplicationUserInKeycloak(
                    token,
                    keycloakUsername,
                    externalUsername,
                    password,
                    email,
                    organizationId,
                    applicationId,
                    clientId,
                    submittedFields);
        } else {
            updateApplicationUserSetup(
                    token,
                    userId,
                    keycloakUsername,
                    externalUsername,
                    email,
                    organizationId,
                    applicationId,
                    clientId,
                    submittedFields);
            resetApplicationUserPassword(token, userId, password);
        }
        joinGroup(token, userId, usersGroupId);
        assignApplicationUserRole(token, userId);
        return userId;
    }

    public String createMigratedApplicationUser(
            String keycloakUsername,
            String externalUsername,
            String password,
            boolean temporaryPassword,
            boolean sendPasswordSetupEmail,
            String email,
            String firstName,
            String lastName,
            String organizationId,
            String applicationId,
            String clientId,
            String roleName,
            Map<String, Object> submittedFields) {
        String token = requestAdminToken();
        String userId = findUserIdByUsername(token, keycloakUsername);
        String usersGroupId = ensureGroupHierarchy(token, organizationId, applicationId);
        Map<String, Object> fields = new LinkedHashMap<>();
        if (submittedFields != null) {
            fields.putAll(submittedFields);
        }
        putIfPresent(fields, "firstName", firstName);
        putIfPresent(fields, "lastName", lastName);
        if (userId == null) {
            userId = createApplicationUserInKeycloak(
                    token,
                    keycloakUsername,
                    externalUsername,
                    password,
                    temporaryPassword,
                    sendPasswordSetupEmail,
                    email,
                    organizationId,
                    applicationId,
                    clientId,
                    fields);
        } else {
            updateApplicationUserSetup(
                    token,
                    userId,
                    keycloakUsername,
                    externalUsername,
                    email,
                    organizationId,
                    applicationId,
                    clientId,
                    fields,
                    sendPasswordSetupEmail);
            if (!sendPasswordSetupEmail && password != null && !password.isBlank()) {
                resetApplicationUserPassword(token, userId, password, temporaryPassword);
            }
        }
        joinGroup(token, userId, usersGroupId);
        String normalizedRole = "APPLICATION_SUPER_ADMIN".equalsIgnoreCase(roleName)
                ? "APPLICATION_SUPER_ADMIN"
                : "APPLICATION_USER";
        assignRealmRole(token, userId, normalizedRole, "Migrated Identity OS user");
        if ("APPLICATION_SUPER_ADMIN".equals(normalizedRole)) {
            assignApplicationUserRole(token, userId);
        }
        if (sendPasswordSetupEmail) {
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Email is required to send password setup link for migrated user.");
            }
            sendPasswordSetupEmail(token, userId);
        }
        return userId;
    }

    public void completeApplicationUserSetup(String keycloakUsername, String externalUsername, String password) {
        String token = requestAdminToken();
        String userId = findUserIdByUsername(token, keycloakUsername);
        if (userId == null) {
            return;
        }
        JsonNode existingUser = getUser(token, userId);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", keycloakUsername);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("requiredActions", List.of());
        if (existingUser != null) {
            putIfJsonPresent(user, "id", existingUser.get("id"));
            putIfJsonPresent(user, "email", existingUser.get("email"));
            putIfJsonPresent(user, "firstName", existingUser.get("firstName"));
            putIfJsonPresent(user, "lastName", existingUser.get("lastName"));
            if (existingUser.has("attributes") && !existingUser.get("attributes").isNull()) {
                user.put("attributes", existingUser.get("attributes"));
            }
            if (existingUser.has("groups") && !existingUser.get("groups").isNull()) {
                user.put("groups", existingUser.get("groups"));
            }
        }
        user.putIfAbsent("firstName", profileFirstName(externalUsername));
        user.putIfAbsent("lastName", profileLastName());
        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
        resetApplicationUserPassword(token, userId, password);
    }

    public JsonNode authenticateApplicationUser(String keycloakUsername, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", userClientId);
        form.add("username", keycloakUsername);
        form.add("password", password);

        JsonNode response = restClient.post()
                .uri("/realms/" + targetRealm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak token was not returned");
        }
        return response;
    }

    private String requestAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", username);
        form.add("password", password);

        JsonNode response = restClient.post()
            .uri("/realms/" + adminRealm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak admin token was not returned");
        }
        return response.get("access_token").asText();
    }

    private String createUser(String token, String organizationId, String representativeName, String email) {
        String[] nameParts = representativeName.trim().split("\\s+", 2);
        Map<String, Object> user = Map.of(
                "username", organizationId,
                "email", email,
                "emailVerified", true,
                "enabled", true,
                "firstName", nameParts[0],
                "lastName", nameParts.length > 1 ? nameParts[1] : "",
                "attributes", Map.of("organization_id", List.of(organizationId)),
                "credentials", List.of(Map.of(
                    "type", "password",
                    "value", organizationId,
                    "temporary", true)),
                "requiredActions", List.of("UPDATE_PASSWORD"));

        var response = restClient.post()
                .uri("/admin/realms/" + targetRealm + "/users")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
        String location = response.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private String createSelfHostedAdminUser(
            String token,
            String adminUsername,
            String displayName,
            String email,
            String password,
            String organizationId,
            String applicationId,
            String clientId) {
        String[] nameParts = displayName.trim().isBlank()
                ? new String[] {adminUsername}
                : displayName.trim().split("\\s+", 2);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", adminUsername);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("firstName", nameParts[0]);
        user.put("lastName", nameParts.length > 1 ? nameParts[1] : "Admin");
        if (email != null && !email.isBlank()) {
            user.put("email", email);
        }
        user.put("attributes", Map.of(
                "organization_id", List.of(organizationId),
                "application_id", List.of(applicationId),
                "client_id", List.of(clientId),
                "external_username", List.of(adminUsername)));
        user.put("requiredActions", List.of());
        if (password != null && !password.isBlank()) {
            user.put("credentials", List.of(Map.of(
                    "type", "password",
                    "value", password,
                    "temporary", false)));
        }

        var response = restClient.post()
                .uri("/admin/realms/" + targetRealm + "/users")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
        String location = response.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void updateSelfHostedAdminUser(
            String token,
            String userId,
            String adminUsername,
            String displayName,
            String email,
            String organizationId,
            String applicationId,
            String clientId) {
        String[] nameParts = displayName.trim().isBlank()
                ? new String[] {adminUsername}
                : displayName.trim().split("\\s+", 2);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", adminUsername);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("firstName", nameParts[0]);
        user.put("lastName", nameParts.length > 1 ? nameParts[1] : "Admin");
        if (email != null && !email.isBlank()) {
            user.put("email", email);
        }
        user.put("attributes", Map.of(
                "organization_id", List.of(organizationId),
                "application_id", List.of(applicationId),
                "client_id", List.of(clientId),
                "external_username", List.of(adminUsername)));
        user.put("requiredActions", List.of());

        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    private String findUserIdByUsername(String token, String username) {
        JsonNode users = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/realms/" + targetRealm + "/users")
                        .queryParam("username", username)
                        .queryParam("exact", true)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
        if (users == null || !users.isArray() || users.isEmpty()) {
            return null;
        }
        return users.get(0).get("id").asText();
    }

    private JsonNode getUser(String token, String userId) {
        return restClient.get()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
    }

    private void updateUser(String token, String userId, String organizationId, String representativeName, String email) {
        String[] nameParts = representativeName.trim().split("\\s+", 2);
        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "username", organizationId,
                    "email", email,
                    "emailVerified", true,
                    "enabled", true,
                    "firstName", nameParts[0],
                    "lastName", nameParts.length > 1 ? nameParts[1] : "",
                    "attributes", Map.of("organization_id", List.of(organizationId))))
                .retrieve()
                .toBodilessEntity();
    }

    private void assignOrganizationAdminRole(String token, String userId) {
        JsonNode role;
        try {
            role = getOrganizationAdminRole(token);
        } catch (HttpClientErrorException.NotFound exception) {
            restClient.post()
                .uri("/admin/realms/" + targetRealm + "/roles")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "name", "ORGANISATION_ADMIN",
                    "description", "Administrator for a single organization"))
                .retrieve()
                .toBodilessEntity();
            role = getOrganizationAdminRole(token);
        }
        try {
            restClient.post()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId + "/role-mappings/realm")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict exception) {
            // Role mapping is already present; the desired state is satisfied.
        }
    }

    private JsonNode getOrganizationAdminRole(String token) {
        return restClient.get()
                .uri("/admin/realms/" + targetRealm + "/roles/ORGANISATION_ADMIN")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
    }

    private void assignApplicationUserRole(String token, String userId) {
        assignRealmRole(token, userId, "APPLICATION_USER", "End user registered through a third-party application");
    }

    private void assignRealmRole(String token, String userId, String roleName, String description) {
        JsonNode role;
        try {
            role = getRealmRole(token, roleName);
        } catch (HttpClientErrorException.NotFound exception) {
            restClient.post()
                .uri("/admin/realms/" + targetRealm + "/roles")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "name", roleName,
                    "description", description))
                .retrieve()
                .toBodilessEntity();
            role = getRealmRole(token, roleName);
        }
        try {
            restClient.post()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId + "/role-mappings/realm")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict exception) {
            // Role mapping is already present; the desired state is satisfied.
        }
    }

    private JsonNode getRealmRole(String token, String roleName) {
        return restClient.get()
                .uri("/admin/realms/" + targetRealm + "/roles/" + roleName)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
    }

    private String createApplicationUserInKeycloak(
            String token,
            String keycloakUsername,
            String externalUsername,
            String password,
            String email,
            String organizationId,
            String applicationId,
            String clientId,
            Map<String, Object> submittedFields) {
        return createApplicationUserInKeycloak(
                token,
                keycloakUsername,
                externalUsername,
                password,
                false,
                false,
                email,
                organizationId,
                applicationId,
                clientId,
                submittedFields);
    }

    private String createApplicationUserInKeycloak(
            String token,
            String keycloakUsername,
            String externalUsername,
            String password,
            boolean temporaryPassword,
            boolean passwordSetupRequired,
            String email,
            String organizationId,
            String applicationId,
            String clientId,
            Map<String, Object> submittedFields) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", keycloakUsername);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("requiredActions", passwordSetupRequired ? List.of("UPDATE_PASSWORD") : List.of());
        if (email != null && !email.isBlank()) {
            user.put("email", email);
        }
        putIfPresent(user, "firstName", submittedFields.get("firstName"));
        putIfPresent(user, "lastName", submittedFields.get("lastName"));
        user.putIfAbsent("firstName", profileFirstName(externalUsername));
        user.putIfAbsent("lastName", profileLastName());
        user.put("attributes", Map.of(
                "organization_id", List.of(organizationId),
                "application_id", List.of(applicationId),
                "client_id", List.of(clientId),
                "external_username", List.of(externalUsername)));
        if (password != null && !password.isBlank()) {
            user.put("credentials", List.of(Map.of(
                    "type", "password",
                    "value", password,
                    "temporary", temporaryPassword)));
        }

        var response = restClient.post()
                .uri("/admin/realms/" + targetRealm + "/users")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
        String location = response.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void updateApplicationUserSetup(
            String token,
            String userId,
            String keycloakUsername,
            String externalUsername,
            String email,
            String organizationId,
            String applicationId,
            String clientId,
            Map<String, Object> submittedFields) {
        updateApplicationUserSetup(
                token,
                userId,
                keycloakUsername,
                externalUsername,
                email,
                organizationId,
                applicationId,
                clientId,
                submittedFields,
                false);
    }

    private void updateApplicationUserSetup(
            String token,
            String userId,
            String keycloakUsername,
            String externalUsername,
            String email,
            String organizationId,
            String applicationId,
            String clientId,
            Map<String, Object> submittedFields,
            boolean passwordSetupRequired) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", keycloakUsername);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("requiredActions", passwordSetupRequired ? List.of("UPDATE_PASSWORD") : List.of());
        if (email != null && !email.isBlank()) {
            user.put("email", email);
        }
        putIfPresent(user, "firstName", submittedFields.get("firstName"));
        putIfPresent(user, "lastName", submittedFields.get("lastName"));
        user.putIfAbsent("firstName", profileFirstName(externalUsername));
        user.putIfAbsent("lastName", profileLastName());
        user.put("attributes", Map.of(
                "organization_id", List.of(organizationId),
                "application_id", List.of(applicationId),
                "client_id", List.of(clientId),
                "external_username", List.of(externalUsername)));

        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    private void resetApplicationUserPassword(String token, String userId, String password) {
        resetApplicationUserPassword(token, userId, password, false);
    }

    private void resetApplicationUserPassword(String token, String userId, String password, boolean temporaryPassword) {
        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId + "/reset-password")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", temporaryPassword))
                .retrieve()
                .toBodilessEntity();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, String.valueOf(value));
        }
    }

    private void putIfJsonPresent(Map<String, Object> target, String key, JsonNode value) {
        if (value != null && !value.isNull() && !value.asText("").isBlank()) {
            target.put(key, value.asText());
        }
    }

    private String profileFirstName(String externalUsername) {
        String value = externalUsername == null ? "" : externalUsername.trim();
        int emailSeparator = value.indexOf('@');
        if (emailSeparator > 0) {
            value = value.substring(0, emailSeparator);
        }
        value = value.replaceAll("[^A-Za-z0-9 -]", " ").trim();
        return value.isBlank() ? "Application" : value;
    }

    private String profileLastName() {
        return "User";
    }

    private String ensureGroupHierarchy(String token, String organizationId, String applicationId) {
        String organizationGroupId = ensureRootGroup(token, organizationId);
        String applicationGroupId = ensureChildGroup(token, organizationGroupId, applicationId);
        return ensureChildGroup(token, applicationGroupId, "users");
    }

    private String ensureRootGroup(String token, String groupName) {
        String groupId = findRootGroupId(token, groupName);
        if (groupId != null) {
            return groupId;
        }
        restClient.post()
                .uri("/admin/realms/" + targetRealm + "/groups")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", groupName))
                .retrieve()
                .toBodilessEntity();
        groupId = findRootGroupId(token, groupName);
        if (groupId == null) {
            throw new IllegalStateException("Keycloak group was not created: " + groupName);
        }
        return groupId;
    }

    private String ensureChildGroup(String token, String parentGroupId, String groupName) {
        String groupId = findChildGroupId(token, parentGroupId, groupName);
        if (groupId != null) {
            return groupId;
        }
        restClient.post()
                .uri("/admin/realms/" + targetRealm + "/groups/" + parentGroupId + "/children")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", groupName))
                .retrieve()
                .toBodilessEntity();
        groupId = findChildGroupId(token, parentGroupId, groupName);
        if (groupId == null) {
            throw new IllegalStateException("Keycloak child group was not created: " + groupName);
        }
        return groupId;
    }

    private String findRootGroupId(String token, String groupName) {
        JsonNode groups = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/realms/" + targetRealm + "/groups")
                        .queryParam("search", groupName)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
        return findGroupIdByName(groups, groupName);
    }

    private String findChildGroupId(String token, String parentGroupId, String groupName) {
        JsonNode groups = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/realms/" + targetRealm + "/groups/" + parentGroupId + "/children")
                        .queryParam("search", groupName)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
        return findGroupIdByName(groups, groupName);
    }

    private String findGroupIdByName(JsonNode groups, String groupName) {
        if (groups == null || !groups.isArray()) {
            return null;
        }
        for (JsonNode group : groups) {
            if (groupName.equals(group.path("name").asText())) {
                return group.path("id").asText();
            }
        }
        return null;
    }

    private void joinGroup(String token, String userId, String groupId) {
        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId + "/groups/" + groupId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity();
    }

    private void sendVerificationEmail(String token, String userId) {
        sendPasswordSetupEmail(token, userId);
    }

    private void sendPasswordSetupEmail(String token, String userId) {
        restClient.put()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId + "/execute-actions-email")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD"))
                .retrieve()
                .toBodilessEntity();
    }

    private void deleteUser(String token, String userId) {
        restClient.delete()
                .uri("/admin/realms/" + targetRealm + "/users/" + userId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity();
    }
}
