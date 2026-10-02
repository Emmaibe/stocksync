package com.lordibe.stocksync.stocktake;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.inventory.domain.MovementType;
import com.lordibe.stocksync.inventory.domain.RecordMovementCommand;
import com.lordibe.stocksync.inventory.domain.StockMovementService;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.stocktake.domain.StockCount;
import com.lordibe.stocksync.stocktake.domain.StockCountLine;
import com.lordibe.stocksync.stocktake.domain.StockCountStatus;
import com.lordibe.stocksync.stocktake.service.StockCountService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Counting the shelf and correcting the ledger.
 *
 * <p>Most of these tests guard against ways a stocktake destroys good data
 * rather than fixing bad data: emptying a warehouse because nobody finished
 * counting, undoing a sale that happened mid-count, or adjusting twice on a
 * retry.
 */
class StocktakeIT extends AbstractPostgresIntegrationTest {

    @Autowired StockCountService stocktake;
    @Autowired StockMovementService movements;
    @Autowired StockMovementRepository movementRepository;
    @Autowired ItemRepository items;
    @Autowired LocationRepository locations;
    @Autowired TenantRepository tenants;
    @Autowired AppUserRepository users;

    private UUID tenantId;
    private UUID widgetId;
    private UUID gadgetId;
    private UUID warehouseId;
    /** The person doing the counting. Every posted count names one. */
    private UUID counterId;

    @BeforeEach
    void seed() {
        tenantId = tenants.save(Tenant.builder()
                .slug("acme-" + UUID.randomUUID()).name("Acme")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);

        counterId = users.save(AppUser.builder()
                .tenantId(tenantId).email("ann-" + UUID.randomUUID() + "@acme.test")
                .passwordHash("not-a-real-hash").fullName("Ann Counter")
                .role(UserRole.OWNER).status(UserStatus.ACTIVE).build()).getId();

        widgetId = newItem("Widget");
        gadgetId = newItem("Gadget");
        warehouseId = locations.save(Location.builder()
                .tenantId(tenantId).code("WH-" + UUID.randomUUID()).name("Warehouse")
                .type(LocationType.WAREHOUSE).build()).getId();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("opening a count produces a sheet of what the system believes")
    void sheetCarriesExpectedQuantities() {
        receive(widgetId, "100");
        receive(gadgetId, "5");

        StockCount count = stocktake.open(warehouseId, "CYCLE-1", null, null);

        assertThat(lines(count)).hasSize(2);
        assertThat(line(count, widgetId).getExpectedQuantity()).isEqualByComparingTo("100");
        assertThat(line(count, widgetId).getCountedQuantity()).isNull();
    }

    @Test
    @DisplayName("counting fewer than expected writes off the difference")
    void shortageIsAdjustedDown() {
        receive(widgetId, "100");
        StockCount count = stocktake.open(warehouseId, "CYCLE-1", null, null);

        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("97"), "three broken");
        stocktake.post(count.getId(), counterId);

        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("97");
        assertThat(line(count, widgetId).getMovementId()).isNotNull();
        // The correction is an ordinary ledger entry, not a silent overwrite, so
        // the history still explains how the figure got there.
        assertThat(movementRepository.findById(line(count, widgetId).getMovementId()).orElseThrow()
                .getMovementType()).isEqualTo(MovementType.ADJUSTMENT);
    }

    @Test
    @DisplayName("counting more than expected adds the difference")
    void surplusIsAdjustedUp() {
        receive(widgetId, "10");
        StockCount count = stocktake.open(warehouseId, null, null, null);

        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("12"), "found behind the rack");
        stocktake.post(count.getId(), counterId);

        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("an uncounted line is left alone, not treated as zero")
    void uncountedLinesAreSkipped() {
        receive(widgetId, "100");
        receive(gadgetId, "5");
        StockCount count = stocktake.open(warehouseId, null, null, null);

        // Only one of the two was counted. If a missing count were read as zero,
        // posting a half-finished sheet would empty the warehouse — the single
        // most destructive thing this feature could do.
        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("98"), null);
        stocktake.post(count.getId(), counterId);

        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("98");
        assertThat(movements.onHand(gadgetId, warehouseId)).isEqualByComparingTo("5");
        assertThat(line(count, gadgetId).getMovementId()).isNull();
    }

    @Test
    @DisplayName("a counted zero does empty the shelf")
    void countedZeroIsHonoured() {
        receive(widgetId, "4");
        StockCount count = stocktake.open(warehouseId, null, null, null);

        stocktake.enterCount(count.getId(), widgetId, BigDecimal.ZERO, "none left");
        stocktake.post(count.getId(), counterId);

        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a sale during the count is not undone by posting")
    void stockThatMovedMidCountIsNotReversed() {
        receive(widgetId, "100");
        StockCount count = stocktake.open(warehouseId, null, null, null);
        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("100"), null);

        // Someone sells 10 while the counting is still under way. The sheet now
        // says 100 expected and 100 counted, which looks like perfect agreement.
        movements.record(new RecordMovementCommand(widgetId, warehouseId, MovementType.SALE,
                new BigDecimal("-10"), null, null, null, OffsetDateTime.now(), "sale-mid", null));

        stocktake.post(count.getId(), counterId);

        // Measuring against the sheet would have adjusted by zero and left 90,
        // or worse, restored the 10. Measuring against stock as it is now sets
        // the shelf to what was actually counted.
        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("100");
        // And the staleness is visible rather than hidden: expected said 100,
        // the system actually held 90 at the moment of posting.
        assertThat(line(count, widgetId).getSystemQuantity()).isEqualByComparingTo("90");
        assertThat(line(count, widgetId).getExpectedQuantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("agreement produces no ledger entry at all")
    void noVarianceWritesNothing() {
        receive(widgetId, "42");
        StockCount count = stocktake.open(warehouseId, null, null, null);
        long before = movementRepository.count();

        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("42"), null);
        stocktake.post(count.getId(), counterId);

        assertThat(movementRepository.count()).isEqualTo(before);
        assertThat(line(count, widgetId).getMovementId()).isNull();
    }

    @Test
    @DisplayName("a posted count cannot be changed or posted again")
    void postingIsFinal() {
        receive(widgetId, "10");
        StockCount count = stocktake.open(warehouseId, null, null, null);
        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("9"), null);
        stocktake.post(count.getId(), counterId);

        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                stocktake.post(count.getId(), counterId));
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                stocktake.enterCount(count.getId(), widgetId, new BigDecimal("8"), null));
        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("only one count may be open at a location")
    void oneOpenCountPerLocation() {
        receive(widgetId, "10");
        stocktake.open(warehouseId, "FIRST", null, null);

        // Two open counts would each measure a shelf the other is changing, and
        // whichever posted second would overwrite the first with older figures.
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                stocktake.open(warehouseId, "SECOND", null, null));
    }

    @Test
    @DisplayName("cancelling writes nothing to stock")
    void cancellingLeavesStockAlone() {
        receive(widgetId, "10");
        StockCount count = stocktake.open(warehouseId, null, null, null);
        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("3"), null);

        stocktake.cancel(count.getId());

        assertThat(stocktake.get(count.getId()).getStatus()).isEqualTo(StockCountStatus.CANCELLED);
        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("something found that the sheet never listed can be added")
    void itemsMissingFromTheSheetCanBeAdded() {
        receive(widgetId, "10");
        StockCount count = stocktake.open(warehouseId, null, null, null);
        assertThat(lines(count)).hasSize(1);

        stocktake.addLine(count.getId(), gadgetId);
        stocktake.enterCount(count.getId(), gadgetId, new BigDecimal("6"), "no record of these");
        stocktake.post(count.getId(), counterId);

        assertThat(movements.onHand(gadgetId, warehouseId)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("staff may count but not post; a manager may post")
    void countingAndApprovingAreDifferentJobs() {
        receive(widgetId, "10");
        StockCount count = stocktake.open(warehouseId, null, null, null);

        TestAuthentication.as(UserRole.STAFF, tenantId);
        stocktake.enterCount(count.getId(), widgetId, new BigDecimal("8"), null);
        // The whole point of the separation: whoever counted does not get to
        // approve the correction their own count produced.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                stocktake.post(count.getId(), counterId));

        TestAuthentication.as(UserRole.MANAGER, tenantId);
        stocktake.post(count.getId(), counterId);
        assertThat(movements.onHand(widgetId, warehouseId)).isEqualByComparingTo("8");
    }

    private void receive(UUID itemId, String quantity) {
        movements.record(new RecordMovementCommand(itemId, warehouseId, MovementType.RECEIPT,
                new BigDecimal(quantity), null, null, null, OffsetDateTime.now(),
                "seed-" + itemId + "-" + quantity, null));
    }

    private UUID newItem(String name) {
        return items.save(Item.builder()
                .tenantId(tenantId).sku("SKU-" + UUID.randomUUID()).name(name).build()).getId();
    }

    private List<StockCountLine> lines(StockCount count) {
        return stocktake.linesOf(count.getId());
    }

    private StockCountLine line(StockCount count, UUID itemId) {
        return lines(count).stream()
                .filter(line -> line.getItemId().equals(itemId))
                .findFirst()
                .orElseThrow();
    }
}
