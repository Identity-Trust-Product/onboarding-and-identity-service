package com.identityos.onboarding_and_identity_service.client.trust;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TrustSignalClient {
    private final RestClient restClient;

    public TrustSignalClient(
            RestClient.Builder builder,
            @Value("${services.trust.url:http://localhost:8087}") String trustUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(750));
        requestFactory.setReadTimeout(Duration.ofSeconds(1));

        this.restClient = builder
                .baseUrl(trustUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public void record(
            String subjectId,
            String organizationId,
            String applicationId,
            String signalType,
            String outcome,
            Map<String, Object> metadata) {
        try {
            restClient
                    .post()
                    .uri("/api/v1/trust/signals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "subjectType", "USER",
                            "subjectId", subjectId,
                            "organizationId", organizationId == null ? "" : organizationId,
                            "applicationId", applicationId == null ? "" : applicationId,
                            "signalType", signalType,
                            "outcome", outcome == null ? "" : outcome,
                            "source", "onboarding-and-identity-service",
                            "metadata", metadata == null ? Map.of() : metadata,
                            "occurredAt", OffsetDateTime.now().toString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            System.err.println("Trust signal was not recorded: " + exception.getMessage());
        }
    }
}
