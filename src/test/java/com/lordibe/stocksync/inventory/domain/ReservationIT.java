package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
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
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Reservations: stock committed to an order but not yet gone.
 *
 * <p>The distinction under test is that reserving changes what may be
 * <em>promised</em> without changing what is <em>present</em>.
 */
class ReservationIT extends AbstractPostgresIntegrationTest {

    @Autowired StockMovementService movements;
    @Autowired StockQueryService queries;
    @Autowired ItemRepository items;
    @Autowired LocationRepository locations;
    @Autowired TenantRepository tenants;

    private UUID tenantId;
    private UUID itemId;
    private UUID locationId;

    @BeforeEach
    void seed() {
        tenantId = tenants.save(Tenant.builder()
                .slug("acme-" + UUID.randomUUID()).name("Acme")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
        itemId = items.save(Item.builder().tenantId(tenantId)
                .sku("SKU-" + UUID.randomUUID()).name("Widget").build()).getId();
        locationId = locations.save(Location.builder().tenantId(tenantId)
                .code("WH-" + UUID.randomUUID()).name("Warehouse")
                .type(LocationType.WAREHOUSE).build()).getId();
        movements.record(receipt(new BigDecimal("100")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("reserving lowers available but leaves on-hand alone")
    void reservingDoesNotMoveStock() {
        StockLevel level = movements.reserve(itemId, locationId, new BigDecimal("30"));

        assertThat(level.getQuantityOnHand()).isEqualByComparingTo("100");
        assertThat(level.getQuantityReserved()).isEqualByComparingTo("30");
        assertThat(level.availableQuantity()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("more cannot be reserved than is on hand")
    void cannotOverReserve() {
        movements.reserve(itemId, locationId, new BigDecimal("80"));

        assertThatExceptionOfType(InsufficientStockException.class).isThrownBy(() ->
                movements.reserve(itemId, locationId, new BigDecimal("30")));

        assertThat(queries.levelAt(itemId, locationId).orElseThrow().getQuantityReserved())
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("releasing gives the stock back to the available pool")
    void releasingRestoresAvailability() {
        movements.reserve(itemId, locationId, new BigDecimal("40"));

        StockLevel level = movements.releaseReservation(itemId, locationId, new BigDecimal("15"));

        assertThat(level.getQuantityReserved()).isEqualByComparingTo("25");
        assertThat(level.availableQuantity()).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("more cannot be released than is reserved")
    void cannotOverRelease() {
        movements.reserve(itemId, locationId, new BigDecimal("10"));

        assertThatExceptionOfType(InvalidMovementException.class).isThrownBy(() ->
                movements.releaseReservation(itemId, locationId, new BigDecimal("11")));
    }

    @Test
    @DisplayName("a sale consumes the reservation it fulfils rather than leaving it standing")
    void saleReleasesTheReservationItFulfils() {
        movements.reserve(itemId, locationId, new BigDecimal("30"));

        movements.record(new RecordMovementCommand(itemId, locationId, MovementType.SALE,
                new BigDecimal("-30"), null, null, null, OffsetDateTime.now(), null, null));

        StockLevel level = queries.levelAt(itemId, locationId).orElseThrow();
        assertThat(level.getQuantityOnHand()).isEqualByComparingTo("70");
        // Had the reservation survived the sale, this slot would still be
        // withholding 30 units that have already left the building.
        assertThat(level.getQuantityReserved()).isEqualByComparingTo("0");
        assertThat(level.availableQuantity()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("floor staff cannot reserve stock")
    void reservingNeedsPermission() {
        TestAuthentication.as(UserRole.STAFF, tenantId);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                movements.reserve(itemId, locationId, new BigDecimal("1")));
    }

    private RecordMovementCommand receipt(BigDecimal quantity) {
        return new RecordMovementCommand(itemId, locationId, MovementType.RECEIPT, quantity,
                null, null, null, OffsetDateTime.now(), null, null);
    }
}
