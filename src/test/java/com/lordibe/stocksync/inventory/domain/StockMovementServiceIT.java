package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
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
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves the invariants the architecture rests on: the ledger is the truth, the
 * projection always agrees with it, a replayed write changes nothing, and one
 * tenant cannot see another's rows.
 */
class StockMovementServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired StockMovementService movementService;
    @Autowired StockMovementRepository movements;
    @Autowired StockLevelRepository stockLevels;
    @Autowired ItemRepository items;
    @Autowired LocationRepository locations;
    @Autowired TenantRepository tenants;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ItemProbe itemProbe;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID itemId;
    private UUID warehouseId;
    private UUID storeId;

    @BeforeEach
    void seed() {
        tenantId = newTenant("acme-" + UUID.randomUUID()).getId();
        otherTenantId = newTenant("globex-" + UUID.randomUUID()).getId();

        TenantContext.set(tenantId);
        // The service is role-guarded; act as an owner unless a test says otherwise.
        TestAuthentication.as(UserRole.OWNER, tenantId);
        itemId = items.save(Item.builder()
                .tenantId(tenantId).sku("SKU-" + UUID.randomUUID()).name("Widget").build()).getId();
        warehouseId = locations.save(Location.builder()
                .tenantId(tenantId).code("WH-" + UUID.randomUUID()).name("Warehouse")
                .type(LocationType.WAREHOUSE).build()).getId();
        storeId = locations.save(Location.builder()
                .tenantId(tenantId).code("ST-" + UUID.randomUUID()).name("Store")
                .type(LocationType.STORE).build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("a receipt then a sale leave the projection agreeing with the ledger")
    void receiptThenSale() {
        movementService.record(receipt(new BigDecimal("100"), "k1"));
        movementService.record(sale(new BigDecimal("-12"), "k2"));

        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("88");
        assertThat(movements.sumQuantity(tenantId, itemId, warehouseId)).isEqualByComparingTo("88");
    }

    @Test
    @DisplayName("a sale larger than stock on hand is refused and writes nothing")
    void oversellIsRefused() {
        movementService.record(receipt(new BigDecimal("10"), "k1"));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> movementService.record(sale(new BigDecimal("-11"), "k2")));

        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("10");
        assertThat(movements.sumQuantity(tenantId, itemId, warehouseId)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("a transfer moves stock between locations and nets to zero overall")
    void transferIsQuantityNeutral() {
        movementService.record(receipt(new BigDecimal("100"), "k1"));

        List<StockMovement> legs = movementService.transfer(new TransferStockCommand(
                itemId, warehouseId, storeId, new BigDecimal("30"),
                null, "restock", null, OffsetDateTime.now(), "tr-1", null));

        assertThat(legs).hasSize(2);
        assertThat(legs.getFirst().getMovementType()).isEqualTo(MovementType.TRANSFER_OUT);
        assertThat(legs).allSatisfy(leg -> assertThat(leg.getTransferGroupId()).isNotNull());
        assertThat(legs.stream().map(StockMovement::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");

        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("70");
        assertThat(movementService.onHand(itemId, storeId)).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a transfer of more than is on hand is refused")
    void transferBeyondStockIsRefused() {
        movementService.record(receipt(new BigDecimal("5"), "k1"));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> movementService.transfer(new TransferStockCommand(
                        itemId, warehouseId, storeId, new BigDecimal("6"),
                        null, null, null, OffsetDateTime.now(), "tr-x", null)));
    }

    @Test
    @DisplayName("replaying an idempotency key returns the original and does not double-count")
    void replayIsANoOp() {
        StockMovement first = movementService.record(receipt(new BigDecimal("40"), "mobile-001"));
        StockMovement replay = movementService.record(receipt(new BigDecimal("40"), "mobile-001"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("40");
        assertThat(movements.findByTenantIdAndIdempotencyKey(tenantId, "mobile-001")).isPresent();
    }

    @Test
    @DisplayName("the same idempotency key in another tenant is a different write")
    void idempotencyKeysAreScopedToTheTenant() {
        movementService.record(receipt(new BigDecimal("40"), "shared-key"));

        UUID otherItem;
        UUID otherLocation;
        TenantContext.set(otherTenantId);
        TestAuthentication.as(UserRole.OWNER, otherTenantId);
        otherItem = items.save(Item.builder()
                .tenantId(otherTenantId).sku("SKU-B").name("Other").build()).getId();
        otherLocation = locations.save(Location.builder()
                .tenantId(otherTenantId).code("WH-B").name("Other WH")
                .type(LocationType.WAREHOUSE).build()).getId();

        StockMovement inOtherTenant = movementService.record(new RecordMovementCommand(
                otherItem, otherLocation, MovementType.RECEIPT, new BigDecimal("7"),
                null, null, null, OffsetDateTime.now(), "shared-key", null));

        assertThat(inOtherTenant.getTenantId()).isEqualTo(otherTenantId);
        assertThat(movementService.onHand(otherItem, otherLocation)).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("rebuilding a drifted slot restores it from the ledger")
    void rebuildRepairsDrift() {
        movementService.record(receipt(new BigDecimal("100"), "k1"));
        movementService.record(sale(new BigDecimal("-25"), "k2"));

        // Corrupt the projection behind the service's back, the way a bad manual
        // fix or a bug outside the movement module would.
        // This test lives in the entity's own package, so it can reach the
        // package-private mutator the service normally guards.
        transactionTemplate.executeWithoutResult(status -> stockLevels
                .findByTenantIdAndItemIdAndLocationId(tenantId, itemId, warehouseId)
                .orElseThrow()
                .rebuildTo(new BigDecimal("999"), null));
        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("999");

        StockLevel repaired = movementService.rebuildSlot(itemId, warehouseId);

        assertThat(repaired.getQuantityOnHand()).isEqualByComparingTo("75");
        assertThat(movementService.onHand(itemId, warehouseId)).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("floor staff hold stock.record but not stock.transfer or stock.rebuild")
    void roleRulesAreEnforcedOnTheService() {
        TestAuthentication.as(UserRole.STAFF, tenantId);

        // Recording is floor work, so this is allowed.
        movementService.record(receipt(new BigDecimal("10"), "staff-1"));

        // Transferring and rebuilding are not.
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> movementService.transfer(new TransferStockCommand(
                        itemId, warehouseId, storeId, new BigDecimal("1"),
                        null, null, null, OffsetDateTime.now(), null, null)));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> movementService.rebuildSlot(itemId, warehouseId));
    }

    @Test
    @DisplayName("the tenant filter hides other tenants' rows from an unscoped query")
    void tenantFilterIsolatesQueries() {
        TenantContext.set(otherTenantId);
        items.save(Item.builder().tenantId(otherTenantId).sku("SKU-OTHER").name("Theirs").build());

        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
        List<Item> visible = itemProbe.allItemsUnscoped();

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(item -> assertThat(item.getTenantId()).isEqualTo(tenantId));
    }

    // --- helpers -----------------------------------------------------------

    private Tenant newTenant(String slug) {
        return tenants.save(Tenant.builder()
                .slug(slug).name(slug).status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
    }

    private RecordMovementCommand receipt(BigDecimal quantity, String key) {
        return new RecordMovementCommand(itemId, warehouseId, MovementType.RECEIPT, quantity,
                null, null, null, OffsetDateTime.now(), key, null);
    }

    private RecordMovementCommand sale(BigDecimal quantity, String key) {
        return new RecordMovementCommand(itemId, warehouseId, MovementType.SALE, quantity,
                null, null, null, OffsetDateTime.now(), key, null);
    }

    /**
     * A deliberately un-scoped query, so the filter has something to prove itself
     * against. Repository calls alone would not do: the tenant advisor matches
     * transactional methods on beans in this project's own packages, and Spring
     * Data's repository implementation is not one of them.
     */
    @TestConfiguration
    static class Probes {

        @Bean
        ItemProbe itemProbe(ItemRepository items) {
            return new ItemProbe(items);
        }
    }

    @RequiredArgsConstructor
    public static class ItemProbe {

        private final ItemRepository items;

        @Transactional(readOnly = true)
        public List<Item> allItemsUnscoped() {
            return items.findAll();
        }
    }
}
