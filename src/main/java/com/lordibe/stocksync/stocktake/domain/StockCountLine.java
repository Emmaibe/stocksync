package com.lordibe.stocksync.stocktake.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item on a count sheet.
 *
 * <p>Three quantities, and the distinction between them is the whole point.
 *
 * <ul>
 *   <li>{@code expectedQuantity} — what the system believed when the sheet was
 *       produced. For the report only.</li>
 *   <li>{@code countedQuantity} — what a person found. <b>Null means not
 *       counted</b>, which is not the same as counted zero: an uncounted line is
 *       skipped at posting, while a zero empties the shelf. Treating the two
 *       alike is how a partial count wipes out a warehouse.</li>
 *   <li>{@code systemQuantity} — what the system believed at the moment of
 *       posting. The correction is computed against this, not against expected,
 *       because stock may legitimately have moved while the counting was
 *       happening. Storing both leaves that difference visible.</li>
 * </ul>
 */
@Entity
@Table(name = "stock_count_line")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class StockCountLine extends AuditedTenantEntity {

    @Column(name = "stock_count_id", nullable = false)
    private UUID stockCountId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "expected_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedQuantity;

    @Column(name = "counted_quantity", precision = 19, scale = 4)
    private BigDecimal countedQuantity;

    @Column(name = "system_quantity", precision = 19, scale = 4)
    private BigDecimal systemQuantity;

    @Column(name = "movement_id")
    private UUID movementId;

    @Column(name = "note", length = 255)
    private String note;

    public boolean isCounted() {
        return countedQuantity != null;
    }

    /** What the sheet says is missing or spare, before posting. Null until counted. */
    public BigDecimal variance() {
        return isCounted() ? countedQuantity.subtract(expectedQuantity) : null;
    }
}
