package com.lordibe.stocksync.location.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * A place stock can sit. Every movement names one; stock is always counted per
 * item per location.
 */
@Entity
@Table(name = "location")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class Location extends AuditedTenantEntity {

    /** Short human-facing code, unique per tenant (e.g. "LGS-01"). */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private LocationType type;

    /**
     * Free-form address. Kept as JSONB because address shape varies by country
     * and none of it is queried structurally.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address", nullable = false)
    @Builder.Default
    private Map<String, Object> address = new HashMap<>();

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
