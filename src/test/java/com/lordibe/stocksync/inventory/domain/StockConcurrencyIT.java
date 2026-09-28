package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.support.TestAuthentication;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Puts the write path under real contention.
 *
 * <p>The single-threaded tests execute the locking code but never make it matter.
 * These do: several threads race on one item/location slot, which exercises both
 * the {@code SELECT … FOR UPDATE} on the slot and the {@code REQUIRES_NEW}
 * provisioning race where every thread finds no slot row and tries to create it.
 *
 * <p>The assertion is the invariant that must never break, however the race
 * resolves: the projection equals the sum of the ledger.
 */
class StockConcurrencyIT extends AbstractPostgresIntegrationTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 5;
    private static final BigDecimal EACH = new BigDecimal("10");

    @Autowired StockMovementService movementService;
    @Autowired StockMovementRepository movements;
    @Autowired StockLevelRepository stockLevels;
    @Autowired ItemRepository items;
    @Autowired LocationRepository locations;
    @Autowired TenantRepository tenants;

    private UUID tenantId;
    private UUID itemId;
    private UUID locationId;

    @BeforeEach
    void seed() {
        tenantId = tenants.save(Tenant.builder()
                .slug("race-" + UUID.randomUUID()).name("Race Ltd")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
        itemId = items.save(Item.builder()
                .tenantId(tenantId).sku("SKU-" + UUID.randomUUID()).name("Contended").build()).getId();
        locationId = locations.save(Location.builder()
                .tenantId(tenantId).code("WH-" + UUID.randomUUID()).name("Warehouse")
                .type(LocationType.WAREHOUSE).build()).getId();
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("concurrent receipts on one slot all land, and the projection still matches the ledger")
    void concurrentReceiptsAreNotLost() throws Exception {
        // No slot row exists yet, so every thread also races to provision it.
        assertThat(stockLevels.findByTenantIdAndItemIdAndLocationId(tenantId, itemId, locationId))
                .isEmpty();

        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int thread = 0; thread < THREADS; thread++) {
            int threadIndex = thread;
            tasks.add(() -> {
                // TenantContext and the security context are both per thread, so
                // each worker establishes its own — as a request thread would.
                TenantContext.set(tenantId);
                TestAuthentication.as(UserRole.OWNER, tenantId);
                try {
                    startTogether.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < PER_THREAD; i++) {
                        try {
                            movementService.record(new RecordMovementCommand(
                                    itemId, locationId, MovementType.RECEIPT, EACH,
                                    null, null, null, OffsetDateTime.now(),
                                    "t%d-%d".formatted(threadIndex, i), null));
                        } catch (RuntimeException contention) {
                            // Counted rather than swallowed: a lock timeout or a
                            // serialisation failure here is a real finding.
                            failures.incrementAndGet();
                        }
                    }
                } finally {
                    TenantContext.clear();
                    TestAuthentication.clear();
                }
                return null;
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Void>> running = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                running.add(pool.submit(task));
            }
            startTogether.countDown();
            for (Future<Void> future : running) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        BigDecimal expected = EACH.multiply(BigDecimal.valueOf((long) THREADS * PER_THREAD));

        assertThat(failures.get()).isZero();
        assertThat(movements.sumQuantity(tenantId, itemId, locationId)).isEqualByComparingTo(expected);

        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
        // The projection is the claim under test: no lost update despite the race.
        assertThat(movementService.onHand(itemId, locationId)).isEqualByComparingTo(expected);
        assertThat(stockLevels.findByTenantIdAndItemIdAndLocationId(tenantId, itemId, locationId))
                .isPresent();
        TenantContext.clear();
        TestAuthentication.clear();
    }
}
