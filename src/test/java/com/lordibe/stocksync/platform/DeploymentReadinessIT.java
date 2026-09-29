package com.lordibe.stocksync.platform;

import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The things Kubernetes and a browser rely on, checked here rather than
 * discovered during a rollout.
 *
 * <p>Runs under the {@code prod} profile, so a typo in
 * {@code application-prod.yaml} fails the build instead of crash-looping a pod
 * at 2am — that file is otherwise never exercised until it is in production.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "stocksync.security.jwt.secret=test-only-signing-key-at-least-32-bytes-long",
                "stocksync.web.cors.allowed-origins=https://stocksync.com.ng"
        })
@ActiveProfiles("prod")
class DeploymentReadinessIT extends AbstractPostgresIntegrationTest {

    @Autowired Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the readiness probe answers without a token")
    void readinessProbeIsReachable() throws Exception {
        // If this needed authentication, the pod would never be marked ready and
        // the deployment would roll back every time.
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    @DisplayName("the liveness probe answers without a token")
    void livenessProbeIsReachable() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    @DisplayName("health details are hidden in production")
    void healthDoesNotLeakInfrastructure() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        // A detailed health body names the database host, the mail server and the
        // disk. None of that belongs in an unauthenticated response.
        assertThat(response.body()).doesNotContain("jdbc:").doesNotContain("postgresql");
    }

    @Test
    @DisplayName("a configured origin is allowed through CORS preflight")
    void corsPreflightAllowsTheConfiguredOrigin() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest
                        .newBuilder(URI.create(baseUrl() + "/api/v1/stock/movements"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://stocksync.com.ng")
                        .header("Access-Control-Request-Method", "POST")
                        // The header the mobile and web clients need for retry safety;
                        // a preflight that omits it silently disables idempotency.
                        .header("Access-Control-Request-Headers", "authorization,idempotency-key")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("https://stocksync.com.ng");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .toLowerCase()).contains("idempotency-key");
    }

    @Test
    @DisplayName("an origin that is not configured is refused")
    void corsRefusesUnknownOrigins() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest
                        .newBuilder(URI.create(baseUrl() + "/api/v1/stock/movements"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://not-our-app.example")
                        .header("Access-Control-Request-Method", "POST")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the ops health group needs a token")
    void opsGroupIsNotPublic() throws Exception {
        // The probes are public because kubelet cannot present a token. This group
        // is not a probe: its body names the database and the mail server, and it
        // is the one actuator path most likely to be forgotten when adding checks.
        HttpResponse<String> response = get("/actuator/health/ops");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("metrics need a token")
    void metricsAreNotPublic() throws Exception {
        // Metric names alone describe the system's internals, and the values
        // describe its traffic. Neither is public information.
        HttpResponse<String> response = get("/actuator/metrics");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("readiness ignores the operational checks")
    void readinessDoesNotDependOnMailOrTheOutbox() throws Exception {
        // The guarantee this test exists to hold: a mail outage must not make a
        // pod unready. If the outbox check ever leaks into the readiness group,
        // a wrong SMTP password becomes a full outage at the next rollout.
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("outbox").doesNotContain("mail");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }
}
