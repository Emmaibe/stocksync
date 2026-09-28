package com.lordibe.stocksync.platform.idempotency.service;

import com.lordibe.stocksync.platform.idempotency.domain.IdempotencyRecord;
import com.lordibe.stocksync.platform.idempotency.domain.IdempotencyStatus;
import com.lordibe.stocksync.platform.idempotency.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * The HTTP-level replay cache behind the {@code Idempotency-Key} header.
 *
 * <p>Distinct from the ledger's own {@code stock_movement.idempotency_key}, which
 * guarantees a movement is never written twice. This layer additionally returns
 * the <em>original response</em> to a retry, so a client that lost the reply to a
 * successful call gets the same body and status rather than an error — and it
 * covers endpoints that are not ledger writes.
 *
 * <p>Every method runs in its own transaction: the record has to survive whatever
 * happens to the request's transaction, including a rollback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** How long a completed response stays replayable. */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordRepository records;

    /**
     * Claims the key for this request, or reports what to do instead.
     *
     * <p>The claim is an insert, so two simultaneous retries race on
     * {@code uk_idempotency_key} and the loser is told the request is in flight
     * rather than running it a second time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyOutcome begin(UUID tenantId, String scope, String key, byte[] requestBody) {
        String hash = sha256(requestBody);

        Optional<IdempotencyRecord> existing =
                records.findByTenantIdAndScopeAndIdempotencyKey(tenantId, scope, key);
        if (existing.isPresent()) {
            return classify(existing.get(), hash);
        }

        IdempotencyRecord claim = IdempotencyRecord.builder()
                .tenantId(tenantId)
                .scope(scope)
                .idempotencyKey(key)
                .requestHash(hash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .expiresAt(OffsetDateTime.now().plus(RETENTION))
                .build();
        try {
            records.saveAndFlush(claim);
            return new IdempotencyOutcome.Proceed();
        } catch (DataIntegrityViolationException raced) {
            // Someone claimed it between our read and our insert.
            return records.findByTenantIdAndScopeAndIdempotencyKey(tenantId, scope, key)
                    .map(record -> classify(record, hash))
                    .orElseGet(IdempotencyOutcome.InFlight::new);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID tenantId, String scope, String key, int status, String body) {
        records.findByTenantIdAndScopeAndIdempotencyKey(tenantId, scope, key)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyStatus.COMPLETED);
                    record.setResponseStatus(status);
                    record.setResponseBody(body);
                });
    }

    /**
     * Releases the claim after a failure, so the client may retry the same key.
     *
     * <p>The record is deleted rather than marked FAILED: a failed attempt should
     * leave no trace that blocks a legitimate retry, and there is nothing worth
     * replaying.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID tenantId, String scope, String key) {
        records.findByTenantIdAndScopeAndIdempotencyKey(tenantId, scope, key)
                .ifPresent(records::delete);
    }

    private IdempotencyOutcome classify(IdempotencyRecord record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            log.warn("Idempotency key {} reused with a different body in scope {}",
                    record.getIdempotencyKey(), record.getScope());
            return new IdempotencyOutcome.BodyMismatch();
        }
        if (record.getStatus() == IdempotencyStatus.COMPLETED) {
            return new IdempotencyOutcome.Replay(
                    record.getResponseStatus() == null ? 200 : record.getResponseStatus(),
                    record.getResponseBody());
        }
        return new IdempotencyOutcome.InFlight();
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
