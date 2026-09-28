package com.lordibe.stocksync.catalog.repository;

import com.lordibe.stocksync.catalog.domain.Item;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant ids are passed explicitly even though the tenant filter also applies
 * them: the filter is the net, these parameters are the intent.
 */
public interface ItemRepository extends JpaRepository<Item, UUID> {

    Optional<Item> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Item> findByTenantIdAndSku(UUID tenantId, String sku);

    Optional<Item> findByTenantIdAndBarcode(UUID tenantId, String barcode);

    boolean existsByTenantIdAndSku(UUID tenantId, String sku);

    Page<Item> findByTenantIdAndActiveTrue(UUID tenantId, Pageable pageable);
}
