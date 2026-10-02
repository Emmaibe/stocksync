package com.lordibe.stocksync.stocktake.service;

import com.lordibe.stocksync.inventory.domain.MovementType;
import com.lordibe.stocksync.inventory.domain.RecordMovementCommand;
import com.lordibe.stocksync.inventory.domain.StockMovement;
import com.lordibe.stocksync.inventory.domain.StockMovementService;
import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import com.lordibe.stocksync.stocktake.domain.StockCount;
import com.lordibe.stocksync.stocktake.domain.StockCountLine;
import com.lordibe.stocksync.stocktake.domain.StockCountStatus;
import com.lordibe.stocksync.stocktake.repository.StockCountLineRepository;
import com.lordibe.stocksync.stocktake.repository.StockCountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Counting what is really on the shelf, and correcting the ledger to match.
 *
 * <p>Inventory data drifts. Things get broken, miscounted, taken without being
 * recorded. Without a way to count and correct, the drift is permanent and the
 * numbers slowly stop meaning anything. This is the only part of the system
 * whose job is to disagree with the ledger.
 *
 * <p><b>It never writes stock itself.</b> Posting appends ordinary ADJUSTMENT
 * movements through {@link StockMovementService}, so every change still has one
 * explanation in one place. Updating {@code stock_level} directly would be
 * fewer lines and would leave a jump in the numbers that no report could later
 * account for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCountService {

    private final StockCountRepository counts;
    private final StockCountLineRepository lines;
    private final StockLevelRepository levels;
    private final LocationRepository locations;
    private final StockMovementService movements;

    /**
     * Opens a count and produces the sheet: one line per item that currently has
     * a record at this location, including the ones at zero.
     *
     * <p>Zero matters. An item the system thinks is empty is exactly the one
     * worth checking, because "none left" and "nobody recorded the delivery"
     * look identical in a report and completely different on the shelf.
     */
    @PreAuthorize("hasAuthority('stock.count')")
    @Transactional
    public StockCount open(UUID locationId, String reference, String note, UUID openedBy) {
        UUID tenantId = TenantContext.require();

        locations.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));

        counts.findByLocationIdAndStatus(locationId, StockCountStatus.OPEN).ifPresent(open -> {
            // Also enforced by a partial unique index. Two open counts would each
            // measure a shelf the other is changing, and the second to post would
            // overwrite the first with older figures.
            throw new ConflictException(
                    "There is already a count open for this location. Finish or cancel it first.");
        });

        StockCount count = counts.save(StockCount.builder()
                .tenantId(tenantId)
                .locationId(locationId)
                .status(StockCountStatus.OPEN)
                .reference(reference)
                .note(note)
                .openedBy(openedBy)
                .build());

        List<StockCountLine> sheet = levels.findByTenantIdAndLocationId(tenantId, locationId)
                .stream()
                .map(level -> StockCountLine.builder()
                        .tenantId(tenantId)
                        .stockCountId(count.getId())
                        .itemId(level.getItemId())
                        .expectedQuantity(level.getQuantityOnHand())
                        .build())
                .toList();
        lines.saveAll(sheet);

        log.info("Opened stock count {} at location {} with {} lines",
                count.getId(), locationId, sheet.size());
        return count;
    }

    /**
     * Adds an item the sheet did not list — something found on the shelf that the
     * system has no record of at this location.
     */
    @PreAuthorize("hasAuthority('stock.count')")
    @Transactional
    public StockCountLine addLine(UUID countId, UUID itemId) {
        UUID tenantId = TenantContext.require();
        StockCount count = requireOpen(countId);

        if (lines.existsByStockCountIdAndItemId(countId, itemId)) {
            throw new ConflictException("That item is already on this count sheet.");
        }

        BigDecimal expected = levels
                .findByTenantIdAndItemIdAndLocationId(tenantId, itemId, count.getLocationId())
                .map(level -> level.getQuantityOnHand())
                .orElse(BigDecimal.ZERO);

        return lines.save(StockCountLine.builder()
                .tenantId(tenantId)
                .stockCountId(countId)
                .itemId(itemId)
                .expectedQuantity(expected)
                .build());
    }

    /** Records what was found. Counting again before posting simply replaces it. */
    @PreAuthorize("hasAuthority('stock.count')")
    @Transactional
    public StockCountLine enterCount(UUID countId, UUID itemId, BigDecimal counted, String note) {
        requireOpen(countId);

        if (counted == null || counted.signum() < 0) {
            throw new ConflictException("A counted quantity cannot be negative.");
        }

        StockCountLine line = lines.findByStockCountIdAndItemId(countId, itemId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That item is not on this count sheet."));
        line.setCountedQuantity(counted);
        line.setNote(note);
        return line;
    }

    /**
     * Turns the sheet into stock corrections.
     *
     * <p>Three decisions worth stating, because each one is a way this could
     * quietly destroy data:
     *
     * <ul>
     *   <li><b>Uncounted lines are skipped.</b> A null count means nobody looked,
     *       not that the shelf is empty. Treating the two alike is how a partial
     *       count empties a warehouse.</li>
     *   <li><b>The correction is measured against stock as it is now</b>, not
     *       against the figure printed on the sheet. Stock may have moved
     *       legitimately while counting was under way, and adjusting by the stale
     *       difference would undo those movements. Both numbers are stored, so a
     *       sheet that went stale is visible rather than hidden.</li>
     *   <li><b>Each line's movement carries an idempotency key derived from the
     *       line.</b> If posting fails halfway and is retried, the lines already
     *       adjusted return their original movement instead of being adjusted
     *       twice.</li>
     * </ul>
     */
    @PreAuthorize("hasAuthority('stock.count.post')")
    @Transactional
    public StockCount post(UUID countId, UUID postedBy) {
        UUID tenantId = TenantContext.require();
        StockCount count = requireOpen(countId);

        int corrected = 0;
        for (StockCountLine line : lines.findByStockCountIdOrderByCreatedAtAsc(countId)) {
            if (!line.isCounted()) {
                continue;
            }

            BigDecimal onHand = levels
                    .findByTenantIdAndItemIdAndLocationId(tenantId, line.getItemId(), count.getLocationId())
                    .map(level -> level.getQuantityOnHand())
                    .orElse(BigDecimal.ZERO);
            line.setSystemQuantity(onHand);

            BigDecimal delta = line.getCountedQuantity().subtract(onHand);
            if (delta.signum() == 0) {
                // Agreement is the common case and deserves no ledger entry. A
                // movement of zero would also be refused by the sign constraint.
                continue;
            }

            StockMovement movement = movements.record(new RecordMovementCommand(
                    line.getItemId(),
                    count.getLocationId(),
                    MovementType.ADJUSTMENT,
                    delta,
                    null,
                    "Stocktake" + (count.getReference() == null ? "" : " " + count.getReference()),
                    count.getReference(),
                    null,
                    "stocktake:" + line.getId(),
                    postedBy));
            line.setMovementId(movement.getId());
            corrected++;
        }

        count.post(postedBy, OffsetDateTime.now());
        log.info("Posted stock count {}: {} corrections", countId, corrected);
        return count;
    }

    /** Abandons a count. Nothing is written to stock, and the sheet is kept. */
    @PreAuthorize("hasAuthority('stock.count')")
    @Transactional
    public StockCount cancel(UUID countId) {
        StockCount count = requireOpen(countId);
        count.cancel();
        return count;
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public Page<StockCount> list(Pageable pageable) {
        return counts.findAllByOrderByCreatedAtDesc(pageable);
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public StockCount get(UUID countId) {
        return counts.findById(countId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock count", countId));
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public List<StockCountLine> linesOf(UUID countId) {
        get(countId);
        return lines.findByStockCountIdOrderByCreatedAtAsc(countId);
    }

    private StockCount requireOpen(UUID countId) {
        StockCount count = counts.findById(countId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock count", countId));
        if (!count.isOpen()) {
            throw new ConflictException(
                    "This count has already been " + count.getStatus().name().toLowerCase()
                            + ", so it cannot be changed.");
        }
        return count;
    }
}
