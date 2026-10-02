package com.lordibe.stocksync.stocktake.domain;

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
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One counting session at one location.
 *
 * <p>A count is a document, not a stock operation. Posting it appends ordinary
 * ADJUSTMENT movements through {@code StockMovementService}, so the ledger
 * remains the only account of how stock reached its current figure. Writing
 * {@code stock_level} directly would be quicker and would leave an unexplained
 * jump that no report could account for afterwards.
 */
@Entity
@Table(name = "stock_count")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class StockCount extends AuditedTenantEntity {

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockCountStatus status;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "opened_by")
    private UUID openedBy;

    @Column(name = "posted_by")
    private UUID postedBy;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    public boolean isOpen() {
        return status == StockCountStatus.OPEN;
    }

    public void post(UUID by, OffsetDateTime at) {
        this.status = StockCountStatus.POSTED;
        this.postedBy = by;
        this.postedAt = at;
    }

    public void cancel() {
        this.status = StockCountStatus.CANCELLED;
    }
}
