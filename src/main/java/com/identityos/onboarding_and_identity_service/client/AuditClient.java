package com.identityos.onboarding_and_identity_service.client;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AuditClient {
    private final RestClient restClient;

    public AuditClient(
            RestClient.Builder builder,
            @Value("${services.audit.url:http://localhost:8086}") String auditUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = builder
                .baseUrl(auditUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public void record(Map<String, Object> auditEvent) {
        restClient
                .post()
                .uri("/audits")
                .contentType(MediaType.APPLICATION_JSON)
                .body(auditEvent)
                .retrieve()
                .toBodilessEntity();
    }
}
