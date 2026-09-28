package com.lordibe.stocksync.catalog.service;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.domain.UnitOfMeasure;
import com.lordibe.stocksync.catalog.repository.ItemRepository;
import com.lordibe.stocksync.catalog.web.dto.ItemRequest;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.UUID;

/**
 * The tenant's catalogue.
 *
 * <p>Deleting is a deactivation, never a row removal: the ledger holds movements
 * that reference this item for as long as the tenant keeps their history, and a
 * real delete would either break that reference or take the history with it.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ItemService {

    private final ItemRepository items;

    @PreAuthorize("hasAuthority('item.create')")
    public Item create(ItemRequest request) {
        UUID tenantId = TenantContext.require();
        if (items.existsByTenantIdAndSku(tenantId, request.sku())) {
            throw new ConflictException("An item with SKU '" + request.sku() + "' already exists");
        }
        return items.save(Item.builder()
                .tenantId(tenantId)
                .sku(request.sku())
                .barcode(blankToNull(request.barcode()))
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .unit(request.unit() == null ? UnitOfMeasure.EACH : request.unit())
                .costPrice(request.costPrice())
                .salePrice(request.salePrice())
                .reorderPoint(request.reorderPoint())
                .tracked(request.tracked() == null || request.tracked())
                .attributes(request.attributes() == null
                        ? new HashMap<>() : new HashMap<>(request.attributes()))
                .build());
    }

    @PreAuthorize("hasAuthority('item.update')")
    public Item update(UUID itemId, ItemRequest request) {
        Item item = require(itemId);
        if (!item.getSku().equals(request.sku())
                && items.existsByTenantIdAndSku(item.getTenantId(), request.sku())) {
            throw new ConflictException("An item with SKU '" + request.sku() + "' already exists");
        }
        item.setSku(request.sku());
        item.setBarcode(blankToNull(request.barcode()));
        item.setName(request.name());
        item.setDescription(request.description());
        item.setCategory(request.category());
        item.setCostPrice(request.costPrice());
        item.setSalePrice(request.salePrice());
        item.setReorderPoint(request.reorderPoint());
        if (request.unit() != null) {
            item.setUnit(request.unit());
        }
        if (request.tracked() != null) {
            item.setTracked(request.tracked());
        }
        if (request.attributes() != null) {
            item.setAttributes(new HashMap<>(request.attributes()));
        }
        return item;
    }

    /** Deactivates the item. Its movement history stays intact and readable. */
    @PreAuthorize("hasAuthority('item.delete')")
    public void deactivate(UUID itemId) {
        require(itemId).setActive(false);
    }

    @PreAuthorize("hasAuthority('item.read')")
    @Transactional(readOnly = true)
    public Item get(UUID itemId) {
        return require(itemId);
    }

    @PreAuthorize("hasAuthority('item.read')")
    @Transactional(readOnly = true)
    public Page<Item> listActive(Pageable pageable) {
        return items.findByTenantIdAndActiveTrue(TenantContext.require(), pageable);
    }

    @PreAuthorize("hasAuthority('item.read')")
    @Transactional(readOnly = true)
    public Item findByBarcode(String barcode) {
        return items.findByTenantIdAndBarcode(TenantContext.require(), barcode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No item with barcode " + barcode));
    }

    private Item require(UUID itemId) {
        return items.findByTenantIdAndId(TenantContext.require(), itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item", itemId));
    }

    /** An empty barcode is no barcode: "" would collide on the partial unique index. */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
