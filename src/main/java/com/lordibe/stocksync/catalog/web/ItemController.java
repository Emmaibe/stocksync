package com.lordibe.stocksync.catalog.web;

import com.lordibe.stocksync.catalog.service.ItemService;
import com.lordibe.stocksync.catalog.web.dto.ItemRequest;
import com.lordibe.stocksync.catalog.web.dto.ItemResponse;
import com.lordibe.stocksync.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService items;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@Valid @RequestBody ItemRequest request) {
        return ItemResponse.from(items.create(request));
    }

    @GetMapping
    public PageResponse<ItemResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.from(items.listActive(pageable), ItemResponse::from);
    }

    /** Barcode lookup — what a scanner in the warehouse actually calls. */
    @GetMapping("/by-barcode")
    public ItemResponse byBarcode(@RequestParam String barcode) {
        return ItemResponse.from(items.findByBarcode(barcode));
    }

    @GetMapping("/{itemId}")
    public ItemResponse get(@PathVariable UUID itemId) {
        return ItemResponse.from(items.get(itemId));
    }

    @PutMapping("/{itemId}")
    public ItemResponse update(@PathVariable UUID itemId, @Valid @RequestBody ItemRequest request) {
        return ItemResponse.from(items.update(itemId, request));
    }

    /** Deactivates rather than deletes; movement history keeps referencing it. */
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID itemId) {
        items.deactivate(itemId);
    }
}
