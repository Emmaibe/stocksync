package com.lordibe.stocksync.platform;

import com.lordibe.stocksync.platform.housekeeping.HousekeepingJob;
import com.lordibe.stocksync.platform.idempotency.domain.IdempotencyRecord;
import com.lordibe.stocksync.platform.idempotency.domain.IdempotencyStatus;
import com.lordibe.stocksync.platform.idempotency.repository.IdempotencyRecordRepository;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The sweep that keeps unbounded tables bounded. */
class HousekeepingIT extends AbstractPostgresIntegrationTest {

    @Autowired HousekeepingJob housekeeping;
    @Autowired IdempotencyRecordRepository records;
    @Autowired TenantRepository tenants;

    @Test
    @DisplayName("expired idempotency records are pruned and live ones are left alone")
    void prunesOnlyExpiredRecords() {
        UUID tenantId = tenants.save(Tenant.builder()
                .slug("sweep-" + UUID.randomUUID()).name("Sweep")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build()).getId();

        UUID staleId = records.save(record(tenantId, "stale", OffsetDateTime.now().minusDays(2)))
                .getId();
        UUID liveId = records.save(record(tenantId, "live", OffsetDateTime.now().plusDays(1)))
                .getId();

        housekeeping.sweep();

        assertThat(records.findById(staleId)).isEmpty();
        assertThat(records.findById(liveId)).isPresent();
    }

    private IdempotencyRecord record(UUID tenantId, String key, OffsetDateTime expiresAt) {
        return IdempotencyRecord.builder()
                .tenantId(tenantId)
                .scope("POST /test")
                .idempotencyKey(key + "-" + UUID.randomUUID())
                .requestHash("hash")
                .status(IdempotencyStatus.COMPLETED)
                .responseStatus(200)
                .responseBody("{}")
                .expiresAt(expiresAt)
                .build();
    }
}
