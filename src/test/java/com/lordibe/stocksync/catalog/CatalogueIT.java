package com.lordibe.stocksync.catalog;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.service.ItemService;
import com.lordibe.stocksync.catalog.web.dto.ItemRequest;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.location.service.LocationService;
import com.lordibe.stocksync.location.web.dto.LocationRequest;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.support.TestAuthentication;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CatalogueIT extends AbstractPostgresIntegrationTest {

    @Autowired ItemService items;
    @Autowired LocationService locations;
    @Autowired TenantRepository tenants;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = tenants.save(Tenant.builder()
                .slug("acme-" + UUID.randomUUID()).name("Acme")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("an item round-trips, including its custom attributes")
    void itemsRoundTrip() {
        Item created = items.create(new ItemRequest("SKU-1", "5012345678900", "Widget",
                "A widget", "Hardware", null, new BigDecimal("2.50"), new BigDecimal("4.00"),
                new BigDecimal("10"), null, Map.of("colour", "red")));

        Item loaded = items.get(created.getId());
        assertThat(loaded.getName()).isEqualTo("Widget");
        assertThat(loaded.getAttributes()).containsEntry("colour", "red");
        assertThat(items.findByBarcode("5012345678900").getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("a duplicate SKU is a conflict, not a stack trace")
    void duplicateSkuIsRejected() {
        items.create(new ItemRequest("SKU-DUP", null, "First", null, null, null,
                null, null, null, null, null));

        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                items.create(new ItemRequest("SKU-DUP", null, "Second", null, null, null,
                        null, null, null, null, null)));
    }

    @Test
    @DisplayName("blank barcodes become null, so several items may have none")
    void blankBarcodesDoNotCollide() {
        items.create(new ItemRequest("SKU-A", "", "A", null, null, null,
                null, null, null, null, null));
        items.create(new ItemRequest("SKU-B", "  ", "B", null, null, null,
                null, null, null, null, null));

        assertThat(items.listActive(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleting deactivates, so movement history keeps its reference")
    void deleteIsDeactivation() {
        Item item = items.create(new ItemRequest("SKU-GONE", null, "Doomed", null, null, null,
                null, null, null, null, null));

        items.deactivate(item.getId());

        assertThat(items.get(item.getId()).isActive()).isFalse();
        assertThat(items.listActive(PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("another tenant's item is reported missing, not forbidden")
    void otherTenantsItemsAreInvisible() {
        Item mine = items.create(new ItemRequest("SKU-MINE", null, "Mine", null, null, null,
                null, null, null, null, null));

        UUID otherTenant = tenants.save(Tenant.builder()
                .slug("other-" + UUID.randomUUID()).name("Other")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();
        TenantContext.set(otherTenant);
        TestAuthentication.as(UserRole.OWNER, otherTenant);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> items.get(mine.getId()));
    }

    @Test
    @DisplayName("staff may read the catalogue but not change it")
    void catalogueWritesNeedPermission() {
        TestAuthentication.as(UserRole.STAFF, tenantId);

        assertThat(items.listActive(PageRequest.of(0, 10))).isNotNull();
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                items.create(new ItemRequest("SKU-NOPE", null, "Nope", null, null, null,
                        null, null, null, null, null)));
    }

    @Test
    @DisplayName("locations round-trip and their codes are unique per tenant")
    void locationsRoundTrip() {
        var warehouse = locations.create(new LocationRequest("WH-1", "Warehouse", null,
                Map.of("city", "Glasgow")));

        assertThat(locations.get(warehouse.getId()).getAddress()).containsEntry("city", "Glasgow");
        assertThat(locations.listActive()).hasSize(1);
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                locations.create(new LocationRequest("WH-1", "Duplicate", null, null)));
    }
}
