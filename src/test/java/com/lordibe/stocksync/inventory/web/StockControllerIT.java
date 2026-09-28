package com.lordibe.stocksync.inventory.web;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.service.AuthenticationService;
import com.lordibe.stocksync.iam.web.dto.LoginRequest;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP surface, end to end over a real socket.
 *
 * <p>Driven with the JDK's own {@link HttpClient} rather than MockMvc, so the
 * whole chain runs as it will in production: the bearer filter, the tenant filter
 * binding {@code tenant_id} from the token, the idempotency filter, method
 * security, and the problem handlers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stocksync.security.jwt.secret=test-only-signing-key-at-least-32-bytes-long")
class StockControllerIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired Environment environment;
    @Autowired AuthenticationService authentication;
    @Autowired TenantRepository tenants;
    @Autowired AppUserRepository users;
    @Autowired ItemRepository items;
    @Autowired LocationRepository locations;
    @Autowired StockMovementRepository movements;
    @Autowired PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID tenantId;
    private UUID itemId;
    private UUID locationId;
    private String managerToken;
    private String staffToken;
    private String slug;

    @BeforeEach
    void seed() {
        slug = "acme-" + UUID.randomUUID();
        tenantId = tenants.save(Tenant.builder()
                .slug(slug).name("Acme").status(TenantStatus.ACTIVE).plan(TenantPlan.FREE)
                .build()).getId();
        users.save(user("manager@acme.io", UserRole.MANAGER));
        users.save(user("staff@acme.io", UserRole.STAFF));
        itemId = items.save(Item.builder()
                .tenantId(tenantId).sku("SKU-" + UUID.randomUUID()).name("Widget").build()).getId();
        locationId = locations.save(Location.builder()
                .tenantId(tenantId).code("WH-" + UUID.randomUUID()).name("Warehouse")
                .type(LocationType.WAREHOUSE).build()).getId();

        managerToken = authentication.login(
                new LoginRequest(slug, "manager@acme.io", PASSWORD)).accessToken();
        staffToken = authentication.login(
                new LoginRequest(slug, "staff@acme.io", PASSWORD)).accessToken();
    }

    @Test
    @DisplayName("without a token the API answers 401 with a problem body, not an empty response")
    void unauthenticatedRequestsGetAProblemBody() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/stock/levels?itemId=" + itemId)
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body()).contains("\"status\":401").contains("detail");
    }

    @Test
    @DisplayName("a token without stock.transfer is refused with 403")
    void missingPermissionIsForbidden() throws Exception {
        String body = """
                {"itemId":"%s","sourceLocationId":"%s","destinationLocationId":"%s","quantity":1}
                """.formatted(itemId, locationId, UUID.randomUUID());

        HttpResponse<String> response = send(request("/api/v1/stock/transfers")
                .header("Authorization", "Bearer " + staffToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("permission");
    }

    @Test
    @DisplayName("a receipt is recorded and the tenant comes from the token")
    void recordsAMovement() throws Exception {
        HttpResponse<String> response = send(movementRequest("25", "key-1"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"movementType\":\"RECEIPT\"");
        assertThat(movements.sumQuantity(tenantId, itemId, locationId)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("replaying an Idempotency-Key returns the first response and records nothing new")
    void replayReturnsTheStoredResponse() throws Exception {
        HttpResponse<String> first = send(movementRequest("25", "mobile-42"));
        HttpResponse<String> replay = send(movementRequest("25", "mobile-42"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        // Byte-for-byte the original response, including the movement id.
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(movements.sumQuantity(tenantId, itemId, locationId)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("reusing a key with a different body is refused rather than guessed at")
    void reusingAKeyWithADifferentBodyIsRejected() throws Exception {
        send(movementRequest("25", "mobile-99"));

        HttpResponse<String> mismatch = send(movementRequest("30", "mobile-99"));

        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(movements.sumQuantity(tenantId, itemId, locationId)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("a failed request releases its key so the client can retry")
    void failureDoesNotBurnTheKey() throws Exception {
        // Oversell: refused with 409, so nothing is stored against the key.
        HttpResponse<String> refused = send(movementRequest("-5", "retry-me"));
        assertThat(refused.statusCode()).isEqualTo(409);

        HttpResponse<String> corrected = send(movementRequest("5", "retry-me"));
        assertThat(corrected.statusCode()).isEqualTo(201);
        assertThat(movements.sumQuantity(tenantId, itemId, locationId)).isEqualByComparingTo("5");
    }

    // --- helpers -----------------------------------------------------------

    private HttpRequest movementRequest(String quantity, String idempotencyKey) {
        String type = quantity.startsWith("-") ? "SALE" : "RECEIPT";
        String body = """
                {"itemId":"%s","locationId":"%s","movementType":"%s","quantity":%s}
                """.formatted(itemId, locationId, type, quantity);
        return request("/api/v1/stock/movements")
                .header("Authorization", "Bearer " + managerToken)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Read from the environment rather than @LocalServerPort, whose package moved in Boot 4. */
    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    private AppUser user(String email, UserRole role) {
        return AppUser.builder()
                .tenantId(tenantId)
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Test User")
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
