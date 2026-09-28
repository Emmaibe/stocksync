package com.lordibe.stocksync.platform.idempotency.repository;

import com.lordibe.stocksync.platform.idempotency.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByTenantIdAndScopeAndIdempotencyKey(
            UUID tenantId, String scope, String idempotencyKey);

    /** Housekeeping for records past their retention horizon. */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
