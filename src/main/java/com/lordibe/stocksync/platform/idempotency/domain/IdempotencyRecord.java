package com.lordibe.stocksync.platform.idempotency.domain;

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

/**
 * Replay cache for unsafe HTTP requests carrying an {@code Idempotency-Key}.
 *
 * <p>Mobile clients on poor connections retry writes they never saw a response
 * to. A repeat with the same key and the same body gets the stored response; a
 * repeat with the same key and a <em>different</em> body is a client bug and is
 * rejected, which is what {@code requestHash} is for.
 *
 * <p>Distinct from {@code stock_movement.idempotency_key}: this is the
 * transport-level cache, that is the ledger's own uniqueness guarantee.
 */
@Entity
@Table(name = "idempotency_record")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class IdempotencyRecord extends AuditedTenantEntity {

    /** Endpoint family the key belongs to, so keys cannot collide across routes. */
    @Column(name = "scope", nullable = false, length = 60, updatable = false)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the canonical request body. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    /**
     * The original response body, replayed verbatim.
     *
     * <p>Plain text rather than JSONB: jsonb re-renders what it stores, so key
     * order and spacing would change between the first call and the replay. It is
     * opaque payload here, never queried, so text is both correct and cheaper.
     */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    /** Retention horizon; records past it are pruned. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
