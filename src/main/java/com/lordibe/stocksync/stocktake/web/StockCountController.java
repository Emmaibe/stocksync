package com.lordibe.stocksync.stocktake.web;

import com.lordibe.stocksync.shared.web.PageResponse;
import com.lordibe.stocksync.stocktake.service.StockCountService;
import com.lordibe.stocksync.stocktake.web.dto.AddCountLineRequest;
import com.lordibe.stocksync.stocktake.web.dto.EnterCountRequest;
import com.lordibe.stocksync.stocktake.web.dto.OpenStockCountRequest;
import com.lordibe.stocksync.stocktake.web.dto.StockCountLineResponse;
import com.lordibe.stocksync.stocktake.web.dto.StockCountResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Stocktake: count a location, then correct the ledger to match. */
@RestController
@RequestMapping("/api/v1/stock-counts")
@RequiredArgsConstructor
public class StockCountController {

    private final StockCountService counts;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockCountResponse open(@Valid @RequestBody OpenStockCountRequest request,
                                   @AuthenticationPrincipal Jwt caller) {
        return StockCountResponse.from(counts.open(
                request.locationId(), request.reference(), request.note(), callerId(caller)));
    }

    @GetMapping
    public PageResponse<StockCountResponse> list(@PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.from(counts.list(pageable), StockCountResponse::from);
    }

    @GetMapping("/{countId}")
    public StockCountResponse get(@PathVariable UUID countId) {
        return StockCountResponse.from(counts.get(countId));
    }

    /** The sheet: every item to be counted, with what the system expects. */
    @GetMapping("/{countId}/lines")
    public List<StockCountLineResponse> lines(@PathVariable UUID countId) {
        return counts.linesOf(countId).stream().map(StockCountLineResponse::from).toList();
    }

    /** Adds something found on the shelf that the sheet did not list. */
    @PostMapping("/{countId}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public StockCountLineResponse addLine(@PathVariable UUID countId,
                                          @Valid @RequestBody AddCountLineRequest request) {
        return StockCountLineResponse.from(counts.addLine(countId, request.itemId()));
    }

    /** Records what was found. Idempotent by nature: it replaces the figure. */
    @PutMapping("/{countId}/lines/{itemId}")
    public StockCountLineResponse enterCount(@PathVariable UUID countId,
                                             @PathVariable UUID itemId,
                                             @Valid @RequestBody EnterCountRequest request) {
        return StockCountLineResponse.from(
                counts.enterCount(countId, itemId, request.countedQuantity(), request.note()));
    }

    /**
     * Writes the corrections to the ledger.
     *
     * <p>Needs {@code stock.count.post}, which starts at MANAGER, while counting
     * needs only {@code stock.count}, which STAFF has. The person who counted
     * does not approve their own correction.
     */
    @PostMapping("/{countId}/post")
    public StockCountResponse post(@PathVariable UUID countId,
                                   @AuthenticationPrincipal Jwt caller) {
        return StockCountResponse.from(counts.post(countId, callerId(caller)));
    }

    @PostMapping("/{countId}/cancel")
    public StockCountResponse cancel(@PathVariable UUID countId) {
        return StockCountResponse.from(counts.cancel(countId));
    }

    private UUID callerId(Jwt caller) {
        return caller == null ? null : UUID.fromString(caller.getSubject());
    }
}
