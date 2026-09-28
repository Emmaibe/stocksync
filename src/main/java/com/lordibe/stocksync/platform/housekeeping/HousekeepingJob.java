package com.lordibe.stocksync.platform.housekeeping;

import com.lordibe.stocksync.iam.repository.InvitationRepository;
import com.lordibe.stocksync.iam.repository.PasswordResetTokenRepository;
import com.lordibe.stocksync.platform.idempotency.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Periodic tidying of tables that would otherwise grow without bound.
 *
 * <p>Runs without a tenant bound, and deliberately so: these sweeps are
 * platform-wide. Both queries are bulk statements filtered on their own
 * timestamps, so they touch every tenant's rows by design — the tenant filter
 * does not apply to bulk JPQL updates in any case.
 *
 * <p><strong>Multi-instance caveat:</strong> with several replicas every one of
 * them runs this. The work is idempotent, so the outcome is correct either way,
 * but it is wasted effort and the deletes will contend. A leader election or a
 * scheduling lock (ShedLock) is the fix when this scales past one pod.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HousekeepingJob {

    private final IdempotencyRecordRepository idempotencyRecords;
    private final InvitationRepository invitations;
    private final PasswordResetTokenRepository resetTokens;

    /**
     * Hourly, offset off the hour so it does not coincide with every other
     * scheduled thing in the system.
     */
    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();

        int prunedRecords = idempotencyRecords.deleteExpired(now);
        int expiredInvitations = invitations.expireStale(now);
        int prunedResetTokens = resetTokens.deleteExpired(now);

        if (prunedRecords > 0 || expiredInvitations > 0 || prunedResetTokens > 0) {
            log.info("Housekeeping: pruned {} idempotency records, expired {} invitations, "
                            + "pruned {} reset tokens",
                    prunedRecords, expiredInvitations, prunedResetTokens);
        }
    }
}
