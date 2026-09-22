package com.identityos.onboarding_and_identity_service.repository;

import com.identityos.onboarding_and_identity_service.dto.RegisterOrganizationRequest;
import com.identityos.onboarding_and_identity_service.dto.ApplicationRegistrationRequest;
import com.identityos.onboarding_and_identity_service.dto.ApplicationResponse;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionRequest;
import com.identityos.onboarding_and_identity_service.dto.IdentitySchemaVersionResponse;
import com.identityos.onboarding_and_identity_service.dto.OrganizationProfileResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrganizationRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrganizationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(RegisterOrganizationRequest request, String organizationId) {
        UUID entityId = request.entityId() == null
                ? jdbcTemplate.queryForObject("""
                    INSERT INTO entities (entity_type, status)
                    VALUES ('ORGANIZATION', 'ACTIVE')
                    RETURNING id
                    """, UUID.class)
                : request.entityId();

        UUID organizationUuid = jdbcTemplate.queryForObject("""
                INSERT INTO organizations (
                    entity_id, organization_id, organization_name, organization_type,
                    country_code, official_email, official_phone, registration_number,
                    registration_authority, incorporation_date, verification_id_type,
                    verification_id, verification_id_verify_status, website_url, logo_url,
                    status, approval_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'APPROVED')
                RETURNING id
                """,
                UUID.class,
                entityId, organizationId, request.organizationName(),
                request.organizationType(), request.countryCode(), request.officialEmail(),
                request.officialPhone(), request.registrationNumber(), request.registrationAuthority(),
                request.incorporationDate(), request.verificationIdType(), request.verificationId(),
                request.verificationIdVerifyStatus(), request.websiteUrl(), request.logoUrl());

        insertRepresentative(organizationUuid, request);
        insertAddress(organizationUuid, request);
    }

    private void insertRepresentative(UUID organizationUuid, RegisterOrganizationRequest request) {
        String representativeName = request.representativeName().trim();
        String[] nameParts = representativeName.split("\\s+", 2);
        jdbcTemplate.update("""
                INSERT INTO organization_representatives (
                    organization_id, first_name, last_name, designation, email,
                    email_verified, mobile_number, mobile_verified, emp_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationUuid,
                nameParts[0],
                nameParts.length > 1 ? nameParts[1] : "",
                request.representativeDesignation(),
                request.representativeEmail(),
                false,
                request.representativeMobile(),
                false,
                request.representativeEmployeeId());
    }

    private void insertAddress(UUID organizationUuid, RegisterOrganizationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO organization_addresses (
                    organization_id, address_type, address_line_1, address_line_2,
                    city, district, state, postal_code, country_code, address_proof_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationUuid,
                blankToDefault(request.addressType(), "REGISTERED"),
                blankToDefault(request.addressLine1(), "-"),
                request.addressLine2(),
                blankToDefault(request.city(), "-"),
                request.district(),
                blankToDefault(request.state(), "-"),
                blankToDefault(request.postalCode(), "-"),
                blankToDefault(request.countryCode(), "-"),
                request.addressProofRef());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public Optional<OrganizationProfileResponse> findByOrganizationId(String organizationId) {
        return jdbcTemplate.query("""
                SELECT o.organization_id, o.organization_name, o.organization_type, o.country_code,
                       o.official_email, o.official_phone, o.registration_number, o.registration_authority,
                       o.verification_id_type, o.verification_id, o.verification_id_verify_status,
                       o.website_url, o.logo_url, o.status, o.approval_status,
                       r.first_name AS representative_first_name,
                       r.last_name AS representative_last_name,
                       r.designation AS representative_designation,
                       r.email AS representative_email,
                       r.mobile_number AS representative_mobile_number,
                       r.emp_id AS representative_employee_id,
                       a.address_type, a.address_line_1, a.address_line_2,
                       a.city, a.district, a.state, a.postal_code,
                       a.country_code AS address_country_code,
                       a.address_proof_ref
                FROM organizations o
                LEFT JOIN LATERAL (
                    SELECT first_name, last_name, designation, email, mobile_number, emp_id
                    FROM organization_representatives
                    WHERE organization_id = o.id
                    LIMIT 1
                ) r ON TRUE
                LEFT JOIN LATERAL (
                    SELECT address_type, address_line_1, address_line_2, city, district,
                           state, postal_code, country_code, address_proof_ref
                    FROM organization_addresses
                    WHERE organization_id = o.id
                    LIMIT 1
                ) a ON TRUE
                WHERE o.organization_id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(new OrganizationProfileResponse(
                    resultSet.getString("organization_id"),
                    resultSet.getString("organization_name"),
                    resultSet.getString("organization_type"),
                    resultSet.getString("country_code"),
                    resultSet.getString("official_email"),
                    resultSet.getString("official_phone"),
                    resultSet.getString("registration_number"),
                    resultSet.getString("registration_authority"),
                    resultSet.getString("verification_id_type"),
                    resultSet.getString("verification_id"),
                    resultSet.getString("verification_id_verify_status"),
                    resultSet.getString("website_url"),
                    resultSet.getString("logo_url"),
                    resultSet.getString("status"),
                    resultSet.getString("approval_status"),
                    resultSet.getString("representative_first_name"),
                    resultSet.getString("representative_last_name"),
                    resultSet.getString("representative_designation"),
                    resultSet.getString("representative_email"),
                    resultSet.getString("representative_mobile_number"),
                    resultSet.getString("representative_employee_id"),
                    resultSet.getString("address_type"),
                    resultSet.getString("address_line_1"),
                    resultSet.getString("address_line_2"),
                    resultSet.getString("city"),
                    resultSet.getString("district"),
                    resultSet.getString("state"),
                    resultSet.getString("postal_code"),
                    resultSet.getString("address_country_code"),
                    resultSet.getString("address_proof_ref")))
                : Optional.empty(), organizationId);
    }

    public List<OrganizationProfileResponse> findOrganizations(String approvalStatus) {
        if (approvalStatus == null || approvalStatus.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT organization_id, organization_name, organization_type, country_code,
                           official_email, official_phone, registration_number, registration_authority,
                           verification_id_type, verification_id, verification_id_verify_status,
                           website_url, logo_url, status, approval_status
                    FROM organizations
                    ORDER BY created_at DESC
                    """, organizationRowMapper());
        }

        String sql = """
                SELECT organization_id, organization_name, organization_type, country_code,
                       official_email, official_phone, registration_number, registration_authority,
                       verification_id_type, verification_id, verification_id_verify_status,
                       website_url, logo_url, status, approval_status
                FROM organizations
                WHERE approval_status = ?
                ORDER BY created_at DESC
                """;
        return jdbcTemplate.query(sql, organizationRowMapper(), approvalStatus);
    }

    public void updateApprovalStatus(String organizationId, String decision) {
        String approvalStatus = "APPROVED".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED";
        String status = "APPROVED".equals(approvalStatus) ? "ACTIVE" : "INACTIVE";
        jdbcTemplate.update("""
                UPDATE organizations
                SET approval_status = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE organization_id = ?
                """, approvalStatus, status, organizationId);
    }

    public ApplicationResponse insertApplication(String organizationId, ApplicationRegistrationRequest request) {
        UUID entityId = jdbcTemplate.queryForObject("""
                INSERT INTO entities (entity_type, status)
                VALUES ('APPLICATION', 'ACTIVE')
                RETURNING id
                """, UUID.class);
        UUID organizationUuid = jdbcTemplate.queryForObject("""
                SELECT id FROM organizations WHERE organization_id = ?
                """, UUID.class, organizationId);
        String applicationId = "app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        jdbcTemplate.queryForObject("""
                INSERT INTO applications (
                    entity_id, organization_id, application_id, application_name,
                    application_type, description, redirect_uri, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                RETURNING id, application_id, application_name, application_type, description,
                          redirect_uri, status, trust_score, created_at
                """, (resultSet, rowNum) -> new ApplicationResponse(
                    resultSet.getString("id"),
                    organizationId,
                    null,
                    resultSet.getString("application_id"),
                    resultSet.getString("application_name"),
                    resultSet.getString("application_type"),
                    resultSet.getString("description"),
                    resultSet.getString("redirect_uri"),
                    null,
                    null,
                    resultSet.getString("status"),
                    resultSet.getBigDecimal("trust_score"),
                    resultSet.getTimestamp("created_at").toLocalDateTime()),
                entityId,
                organizationUuid,
                applicationId,
                request.applicationName(),
                request.applicationType(),
                request.description(),
                request.redirectUri());
        ensureApplicationClient(applicationId);
        return findApplicationByApplicationId(applicationId).orElseThrow();
    }

    public List<ApplicationResponse> findApplications(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT a.id, o.organization_id, o.organization_name, a.application_id,
                           a.application_name, a.application_type, a.description, a.redirect_uri,
                           c.client_id, c.client_secret, a.status, a.trust_score, a.created_at
                    FROM applications a
                    JOIN organizations o ON o.id = a.organization_id
                    LEFT JOIN application_clients c ON c.application_id = a.id AND c.active = TRUE
                    ORDER BY a.created_at DESC
                    """, applicationRowMapper());
        }

        return jdbcTemplate.query("""
                SELECT a.id, o.organization_id, o.organization_name, a.application_id,
                       a.application_name, a.application_type, a.description, a.redirect_uri,
                       c.client_id, c.client_secret, a.status, a.trust_score, a.created_at
                FROM applications a
                JOIN organizations o ON o.id = a.organization_id
                LEFT JOIN application_clients c ON c.application_id = a.id AND c.active = TRUE
                WHERE o.organization_id = ?
                ORDER BY a.created_at DESC
                """, applicationRowMapper(), organizationId);
    }

    public ApplicationResponse updateApplicationStatus(String applicationId, String decision) {
        String status = "APPROVED".equalsIgnoreCase(decision) || "ACTIVE".equalsIgnoreCase(decision)
                ? "ACTIVE"
                : "SUSPENDED";
        jdbcTemplate.update("""
                UPDATE applications
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE application_id = ?
                """, status, applicationId);
        if ("ACTIVE".equals(status)) {
            ensureApplicationClient(applicationId);
        }
        return findApplicationByApplicationId(applicationId).orElseThrow();
    }

    public Optional<ApplicationResponse> findApplicationByApplicationId(String applicationId) {
        return jdbcTemplate.query("""
                SELECT a.id, o.organization_id, o.organization_name, a.application_id,
                       a.application_name, a.application_type, a.description, a.redirect_uri,
                       COALESCE(c.client_id, a.application_id) AS client_id, c.client_secret,
                       a.status, a.trust_score, a.created_at
                FROM applications a
                JOIN organizations o ON o.id = a.organization_id
                LEFT JOIN application_clients c ON c.application_id = a.id AND c.active = TRUE
                WHERE a.application_id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(applicationRowMapper().mapRow(resultSet, 0))
                : Optional.empty(), applicationId);
    }

    public Optional<ApplicationResponse> findApplicationByClientId(String clientId) {
        Optional<ApplicationResponse> application = findApplicationByApplicationId(clientId);
        if (application.isPresent()) {
            return application;
        }
        return jdbcTemplate.query("""
                SELECT a.id, o.organization_id, o.organization_name, a.application_id,
                       a.application_name, a.application_type, a.description, a.redirect_uri,
                       c.client_id, c.client_secret, a.status, a.trust_score, a.created_at
                FROM application_clients c
                JOIN applications a ON a.id = c.application_id
                JOIN organizations o ON o.id = a.organization_id
                WHERE c.client_id = ?
                  AND c.active = TRUE
                """, resultSet -> resultSet.next()
                ? Optional.of(applicationRowMapper().mapRow(resultSet, 0))
                : Optional.empty(), clientId);
    }

    private void ensureApplicationClient(String applicationId) {
        UUID applicationUuid = jdbcTemplate.queryForObject("""
                SELECT id FROM applications WHERE application_id = ?
                """, UUID.class, applicationId);
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM application_clients WHERE application_id = ? AND active = TRUE
                """, Integer.class, applicationUuid);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO application_clients (application_id, client_id, client_secret, active)
                VALUES (?, ?, ?, TRUE)
                """, applicationUuid, applicationId, generateClientSecret());
    }

    private String generateClientSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "sec_" + HexFormat.of().formatHex(bytes);
    }

    public IdentitySchemaVersionResponse createSchemaVersion(
            String organizationId,
            String applicationId,
            IdentitySchemaVersionRequest request) {
        UUID organizationUuid = jdbcTemplate.queryForObject("""
                SELECT id FROM organizations WHERE organization_id = ?
                """, UUID.class, organizationId);
        UUID applicationUuid = jdbcTemplate.queryForObject("""
                SELECT id FROM applications WHERE application_id = ? AND organization_id = ?
                """, UUID.class, applicationId, organizationUuid);
        UUID schemaUuid = findOrCreateSchema(organizationUuid, applicationUuid, request);
        Integer versionNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM identity_schema_version
                WHERE schema_id = ?
                """, Integer.class, schemaUuid);
        String versionStatus = Boolean.TRUE.equals(request.submitForApproval()) ? "APPROVED" : "DRAFT";

        IdentitySchemaVersionResponse response = jdbcTemplate.queryForObject("""
                INSERT INTO identity_schema_version (
                    schema_id, version_number, schema_json, configuration_json,
                    status, change_summary, created_by
                ) VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> findSchemaVersionByVersionId(resultSet.getString("id")).orElseThrow(),
                schemaUuid,
                versionNumber,
                jsonb(request.schemaJson()),
                request.configurationJson() == null ? null : jsonb(request.configurationJson()),
                versionStatus,
                request.changeSummary(),
                organizationUuid);

        jdbcTemplate.update("""
                INSERT INTO schema_version_change (
                    schema_id, from_version_id, to_version_id, change_type, change_details, changed_by
                ) VALUES (?, NULL, ?, ?, CAST(? AS jsonb), ?)
                """,
                schemaUuid,
                UUID.fromString(response.versionId()),
                versionNumber == 1 ? "INITIAL_VERSION" : "NEW_VERSION",
                jsonb(java.util.Map.of("summary", blankToDefault(request.changeSummary(), "Schema version created"))),
                organizationUuid);

        if (Boolean.TRUE.equals(request.submitForApproval())) {
            jdbcTemplate.update("""
                    INSERT INTO schema_version_approval (schema_version_id, submitted_by, status)
                    VALUES (?, ?, 'APPROVED')
                    """, UUID.fromString(response.versionId()), organizationUuid);
            jdbcTemplate.update("""
                    UPDATE identity_schema_version
                    SET published_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, UUID.fromString(response.versionId()));
            jdbcTemplate.update("""
                    UPDATE identity_schema
                    SET active_version_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, UUID.fromString(response.versionId()), schemaUuid);
        }

        return response;
    }

    public List<IdentitySchemaVersionResponse> findSchemas(String organizationId, String applicationId) {
        if ((organizationId == null || organizationId.isBlank()) && (applicationId == null || applicationId.isBlank())) {
            return jdbcTemplate.query(schemaListSql("""
                    WHERE v.version_number = (
                        SELECT MAX(version_number) FROM identity_schema_version WHERE schema_id = s.id
                    )
                    """), schemaVersionRowMapper());
        }
        if (applicationId == null || applicationId.isBlank()) {
            return jdbcTemplate.query(schemaListSql("""
                    WHERE o.organization_id = ?
                      AND v.version_number = (
                          SELECT MAX(version_number) FROM identity_schema_version WHERE schema_id = s.id
                      )
                    """), schemaVersionRowMapper(), organizationId);
        }
        return jdbcTemplate.query(schemaListSql("""
                WHERE o.organization_id = ?
                  AND a.application_id = ?
                  AND v.version_number = (
                  SELECT MAX(version_number) FROM identity_schema_version WHERE schema_id = s.id
                )
                """), schemaVersionRowMapper(), organizationId, applicationId);
    }

    public List<IdentitySchemaVersionResponse> findSchemaVersions(String organizationId, String schemaType) {
        if (schemaType == null || schemaType.isBlank()) {
            return jdbcTemplate.query(schemaHistorySql("""
                    WHERE o.organization_id = ?
                    """), schemaVersionRowMapper(), organizationId);
        }
        return jdbcTemplate.query(schemaHistorySql("""
                WHERE o.organization_id = ?
                  AND s.schema_type = ?
                """), schemaVersionRowMapper(), organizationId, schemaType.toUpperCase());
    }

    public Optional<IdentitySchemaVersionResponse> findApprovedSchemaForClient(String clientId, String schemaType) {
        return jdbcTemplate.query("""
                SELECT s.id AS schema_id, v.id AS version_id,
                       o.organization_id, o.organization_name,
                       a.application_id, a.application_name,
                       s.schema_type, s.schema_name, v.version_number,
                       v.schema_json::text AS schema_json,
                       v.configuration_json::text AS configuration_json,
                       v.status, v.change_summary, v.created_at, v.published_at
                FROM identity_schema s
                JOIN organizations o ON o.id = s.organization_id
                JOIN applications a ON a.id = s.application_id
                JOIN identity_schema_version v ON v.schema_id = s.id
                LEFT JOIN application_clients c ON c.application_id = a.id AND c.active = TRUE
                WHERE (a.application_id = ? OR c.client_id = ?)
                  AND s.schema_type = ?
                  AND v.status IN ('APPROVED', 'PUBLISHED')
                ORDER BY
                  CASE WHEN s.active_version_id = v.id THEN 0 ELSE 1 END,
                  v.published_at DESC NULLS LAST,
                  v.version_number DESC
                LIMIT 1
                """, resultSet -> resultSet.next()
                ? Optional.of(schemaVersionRowMapper().mapRow(resultSet, 0))
                : Optional.empty(), clientId, clientId, schemaType.toUpperCase());
    }

    public void updateSchemaVersionApproval(String versionId, String decision, String comments) {
        String normalizedDecision = decision == null ? "REJECTED" : decision.toUpperCase();
        String versionStatus = switch (normalizedDecision) {
            case "APPROVED" -> "APPROVED";
            case "CHANGES_REQUESTED" -> "DRAFT";
            default -> "REJECTED";
        };
        String approvalStatus = switch (normalizedDecision) {
            case "APPROVED" -> "APPROVED";
            case "CHANGES_REQUESTED" -> "CHANGES_REQUESTED";
            default -> "REJECTED";
        };
        UUID versionUuid = UUID.fromString(versionId);
        jdbcTemplate.update("""
                UPDATE identity_schema_version
                SET status = ?, published_at = CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE published_at END
                WHERE id = ?
                """, versionStatus, versionStatus, versionUuid);
        jdbcTemplate.update("""
                UPDATE schema_version_approval
                SET status = ?, comments = ?, reviewed_at = CURRENT_TIMESTAMP
                WHERE schema_version_id = ?
                """, approvalStatus, comments, versionUuid);
        if ("APPROVED".equals(versionStatus)) {
            jdbcTemplate.update("""
                    UPDATE identity_schema
                    SET active_version_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = (SELECT schema_id FROM identity_schema_version WHERE id = ?)
                    """, versionUuid, versionUuid);
        }
    }

    private UUID findOrCreateSchema(UUID organizationUuid, UUID applicationUuid, IdentitySchemaVersionRequest request) {
        List<UUID> existing = jdbcTemplate.query("""
                SELECT id
                FROM identity_schema
                WHERE organization_id = ?
                  AND application_id = ?
                  AND schema_type = ?
                  AND schema_name = ?
                """, (resultSet, rowNum) -> (UUID) resultSet.getObject("id"),
                organizationUuid,
                applicationUuid,
                request.schemaType().toUpperCase(),
                request.schemaName());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO identity_schema (
                    organization_id, application_id, schema_type, schema_name, created_by
                ) VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class,
                organizationUuid,
                applicationUuid,
                request.schemaType().toUpperCase(),
                request.schemaName(),
                organizationUuid);
    }

    private Optional<IdentitySchemaVersionResponse> findSchemaVersionByVersionId(String versionId) {
        return jdbcTemplate.query(schemaListSql("WHERE v.id = ?"), resultSet -> resultSet.next()
                ? Optional.of(schemaVersionRowMapper().mapRow(resultSet, 0))
                : Optional.empty(), UUID.fromString(versionId));
    }

    private String schemaListSql(String whereClause) {
        return """
                SELECT s.id AS schema_id, v.id AS version_id,
                       o.organization_id, o.organization_name,
                       a.application_id, a.application_name,
                       s.schema_type, s.schema_name, v.version_number,
                       v.schema_json::text AS schema_json,
                       v.configuration_json::text AS configuration_json,
                       v.status, v.change_summary, v.created_at, v.published_at
                FROM identity_schema s
                JOIN organizations o ON o.id = s.organization_id
                JOIN applications a ON a.id = s.application_id
                JOIN identity_schema_version v ON v.schema_id = s.id
                %s
                ORDER BY v.created_at DESC
                """.formatted(whereClause);
    }

    private String schemaHistorySql(String whereClause) {
        return """
                SELECT s.id AS schema_id, v.id AS version_id,
                       o.organization_id, o.organization_name,
                       a.application_id, a.application_name,
                       s.schema_type, s.schema_name, v.version_number,
                       v.schema_json::text AS schema_json,
                       v.configuration_json::text AS configuration_json,
                       COALESCE(approval.status, v.status) AS status,
                       COALESCE(v.change_summary, version_change.change_type) AS change_summary,
                       v.created_at, v.published_at
                FROM identity_schema s
                JOIN organizations o ON o.id = s.organization_id
                JOIN applications a ON a.id = s.application_id
                JOIN identity_schema_version v ON v.schema_id = s.id
                LEFT JOIN schema_version_approval approval ON approval.schema_version_id = v.id
                LEFT JOIN LATERAL (
                    SELECT change_type
                    FROM schema_version_change
                    WHERE to_version_id = v.id
                    LIMIT 1
                ) version_change ON TRUE
                %s
                ORDER BY s.schema_name ASC, v.version_number DESC, v.created_at DESC
                """.formatted(whereClause);
    }

    private String jsonb(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid schema JSON", exception);
        }
    }

    private RowMapper<OrganizationProfileResponse> organizationRowMapper() {
        return (resultSet, rowNum) -> new OrganizationProfileResponse(
                resultSet.getString("organization_id"),
                resultSet.getString("organization_name"),
                resultSet.getString("organization_type"),
                resultSet.getString("country_code"),
                resultSet.getString("official_email"),
                resultSet.getString("official_phone"),
                resultSet.getString("registration_number"),
                resultSet.getString("registration_authority"),
                resultSet.getString("verification_id_type"),
                resultSet.getString("verification_id"),
                resultSet.getString("verification_id_verify_status"),
                resultSet.getString("website_url"),
                resultSet.getString("logo_url"),
                resultSet.getString("status"),
                resultSet.getString("approval_status"));
    }

    private RowMapper<ApplicationResponse> applicationRowMapper() {
        return (resultSet, rowNum) -> new ApplicationResponse(
                resultSet.getString("id"),
                resultSet.getString("organization_id"),
                resultSet.getString("organization_name"),
                resultSet.getString("application_id"),
                resultSet.getString("application_name"),
                resultSet.getString("application_type"),
                resultSet.getString("description"),
                resultSet.getString("redirect_uri"),
                resultSet.getString("client_id"),
                resultSet.getString("client_secret"),
                resultSet.getString("status"),
                resultSet.getBigDecimal("trust_score"),
                resultSet.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<IdentitySchemaVersionResponse> schemaVersionRowMapper() {
        return (resultSet, rowNum) -> new IdentitySchemaVersionResponse(
                resultSet.getString("schema_id"),
                resultSet.getString("version_id"),
                resultSet.getString("organization_id"),
                resultSet.getString("organization_name"),
                resultSet.getString("application_id"),
                resultSet.getString("application_name"),
                resultSet.getString("schema_type"),
                resultSet.getString("schema_name"),
                resultSet.getInt("version_number"),
                resultSet.getString("schema_json"),
                resultSet.getString("configuration_json"),
                resultSet.getString("status"),
                resultSet.getString("change_summary"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("published_at") == null ? null : resultSet.getTimestamp("published_at").toLocalDateTime());
    }
}
