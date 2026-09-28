package com.lordibe.stocksync.inventory.web;

import com.lordibe.stocksync.inventory.domain.StockMovement;
import com.lordibe.stocksync.inventory.domain.StockMovementService;
import com.lordibe.stocksync.inventory.domain.StockQueryService;
import com.lordibe.stocksync.inventory.web.dto.RecordMovementRequest;
import com.lordibe.stocksync.inventory.web.dto.ReservationRequest;
import com.lordibe.stocksync.inventory.web.dto.StockLevelResponse;
import com.lordibe.stocksync.inventory.web.dto.StockMovementResponse;
import com.lordibe.stocksync.inventory.web.dto.TransferStockRequest;
import com.lordibe.stocksync.platform.idempotency.web.IdempotencyFilter;
import com.lordibe.stocksync.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Stock movements and levels.
 *
 * <p>Thin on purpose: it maps HTTP to commands and back, and every rule that
 * matters — authorisation, tenancy, the ledger invariants — lives in the services
 * below it, where a non-HTTP caller is covered by the same code.
 *
 * <p>Writes accept an optional {@code Idempotency-Key}. It is handled twice over:
 * {@link IdempotencyFilter} replays the original response for a repeated key, and
 * the same key reaches the ledger's own unique constraint as a second line of
 * defence against double-counting.
 */
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockMovementService movements;
    private final StockQueryService queries;

    /** Records a receipt, sale or adjustment. */
    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public StockMovementResponse record(
            @Valid @RequestBody RecordMovementRequest request,
            @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt caller) {
        StockMovement movement = movements.record(
                request.toCommand(idempotencyKey, callerId(caller)));
        return StockMovementResponse.from(movement);
    }

    /** Moves stock between two locations; returns both legs, outbound first. */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public List<StockMovementResponse> transfer(
            @Valid @RequestBody TransferStockRequest request,
            @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt caller) {
        return movements.transfer(request.toCommand(idempotencyKey, callerId(caller)))
                .stream()
                .map(StockMovementResponse::from)
                .toList();
    }

    /**
     * Current levels for an item, optionally narrowed to one location.
     *
     * <p>A missing slot is reported as zero rather than 404: "no movements yet"
     * and "no stock" are the same answer to a client asking what is on hand.
     */
    @GetMapping("/levels")
    public List<StockLevelResponse> levels(@RequestParam UUID itemId,
                                           @RequestParam(required = false) UUID locationId) {
        if (locationId == null) {
            return queries.levelsForItem(itemId).stream()
                    .map(StockLevelResponse::from)
                    .toList();
        }
        return queries.levelAt(itemId, locationId)
                .map(level -> List.of(StockLevelResponse.from(level)))
                .orElseGet(() -> List.of(new StockLevelResponse(itemId, locationId,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, null)));
    }

    /** Items at or below their reorder point. */
    @GetMapping("/levels/below-reorder-point")
    public List<StockLevelResponse> belowReorderPoint() {
        return queries.belowReorderPoint().stream()
                .map(StockLevelResponse::from)
                .toList();
    }

    /** An item's ledger history, newest first. */
    @GetMapping("/items/{itemId}/movements")
    public PageResponse<StockMovementResponse> history(
            @PathVariable UUID itemId,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.from(queries.historyFor(itemId, pageable), StockMovementResponse::from);
    }

    /**
     * Commits stock to an order without moving it: on-hand is unchanged, available
     * falls. The eventual sale releases the reservation as it removes the stock.
     */
    @PostMapping("/reservations")
    public StockLevelResponse reserve(@Valid @RequestBody ReservationRequest request) {
        return StockLevelResponse.from(movements.reserve(
                request.itemId(), request.locationId(), request.quantity()));
    }

    /** Releases a reservation that will not be fulfilled. */
    @PostMapping("/reservations/release")
    public StockLevelResponse releaseReservation(@Valid @RequestBody ReservationRequest request) {
        return StockLevelResponse.from(movements.releaseReservation(
                request.itemId(), request.locationId(), request.quantity()));
    }

    /** Rebuilds a slot's projection from the ledger. Administrative repair. */
    @PostMapping("/levels/rebuild")
    public StockLevelResponse rebuild(@RequestParam UUID itemId, @RequestParam UUID locationId) {
        return StockLevelResponse.from(movements.rebuildSlot(itemId, locationId));
    }

    private UUID callerId(Jwt caller) {
        return caller == null ? null : UUID.fromString(caller.getSubject());
    }
}
