package com.lordibe.stocksync.catalog.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * A stock-keeping unit in a tenant's catalogue.
 *
 * <p>Holds only definition, never quantity: on-hand figures live in the stock
 * ledger and its projection.
 */
@Entity
@Table(name = "item")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class Item extends AuditedTenantEntity {

    /** Tenant's own identifier for the item. Unique per tenant. */
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    /** Optional scannable code. Unique per tenant when present. */
    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    /** Free-text grouping for now; promoted to its own entity when tenants need trees. */
    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    @Builder.Default
    private UnitOfMeasure unit = UnitOfMeasure.EACH;

    @Column(name = "cost_price", precision = 19, scale = 4)
    private BigDecimal costPrice;

    @Column(name = "sale_price", precision = 19, scale = 4)
    private BigDecimal salePrice;

    /** On-hand level at or below which the item counts as needing reorder. */
    @Column(name = "reorder_point", precision = 19, scale = 4)
    private BigDecimal reorderPoint;

    /** False for services and consumables that are sold but not counted. */
    @Column(name = "tracked", nullable = false)
    @Builder.Default
    private boolean tracked = true;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Tenant-defined custom fields, so extending the item record needs no
     * migration. Indexed with GIN for containment queries.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false)
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
